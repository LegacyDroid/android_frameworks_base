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

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.R
import java.util.Locale

/**
 * Expanded floating card for the Dynamic Pill.
 *
 * When the compact pill is tapped, this dialog presents a vertical stack of
 * contextual controls — one card per active session, ordered by recency
 * (newest at the top).
 */
class DynamicPillExpandedDialog(
    private val context: Context,
    private val windowManager: WindowManager,
) {

    companion object {
        private const val EXPANDED_MARGIN_HORIZONTAL_DP = 12
        private const val CARD_CORNER_RADIUS_DP = 28f
        private const val ANIM_DURATION_MS = 300L
    }

    private var containerView: View? = null
    private var isShowing = false
    private var onActionListener: ActionListener? = null

    interface ActionListener {
        fun onMediaPlayPause() {}
        fun onMediaPrevious() {}
        fun onMediaNext() {}
        fun onMediaSeek(position: Long) {}
        fun onClockPauseResume() {}
        fun onClockLap() {}
        fun onClockAddMinute() {}
        fun onRecordingPauseResume() {}
        fun onRecordingStop() {}
        fun onDismiss() {}
    }

    fun setActionListener(listener: ActionListener) {
        onActionListener = listener
    }

    fun show(state: PillState) {
        if (isShowing) {
            updateContent(state)
            return
        }

        val view = buildExpandedView(state)
        containerView = view

        val params = createLayoutParams()
        windowManager.addView(view, params)
        isShowing = true

        animateIn(view)
    }

    fun dismiss() {
        if (!isShowing) return
        val view = containerView ?: return

        val fadeOut = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f)
        val scaleDown = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 0.9f)
        val scaleDownY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 0.9f)

        AnimatorSet().apply {
            playTogether(fadeOut, scaleDown, scaleDownY)
            duration = ANIM_DURATION_MS / 2
            interpolator = DecelerateInterpolator(2f)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    removeViewFromWindow(view)
                }
            })
            start()
        }

        isShowing = false
        containerView = null
        onActionListener?.onDismiss()
    }

    fun updateContent(state: PillState) {
        val view = containerView ?: return
        val cardsContainer = view.findViewById<LinearLayout>(R.id.expanded_cards_container) ?: return
        cardsContainer.removeAllViews()
        populateCards(state, cardsContainer)
    }

    // ── View construction ──────────────────────────────────────────────────

    private fun buildExpandedView(state: PillState): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        val cardsContainer = LinearLayout(context).apply {
            id = R.id.expanded_cards_container
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            background = createContainerBackground()
            val pad = dpToPx(8)
            setPadding(pad, pad, pad, pad)
        }

        populateCards(state, cardsContainer)
        root.addView(cardsContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        return root
    }

    private fun populateCards(state: PillState, container: LinearLayout) {
        // Sessions are already sorted newest-first by the controller
        for (session in state.activeSessions) {
            val card = when (session) {
                is PillSession.Media -> createMediaCard(session)
                is PillSession.Clock -> createClockCard(session)
                is PillSession.Recording -> createRecordingCard(session)
            }
            container.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (container.childCount > 0) topMargin = dpToPx(4)
            })
        }
    }

    // ── Card builders ──────────────────────────────────────────────────────

    private fun createMediaCard(media: PillSession.Media): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createCardBackground()
            setPadding(dpToPx(16), dpToPx(12), dpToPx(12), dpToPx(12))
        }

        // Track info
        val info = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        info.addView(TextView(context).apply {
            text = media.title.ifEmpty { "Unknown" }
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })

        info.addView(TextView(context).apply {
            text = media.artist
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })

        card.addView(info)

        // Controls: Prev | Play/Pause | Next
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        controls.addView(makeBtn(R.drawable.ic_media_prev) { onActionListener?.onMediaPrevious() })
        controls.addView(makeBtn(R.drawable.ic_media_play) { onActionListener?.onMediaPlayPause() })
        controls.addView(makeBtn(R.drawable.ic_media_next) { onActionListener?.onMediaNext() })

        card.addView(controls)
        return card
    }

    private fun createClockCard(clock: PillSession.Clock): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createCardBackground()
            setPadding(dpToPx(16), dpToPx(12), dpToPx(12), dpToPx(12))
        }

        val info = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        info.addView(TextView(context).apply {
            text = if (clock.isStopwatch) "Stopwatch" else "Timer"
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 12f
        })

        info.addView(TextView(context).apply {
            text = formatTime(clock.elapsedMillis)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
        })

        card.addView(info)

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val pauseResumeRes = if (clock.isPaused) R.drawable.ic_media_play else R.drawable.ic_media_pause
        controls.addView(makeBtn(pauseResumeRes) { onActionListener?.onClockPauseResume() })

        if (clock.isStopwatch) {
            controls.addView(makeBtn(R.drawable.ic_add) { onActionListener?.onClockLap() })
        } else {
            controls.addView(makeBtn(R.drawable.ic_add) { onActionListener?.onClockAddMinute() })
        }

        card.addView(controls)
        return card
    }

    private fun createRecordingCard(recording: PillSession.Recording): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createCardBackground()
            setPadding(dpToPx(16), dpToPx(12), dpToPx(12), dpToPx(12))
        }

        val info = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        info.addView(TextView(context).apply {
            text = if (recording.isScreenRecord) "Screen Recording" else "Recording"
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 12f
        })

        info.addView(TextView(context).apply {
            text = formatTime(recording.elapsedMillis)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
        })

        card.addView(info)

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val pauseRes = if (recording.isPaused) R.drawable.ic_media_play else R.drawable.ic_media_pause
        controls.addView(makeBtn(pauseRes) { onActionListener?.onRecordingPauseResume() })
        controls.addView(makeBtn(R.drawable.ic_media_pause) { onActionListener?.onRecordingStop() })

        card.addView(controls)
        return card
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun makeBtn(drawableRes: Int, onClick: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            setImageResource(drawableRes)
            background = null
            val size = dpToPx(40)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dpToPx(4)
                marginEnd = dpToPx(4)
            }
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            setOnClickListener { onClick() }
            colorFilter = android.graphics.PorterDuffColorFilter(
                0xFFFFFFFF.toInt(),
                android.graphics.PorterDuff.Mode.SRC_IN,
            )
        }
    }

    private fun createContainerBackground(): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, CARD_CORNER_RADIUS_DP, resources.displayMetrics,
            )
            setColor(resolveSurfaceColor())
        }
    }

    private fun createCardBackground(): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics,
            )
            setColor(0x1AFFFFFF)
        }
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val marginPx = dpToPx(EXPANDED_MARGIN_HORIZONTAL_DP)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = marginPx
            y = dpToPx(8)
        }
    }

    private fun animateIn(view: View) {
        view.alpha = 0f
        view.scaleX = 0.9f
        view.scaleY = 0.9f

        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_X, 0.9f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.9f, 1f),
            )
            duration = ANIM_DURATION_MS
            interpolator = DecelerateInterpolator(2f)
            start()
        }
    }

    private fun removeViewFromWindow(view: View) {
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) { }
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun resolveSurfaceColor(): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(
                com.android.internal.R.attr.colorSurface, tv, true
            )
        ) tv.data else 0xFF303030.toInt()
    }

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics,
    ).toInt()

    private val resources get() = context.resources
}
