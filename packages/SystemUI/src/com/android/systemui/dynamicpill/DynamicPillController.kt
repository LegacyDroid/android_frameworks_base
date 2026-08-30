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

import android.app.ActivityTaskManager
import android.app.IActivityTaskManager
import android.app.TaskStackListener
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Display
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
 * Compact state shows the most recently triggered session. The pill is hidden while the
 * foreground app owns the currently displayed session.
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
) : CoreStartable, Dumpable, DynamicPillExpandedDialog.ActionListener {

    companion object {
        private const val TAG = "DynamicPillController"
        private const val CLOCK_TICK_INTERVAL_MS = 1000L
        private const val DUMP_PREFIX = "DynamicPillController"

        /** Package of the DeskClock app that owns the CLOCK pill session. */
        const val DESKCLOCK_PACKAGE = "com.android.deskclock"

        // Cross-process contract with com.android.deskclock.pill.PillClockContract.
        // DeskClock and SystemUI cannot share classes, so the strings are duplicated.
        private const val PILL_CLOCK_STATE_CHANGED =
            "com.android.deskclock.action.PILL_CLOCK_STATE_CHANGED"
        private const val PILL_CLOCK_STATE_REQUEST =
            "com.android.deskclock.action.REQUEST_PILL_CLOCK_STATE"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_STATE = "state"
        private const val EXTRA_REMAINING_MS = "remaining_ms"
        private const val EXTRA_TOTAL_MS = "total_ms"
        private const val EXTRA_ELAPSED_MS = "elapsed_ms"
        private const val TYPE_TIMER = "timer"
        private const val TYPE_STOPWATCH = "stopwatch"
        private const val STATE_RUNNING = "running"
        private const val STATE_PAUSED = "paused"
        private const val STATE_RESET = "reset"
    }

    private val callbacks = CopyOnWriteArrayList<DynamicPillCallback>()
    private val activeSessions = mutableMapOf<PillSourceType, PillSession>()
    @Volatile private var currentState = PillState()
    private var clockTickerRunning = false
    private var lastClockTickAt = 0L

    /** Binder to the activity task stack, used to track the foreground app. */
    private val activityTaskManager: IActivityTaskManager = ActivityTaskManager.getService()

    /** Package of the app currently on top of the main display, or null if unavailable. */
    private var topPackage: String? = null

    /** MediaController of the session currently shown in the pill, used for transport controls. */
    private var mediaController: MediaController? = null
    @Volatile private var mediaIsPlaying = false

    private val taskStackListener = object : TaskStackListener() {
        override fun onTaskStackChanged() {
            refreshTopPackage()
        }

        override fun onTaskMovedToFront(taskInfo: android.app.ActivityManager.RunningTaskInfo) {
            refreshTopPackage()
        }
    }

    private val clockStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent == null) return
            if (intent.getPackage() != DESKCLOCK_PACKAGE) return
            if (intent.action != PILL_CLOCK_STATE_CHANGED) return
            onClockStateBroadcast(intent)
        }
    }

    private val clockTicker = object : Runnable {
        override fun run() {
            advanceClockSession()
            mainHandler.postDelayed(this, CLOCK_TICK_INTERVAL_MS)
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
                    sessionKey = key,
                    token = data.token,
                )
                mediaController = data.token?.let { MediaController(context, it) }
                mediaIsPlaying = true
                addSession(session)
            } else {
                mediaIsPlaying = false
                mediaController = null
                removeSession(PillSourceType.MEDIA)
            }
        }

        override fun onMediaDataRemoved(key: String) {
            mediaController = null
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

        // Listen for clock state broadcasts from DeskClock.
        val filter = IntentFilter().apply {
            addAction(PILL_CLOCK_STATE_CHANGED)
        }
        context.registerReceiver(clockStateReceiver, filter, Context.RECEIVER_EXPORTED)

        // Ask DeskClock for the current timer/stopwatch state.
        context.sendBroadcast(
            Intent(PILL_CLOCK_STATE_REQUEST).setPackage(DESKCLOCK_PACKAGE)
        )

        // Track the foreground app to hide the pill while it owns the displayed session.
        activityTaskManager.registerTaskStackListener(taskStackListener)
        refreshTopPackage()

        startClockTicker()
    }

    /** Stop listening and release resources. */
    fun stop() {
        Log.d(TAG, "Stopping DynamicPillController")
        mediaDataManager.removeListener(mediaDataListener)
        recordingController.removeCallback(recordingStateCallback)
        context.unregisterReceiver(clockStateReceiver)
        activityTaskManager.unregisterTaskStackListener(taskStackListener)
        stopClockTicker()
        activeSessions.clear()
        currentState = PillState()
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
        val compact = sorted.firstOrNull()
        currentState = currentState.copy(
            activeSessions = sorted,
            isHiddenForForeground = matchesForeground(compact),
        )
        dispatchState()
    }

    private fun dispatchState() {
        callbacks.forEach { it.onPillStateChanged(currentState) }
    }

    /** Media card action dispatch. */
    override fun onMediaPlayPause() {
        val controller = mediaController ?: return
        if (mediaIsPlaying) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
    }

    override fun onMediaNext() {
        mediaController?.transportControls?.skipToNext()
    }

    override fun onMediaPrevious() {
        mediaController?.transportControls?.skipToPrevious()
    }

    private fun onMediaSessionsChanged(controllers: List<MediaController>?) {
        if (controllers.isNullOrEmpty()) {
            mediaController = null
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
                token = playing.sessionToken,
            )
            mediaController = MediaController(context, playing.sessionToken)
            mediaIsPlaying = true
            addSession(session)
        } else {
            mediaController = null
            removeSession(PillSourceType.MEDIA)
        }
    }

    private fun onClockStateBroadcast(intent: Intent) {
        val type = intent.getStringExtra(EXTRA_TYPE)
        val state = intent.getStringExtra(EXTRA_STATE) ?: return
        val isReset = state == STATE_RESET
        val currentClock = activeSessions[PillSourceType.CLOCK] as? PillSession.Clock
        when (type) {
            TYPE_TIMER -> {
                if (isReset) {
                    // Keep a stopwatch session if one is active.
                    if (currentClock != null && !currentClock.isStopwatch) {
                        removeSession(PillSourceType.CLOCK)
                    }
                } else {
                    val total = intent.getLongExtra(EXTRA_TOTAL_MS, 0L)
                    val remaining = intent.getLongExtra(EXTRA_REMAINING_MS, 0L)
                    val elapsed = (total - remaining).coerceAtLeast(0L)
                    addSession(
                        PillSession.Clock(
                            isStopwatch = false,
                            elapsedMillis = elapsed,
                            isPaused = state == STATE_PAUSED,
                            totalCountdownMillis = total,
                        )
                    )
                }
            }
            TYPE_STOPWATCH -> {
                if (isReset) {
                    // Keep a timer session if one is active.
                    if (currentClock != null && currentClock.isStopwatch) {
                        removeSession(PillSourceType.CLOCK)
                    }
                } else {
                    val elapsed = intent.getLongExtra(EXTRA_ELAPSED_MS, 0L)
                    addSession(
                        PillSession.Clock(
                            isStopwatch = true,
                            elapsedMillis = elapsed,
                            isPaused = state == STATE_PAUSED,
                        )
                    )
                }
            }
            else -> {
                if (isReset) {
                    removeSession(PillSourceType.CLOCK)
                }
            }
        }
    }

    /** Advances the elapsed time of a running clock session using real elapsed time deltas. */
    private fun advanceClockSession() {
        val now = SystemClock.elapsedRealtime()
        val delta = if (lastClockTickAt == 0L) 0L else (now - lastClockTickAt).coerceIn(0L, 5000L)
        lastClockTickAt = now

        val clockSession = activeSessions[PillSourceType.CLOCK] as? PillSession.Clock ?: return
        if (!clockSession.isPaused && delta > 0L) {
            addSession(clockSession.copy(elapsedMillis = clockSession.elapsedMillis + delta))
        }
    }

    private fun startClockTicker() {
        if (!clockTickerRunning) {
            clockTickerRunning = true
            lastClockTickAt = 0L
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

    private fun refreshTopPackage() {
        val pkg = try {
            activityTaskManager
                .getTasks(1, false, false, Display.INVALID_DISPLAY)
                .firstOrNull()?.topActivity?.packageName
        } catch (e: Exception) {
            null
        }
        if (pkg != topPackage) {
            topPackage = pkg
            rebuildState()
        }
    }

    /** Whether the currently displayed session is owned by the foreground app. */
    private fun matchesForeground(session: PillSession?): Boolean {
        val owner = ownerPackage(session) ?: return false
        val top = topPackage ?: return false
        return owner == top
    }

    /**
     * Package owning the given session: the media app for MEDIA, DeskClock for CLOCK, and no owner
     * (never hidden) for RECORDING.
     */
    private fun ownerPackage(session: PillSession?): String? = when (session) {
        is PillSession.Media -> session.packageName
        is PillSession.Clock -> DESKCLOCK_PACKAGE
        else -> null
    }

    override fun dump(pw: PrintWriter, args: Array<String>) {
        pw.println("$DUMP_PREFIX:")
        pw.println("  activeSessions=${activeSessions.size}")
        activeSessions.forEach { (source, session) ->
            pw.println("    $source: $session")
        }
        pw.println("  state=$currentState")
        pw.println("  topPackage=$topPackage")
    }
}