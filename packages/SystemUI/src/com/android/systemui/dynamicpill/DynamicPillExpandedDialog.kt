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

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.R
import java.util.Locale

class DynamicPillExpandedDialog(
    private val context: Context,
    private val windowManager: WindowManager,
) {

    companion object {
        private const val EXPANDED_MARGIN_HORIZONTAL_DP = 20
        private const val CARD_CORNER_RADIUS_DP = 28f
        private const val PILL_CORNER_RADIUS_DP = 100f
        private const val ANIM_DURATION_MS = 380L
        private const val DISMISS_DURATION_MS = 280L

        private val EASE_OUT = PathInterpolator(0.0f, 0.0f, 0.2f, 1.0f)
        private val EASE_IN_OUT = PathInterpolator(0.4f, 0.0f, 0.2f, 1.0f)
        private val BOUNCY = PathInterpolator(0.34f, 1.4f, 0.64f, 1.0f)
        private val SNAP = PathInterpolator(0.4f, 0.0f, 0.2f, 1.0f)
    }

    private var containerView: View? = null
    private var isShowing = false
    private var isDismissing = false
    private var activeAnimator: android.animation.Animator? = null
    private var onActionListener: ActionListener? = null
    private var onDismissListener: (() -> Unit)? = null

    private var pillScreenX = 0
    private var pillScreenY = 0
    private var pillWidth = 0
    private var pillHeight = 0
    private var onMorphStarted: (() -> Unit)? = null
    private var onMorphFinished: (() -> Unit)? = null

    fun setOnDismissListener(listener: () -> Unit) {
        onDismissListener = listener
    }

    fun isShowing(): Boolean = isShowing

    /** Whether a dismiss morph is currently in progress. */
    fun dismissInProgress(): Boolean = isDismissing

    fun setOnMorphListeners(started: Runnable, finished: Runnable) {
        onMorphStarted = { started.run() }
        onMorphFinished = { finished.run() }
    }

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

    fun show(state: PillState, pillRect: IntArray? = null) {
        if (isDismissing) return
        if (isShowing) {
            updateContent(state)
            return
        }

        if (pillRect != null && pillRect.size >= 4) {
            pillScreenX = pillRect[0]
            pillScreenY = pillRect[1]
            pillWidth = pillRect[2]
            pillHeight = pillRect[3]
        }

        val view = buildExpandedView(state)
        containerView = view

        // Hide the view until the morph initial state is set up; without
        // this the expanded card flashes at full size for one frame before
        // the morph animation scales it down to the pill origin.
        view.alpha = 0f
        val params = createLayoutParams()
        windowManager.addView(view, params)
        isShowing = true

        morphExpand(view)
    }

    fun dismiss() {
        if (!isShowing || isDismissing) return
        val view = containerView ?: return

        isDismissing = true
        isShowing = false

        // Stop an in-flight expand morph: cancelling fires the expand
        // animator's own (captured) end listener, and the dismiss animation
        // then starts from the current visual state instead of fighting the
        // expand animator for the same properties.
        activeAnimator?.cancel()
        activeAnimator = null

        // Capture the morph listeners bound to THIS dismiss animation. Reading
        // the members at fire time would let a still-running expand animator
        // invoke the dismiss listeners and reveal the pill mid-morph.
        val dismissMorphStarted = onMorphStarted
        val dismissMorphFinished = onMorphFinished

        val container = view.findViewById<LinearLayout>(R.id.expanded_cards_container)
        if (container != null && pillWidth > 0 && pillHeight > 0 && container.width > 0) {
            val cw = container.width.toFloat()
            val ch = container.height.toFloat()
            val targetScaleX = pillWidth / cw
            val targetScaleY = pillHeight / ch

            val containerLoc = IntArray(2)
            container.getLocationOnScreen(containerLoc)
            val targetTranslationX = (pillScreenX + pillWidth / 2f) - (containerLoc[0] + cw / 2f)
            val targetTranslationY = (pillScreenY + pillHeight / 2f) - (containerLoc[1] + ch / 2f)

            dismissMorphStarted?.invoke()

            val containerBg = container.background as? GradientDrawable
            val startCornerRadius = containerBg?.cornerRadius ?: dpToPxF(CARD_CORNER_RADIUS_DP)
            val pillCornerPx = dpToPxF(PILL_CORNER_RADIUS_DP)

            val childCount = container.childCount
            val cardAlphaAnimators = mutableListOf<android.animation.ObjectAnimator>()
            val cardScaleXAnimators = mutableListOf<android.animation.ObjectAnimator>()
            val cardScaleYAnimators = mutableListOf<android.animation.ObjectAnimator>()

            for (i in 0 until childCount) {
                val child = container.getChildAt(i)
                val reverseIndex = (childCount - 1 - i)
                val delay = reverseIndex * 18L

                cardAlphaAnimators.add(
                    android.animation.ObjectAnimator.ofFloat(child, View.ALPHA, 1f, 0f).apply {
                        startDelay = delay
                    }
                )
                cardScaleXAnimators.add(
                    android.animation.ObjectAnimator.ofFloat(child, View.SCALE_X, 1f, 0.88f).apply {
                        startDelay = delay
                    }
                )
                cardScaleYAnimators.add(
                    android.animation.ObjectAnimator.ofFloat(child, View.SCALE_Y, 1f, 0.88f).apply {
                        startDelay = delay
                    }
                )
            }

            val scaleAnimX = android.animation.ObjectAnimator.ofFloat(container, View.SCALE_X, container.scaleX, targetScaleX)
            val scaleAnimY = android.animation.ObjectAnimator.ofFloat(container, View.SCALE_Y, container.scaleY, targetScaleY)
            val transAnimX = android.animation.ObjectAnimator.ofFloat(container, View.TRANSLATION_X, container.translationX, targetTranslationX)
            val transAnimY = android.animation.ObjectAnimator.ofFloat(container, View.TRANSLATION_Y, container.translationY, targetTranslationY)

            val cornerAnimator = ValueAnimator.ofFloat(startCornerRadius, pillCornerPx).apply {
                addUpdateListener { anim ->
                    containerBg?.cornerRadius = anim.animatedValue as Float
                }
            }

            val animator = android.animation.AnimatorSet()
            animator.playTogether(
                scaleAnimX, scaleAnimY, transAnimX, transAnimY, cornerAnimator,
                *cardAlphaAnimators.toTypedArray(),
                *cardScaleXAnimators.toTypedArray(),
                *cardScaleYAnimators.toTypedArray(),
            )
            animator.duration = DISMISS_DURATION_MS
            animator.interpolator = EASE_IN_OUT
            animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    dismissMorphFinished?.invoke()
                    removeViewFromWindow(view)
                    onDismissListener?.invoke()
                    isDismissing = false
                }
            })
            activeAnimator = animator
            animator.start()
        } else {
            dismissMorphFinished?.invoke()
            removeViewFromWindow(view)
            onDismissListener?.invoke()
            isDismissing = false
        }
    }

    fun updateContent(state: PillState) {
        val view = containerView ?: return
        val cardsContainer = view.findViewById<LinearLayout>(R.id.expanded_cards_container) ?: return
        cardsContainer.removeAllViews()
        populateCards(state, cardsContainer)
    }

    private fun buildExpandedView(state: PillState): View {
        val root = FrameLayout(context)

        val cardsContainer = LinearLayout(context).apply {
            id = R.id.expanded_cards_container
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            background = createContainerBackground()
            val pad = dpToPx(8)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
        }

        populateCards(state, cardsContainer)

        val containerParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dpToPx(40)
            marginStart = dpToPx(EXPANDED_MARGIN_HORIZONTAL_DP)
            marginEnd = dpToPx(EXPANDED_MARGIN_HORIZONTAL_DP)
        }
        root.addView(cardsContainer, containerParams)

        root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (onDismissListener != null) {
                    onDismissListener?.invoke()
                } else {
                    dismiss()
                }
            }
            true
        }

        return root
    }

    private fun morphExpand(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.expanded_cards_container) ?: return

        container.post {
            if (!isShowing || isDismissing) return@post
            // Restore visibility now that the morph initial state is set up.
            view.alpha = 1f
            val cw = container.width.toFloat()
            val ch = container.height.toFloat()
            if (cw <= 0f || ch <= 0f) return@post

            if (pillWidth > 0 && pillHeight > 0) {
                val containerLoc = IntArray(2)
                container.getLocationOnScreen(containerLoc)

                val startScaleX = pillWidth / cw
                val startScaleY = pillHeight / ch
                val startTransX = (pillScreenX + pillWidth / 2f) - (containerLoc[0] + cw / 2f)
                val startTransY = (pillScreenY + pillHeight / 2f) - (containerLoc[1] + ch / 2f)

                container.scaleX = startScaleX
                container.scaleY = startScaleY
                container.translationX = startTransX
                container.translationY = startTransY

                // Capture the listeners bound to this expand animation so a
                // later dismiss re-arming the members can not affect this one.
                val expandMorphStarted = onMorphStarted
                val expandMorphFinished = onMorphFinished
                expandMorphStarted?.invoke()

                val containerBg = container.background as? GradientDrawable
                val pillCornerPx = dpToPxF(PILL_CORNER_RADIUS_DP)
                val targetCornerPx = dpToPxF(CARD_CORNER_RADIUS_DP)

                val childCount = container.childCount

                for (i in 0 until childCount) {
                    val child = container.getChildAt(i)
                    child.alpha = 0f
                    child.scaleX = 0.6f
                    child.scaleY = 0.6f
                    child.translationY = 24f

                    child.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationY(0f)
                        .setDuration(ANIM_DURATION_MS)
                        .setStartDelay(50L + i * 28L)
                        .setInterpolator(BOUNCY)
                        .start()
                }

                val scaleAnimX = android.animation.ObjectAnimator.ofFloat(container, View.SCALE_X, startScaleX, 1f)
                val scaleAnimY = android.animation.ObjectAnimator.ofFloat(container, View.SCALE_Y, startScaleY, 1f)
                val transAnimX = android.animation.ObjectAnimator.ofFloat(container, View.TRANSLATION_X, startTransX, 0f)
                val transAnimY = android.animation.ObjectAnimator.ofFloat(container, View.TRANSLATION_Y, startTransY, 0f)

                val cornerAnimator = ValueAnimator.ofFloat(pillCornerPx, targetCornerPx).apply {
                    addUpdateListener { anim ->
                        containerBg?.cornerRadius = anim.animatedValue as Float
                    }
                }

                val animator = android.animation.AnimatorSet()
                animator.playTogether(scaleAnimX, scaleAnimY, transAnimX, transAnimY, cornerAnimator)
                animator.duration = ANIM_DURATION_MS
                animator.interpolator = BOUNCY
                animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        expandMorphFinished?.invoke()
                    }
                })
                activeAnimator = animator
                animator.start()
            }
        }
    }

    private fun removeViewFromWindow(view: View) {
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) { }
    }

    private fun populateCards(state: PillState, container: LinearLayout) {
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

    private fun createMediaCard(media: PillSession.Media): View {
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
            text = media.title.ifEmpty { "Unknown" }
            setTextColor(0xFF000000.toInt())
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })

        info.addView(TextView(context).apply {
            text = media.artist
            setTextColor(0x80000000.toInt())
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })

        card.addView(info)

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        controls.addView(makeBtn(R.drawable.ic_media_prev) { onActionListener?.onMediaPrevious() })
        val playPauseRes = if (media.isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play
        controls.addView(makeBtn(playPauseRes) { onActionListener?.onMediaPlayPause() })
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
            setTextColor(0x80000000.toInt())
            textSize = 12f
        })

        info.addView(TextView(context).apply {
            val displayMillis = if (clock.isStopwatch) {
                clock.elapsedMillis
            } else {
                (clock.totalCountdownMillis - clock.elapsedMillis).coerceAtLeast(0L)
            }
            text = formatTime(displayMillis)
            setTextColor(0xFF000000.toInt())
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
            controls.addView(makeBtn(R.drawable.ic_add_circle) { onActionListener?.onClockLap() })
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
            setTextColor(0x80000000.toInt())
            textSize = 12f
        })

        info.addView(TextView(context).apply {
            text = formatTime(recording.elapsedMillis)
            setTextColor(0xFF000000.toInt())
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
                0xFF000000.toInt(),
                android.graphics.PorterDuff.Mode.SRC_IN,
            )
        }
    }

    private fun createContainerBackground(): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, PILL_CORNER_RADIUS_DP, resources.displayMetrics,
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
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
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
                com.android.internal.R.attr.colorAccentPrimary, tv, true
            )
        ) tv.data else 0xFF6750A4.toInt()
    }

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics,
    ).toInt()

    private fun dpToPxF(dp: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics,
    )

    private val resources get() = context.resources
}
