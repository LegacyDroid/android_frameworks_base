/*
 * Copyright (C) 2026 The LegacyDroid Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.dynamicpill

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.service.notification.StatusBarNotification
import android.util.Log
import com.android.systemui.CoreStartable
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.screenrecord.RecordingController
import java.io.PrintWriter
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject

/**
 * Controller for the Dynamic Pill status bar feature.
 *
 * Manages subscriptions to media, clock (timer/stopwatch), and recording sessions.
 * Compact state shows the most recently triggered session.
 *
 * State updates are dispatched to registered [DynamicPillCallback] instances.
 */
@SysUISingleton
class DynamicPillController @Inject constructor(
    @Application private val context: Context,
    @Main private val mainHandler: Handler,
    private val mediaDataManager: MediaDataManager,
    private val recordingController: RecordingController,
    private val mediaSessionManager: MediaSessionManager,
    private val dumpManager: DumpManager,
) : CoreStartable, Dumpable {

    companion object {
        private const val TAG = "DynamicPillController"
        private const val CLOCK_TICK_INTERVAL_MS = 1000L
        private const val DESKCLOCK_POLL_INTERVAL_MS = 2000L
        private const val DUMP_PREFIX = "DynamicPillController"
        private const val DESKCLOCK_PACKAGE = "com.android.deskclock"
        private const val TIMER_CHANNEL = "timerNotification"
        private const val STOPWATCH_CHANNEL = "stopwatchNotification"
        private const val TIMER_NOTIF_ID = Int.MAX_VALUE - 2
        private const val STOPWATCH_NOTIF_ID = Int.MAX_VALUE - 1
    }

    private val callbacks = CopyOnWriteArrayList<DynamicPillCallback>()
    private val activeSessions = mutableMapOf<PillSourceType, PillSession>()
    @Volatile private var currentState = PillState()
    private var clockTickerRunning = false
    private var deskclockPollingRunning = false
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    private val clockTicker = object : Runnable {
        override fun run() {
            updateClockSessions()
            mainHandler.postDelayed(this, CLOCK_TICK_INTERVAL_MS)
        }
    }

    private val deskclockPoller = object : Runnable {
        override fun run() {
            checkDeskclockNotifications()
            mainHandler.postDelayed(this, DESKCLOCK_POLL_INTERVAL_MS)
        }
    }

    private val mediaDataListener = object : MediaDataManager.Listener {
        override fun onMediaDataLoaded(
            key: String,
            oldKey: String?,
            data: com.android.systemui.media.controls.shared.model.MediaData,
            immediately: Boolean,
            receivedSmartspaceCardLatency: Int,
            isSsReactivated: Boolean,
        ) {
            if (data.isPlaying == true) {
                val session = PillSession.Media(
                    packageName = data.packageName,
                    title = data.song?.toString() ?: "",
                    artist = data.artist?.toString() ?: "",
                    isPlaying = true,
                    duration = 0L,
                    position = 0L,
                )
                addSession(session)
            } else {
                removeSession(PillSourceType.MEDIA)
            }
        }

        override fun onMediaDataRemoved(key: String) {
            removeSession(PillSourceType.MEDIA)
        }
    }

    private val recordingStateCallback = object : RecordingController.RecordingStateChangeCallback {
        override fun onRecordingStart() {
            val session = PillSession.Recording(
                isScreenRecord = true,
                elapsedMillis = 0L,
                isPaused = false,
            )
            addSession(session)
        }

        override fun onRecordingEnd() {
            removeSession(PillSourceType.RECORDING)
        }
    }

    init {
        dumpManager.registerDumpable(DUMP_PREFIX, this)
    }

    /** Start listening for all data sources. Must be called on main thread. */
    override fun start() {
        Log.d(TAG, "Starting DynamicPillController")
        mediaDataManager.addListener(mediaDataListener)
        recordingController.addCallback(recordingStateCallback)
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            onMediaSessionsChanged(controllers)
        }
        val componentName = ComponentName(context, "com.android.systemui.media.MediaSessionBasedFilter")
        mediaSessionManager.addOnActiveSessionsChangedListener(listener, componentName)
        startClockTicker()
        startDeskclockPolling()
    }

    /** Stop listening and release resources. */
    fun stop() {
        Log.d(TAG, "Stopping DynamicPillController")
        mediaDataManager.removeListener(mediaDataListener)
        recordingController.removeCallback(recordingStateCallback)
        stopClockTicker()
        stopDeskclockPolling()
        activeSessions.clear()
        dispatchState()
    }

    fun addCallback(callback: DynamicPillCallback) {
        callbacks.add(callback)
        callback.onPillStateChanged(currentState)
    }

    fun removeCallback(callback: DynamicPillCallback) {
        callbacks.remove(callback)
    }

    /** Toggle expanded state. Called when the pill is tapped. */
    fun toggleExpanded() {
        currentState = currentState.copy(isExpanded = !currentState.isExpanded)
        dispatchState()
    }

    /** Set expanded state externally (e.g., from touch handling). */
    fun setExpanded(expanded: Boolean) {
        if (currentState.isExpanded != expanded) {
            currentState = currentState.copy(isExpanded = expanded)
            dispatchState()
        }
    }

    fun getState(): PillState = currentState

    private fun addSession(session: PillSession) {
        activeSessions[session.source] = session
        rebuildState()
    }

    private fun removeSession(source: PillSourceType) {
        if (activeSessions.remove(source) != null) {
            rebuildState()
        }
    }

    private fun rebuildState() {
        val sorted = activeSessions.values.sortedByDescending { it.timestamp }
        currentState = currentState.copy(activeSessions = sorted)
        dispatchState()
    }

    private fun dispatchState() {
        callbacks.forEach { it.onPillStateChanged(currentState) }
    }

    private fun onMediaSessionsChanged(controllers: List<MediaController>?) {
        if (controllers.isNullOrEmpty()) {
            removeSession(PillSourceType.MEDIA)
            return
        }
        val playing = controllers.firstOrNull { ctrl ->
            ctrl.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        if (playing != null) {
            val metadata = playing.metadata
            val session = PillSession.Media(
                packageName = playing.packageName,
                title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "",
                artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "",
                isPlaying = true,
                duration = 0L,
                position = 0L,
            )
            addSession(session)
        } else {
            removeSession(PillSourceType.MEDIA)
        }
    }

    private fun updateClockSessions() {
        val clockSession = activeSessions[PillSourceType.CLOCK] as? PillSession.Clock ?: return
        val updated = clockSession.copy(
            elapsedMillis = clockSession.elapsedMillis + CLOCK_TICK_INTERVAL_MS,
        )
        addSession(updated)
    }

    private fun startClockTicker() {
        if (!clockTickerRunning) {
            clockTickerRunning = true
            mainHandler.post(clockTicker)
        }
    }

    private fun stopClockTicker() {
        clockTickerRunning = false
        mainHandler.removeCallbacks(clockTicker)
    }

    /** Add a clock session (timer or stopwatch). Called from clock integration. */
    fun addClockSession(isStopwatch: Boolean, elapsedMillis: Long, isPaused: Boolean, totalCountdownMillis: Long = 0L) {
        val session = PillSession.Clock(
            isStopwatch = isStopwatch,
            elapsedMillis = elapsedMillis,
            isPaused = isPaused,
            totalCountdownMillis = totalCountdownMillis,
        )
        addSession(session)
    }

    /** Remove the clock session. */
    fun removeClockSession() {
        removeSession(PillSourceType.CLOCK)
    }

    /** Update clock session state. */
    fun updateClockSession(isPaused: Boolean? = null, elapsedMillis: Long? = null) {
        val current = activeSessions[PillSourceType.CLOCK] as? PillSession.Clock ?: return
        val updated = current.copy(
            isPaused = isPaused ?: current.isPaused,
            elapsedMillis = elapsedMillis ?: current.elapsedMillis,
        )
        addSession(updated)
    }

    private fun startDeskclockPolling() {
        if (!deskclockPollingRunning) {
            deskclockPollingRunning = true
            mainHandler.post(deskclockPoller)
        }
    }

    private fun stopDeskclockPolling() {
        deskclockPollingRunning = false
        mainHandler.removeCallbacks(deskclockPoller)
    }

    private fun checkDeskclockNotifications() {
        try {
            val notifications = notificationManager?.activeNotifications ?: return
            var foundTimer = false
            var foundStopwatch = false

            for (sbn: StatusBarNotification in notifications) {
                if (sbn.packageName != DESKCLOCK_PACKAGE) continue
                val channelId = sbn.notification?.channelId ?: continue

                if (channelId == TIMER_CHANNEL && sbn.id == TIMER_NOTIF_ID) {
                    foundTimer = true
                    val elapsed = extractElapsedFromNotification(sbn)
                    addSession(PillSession.Clock(
                        isStopwatch = false,
                        elapsedMillis = elapsed,
                        isPaused = !sbn.isOngoing,
                    ))
                } else if (channelId == STOPWATCH_CHANNEL && sbn.id == STOPWATCH_NOTIF_ID) {
                    foundStopwatch = true
                    val elapsed = extractElapsedFromNotification(sbn)
                    addSession(PillSession.Clock(
                        isStopwatch = true,
                        elapsedMillis = elapsed,
                        isPaused = !sbn.isOngoing,
                    ))
                }
            }

            if (!foundTimer && activeSessions.containsKey(PillSourceType.CLOCK)) {
                val current = activeSessions[PillSourceType.CLOCK] as? PillSession.Clock
                if (current != null && !current.isStopwatch) {
                    removeSession(PillSourceType.CLOCK)
                }
            }
            if (!foundStopwatch && activeSessions.containsKey(PillSourceType.CLOCK)) {
                val current = activeSessions[PillSourceType.CLOCK] as? PillSession.Clock
                if (current != null && current.isStopwatch) {
                    removeSession(PillSourceType.CLOCK)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot access active notifications", e)
        }
    }

    private fun extractElapsedFromNotification(sbn: StatusBarNotification): Long {
        val extras = sbn.notification?.extras ?: return 0L
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: return 0L
        return try {
            parseTimeStringToMillis(text)
        } catch (e: Exception) {
            0L
        }
    }

    private fun parseTimeStringToMillis(time: String): Long {
        val parts = time.split(":")
        return when (parts.size) {
            3 -> {
                val h = parts[0].toLongOrNull() ?: 0L
                val m = parts[1].toLongOrNull() ?: 0L
                val s = parts[2].toLongOrNull() ?: 0L
                (h * 3600 + m * 60 + s) * 1000
            }
            2 -> {
                val m = parts[0].toLongOrNull() ?: 0L
                val s = parts[1].toLongOrNull() ?: 0L
                (m * 60 + s) * 1000
            }
            else -> 0L
        }
    }

    override fun dump(pw: PrintWriter, args: Array<String>) {
        pw.println("$DUMP_PREFIX:")
        pw.println("  activeSessions=${activeSessions.size}")
        activeSessions.forEach { (source, session) ->
            pw.println("    $source: $session")
        }
        pw.println("  state=$currentState")
    }
}
