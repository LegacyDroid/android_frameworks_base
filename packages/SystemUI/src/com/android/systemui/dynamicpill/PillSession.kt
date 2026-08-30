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

import android.media.session.MediaSession

/** Source type of a dynamic pill session. */
enum class PillSourceType {
    RECORDING,
    CLOCK,
    MEDIA;
}

/** Base class for all dynamic pill session data. */
sealed class PillSession(val source: PillSourceType, val timestamp: Long = System.currentTimeMillis()) {
    /** Media playback session. */
    data class Media(
        val packageName: String,
        val title: String,
        val artist: String,
        val isPlaying: Boolean,
        val duration: Long,
        val position: Long,
        val sessionKey: String? = null,
        val token: MediaSession.Token? = null,
        /**
         * Elapsed realtime (ms) of the last observed PLAYING state. A session that
         * never played (or last played longer ago than the retain window) does not
         * populate the pill, so a fresh boot with no active playback stays clean.
         */
        val lastActiveAt: Long = 0L,
    ) : PillSession(PillSourceType.MEDIA)

    /** Clock timer or stopwatch session. */
    data class Clock(
        val isStopwatch: Boolean,
        val elapsedMillis: Long,
        val isPaused: Boolean,
        val totalCountdownMillis: Long = 0L,
    ) : PillSession(PillSourceType.CLOCK)

    /** Screen or voice recording session. */
    data class Recording(
        val isScreenRecord: Boolean,
        val elapsedMillis: Long,
        val isPaused: Boolean,
    ) : PillSession(PillSourceType.RECORDING)
}

/** State emitted by the DynamicPillController. */
data class PillState(
    val activeSessions: List<PillSession> = emptyList(),
    val isExpanded: Boolean = false,
    /** True while the foreground app owns the currently displayed session. */
    val isHiddenForForeground: Boolean = false,
) {
    val hasActiveSessions: Boolean get() = activeSessions.isNotEmpty()

    /**
     * The session to display in compact mode.
     * Most recently triggered (highest timestamp) wins.
     */
    val compactSession: PillSession? get() = activeSessions.maxByOrNull { it.timestamp }

    /** Whether the pill should be shown at all. */
    val isPillVisible: Boolean get() = hasActiveSessions && !isHiddenForForeground

    /** Distinct source types currently active. */
    val activeSourceTypes: Set<PillSourceType>
        get() = activeSessions.map { it.source }.toSet()
}
