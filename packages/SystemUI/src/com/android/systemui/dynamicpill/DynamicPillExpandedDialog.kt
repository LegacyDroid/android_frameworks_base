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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
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
        private const val EXPANDED_MARGIN_HORIZONTAL_DP = 16
        private const val EXPANDED_MARGIN_TOP_DP = 48
        private const val CARD_CORNER_RADIUS_DP = 28f
        private const val CARD_INNER_CORNER_RADIUS_DP = 20f
        private const val EXPAND_DURATION_MS = 340L
        private const val DISMISS_DURATION_MS = 260L
        private const val SCRIM_MAX_ALPHA = 0.35f

        // Material 3 Emphasized Decelerate: fast initial burst, ultra-smooth settling
        private val EXPAND_INTERPOLATOR = PathInterpolator(0.2f, 0.0f, 0.0f, 1.0f)
        // Standard Decelerate: crisp and natural collapse
        private val DISMISS_INTERPOLATOR = PathInterpolator(0.4f, 0.0f, 0.2f, 1.0f)
    }

    private var containerView: View? = null
    private var isShowing = false
    private var isDismissing = false
    private var activeAnimator: ValueAnimator? = null
    private var onActionListener: ActionListener? = null
    private var onDismissListener: (() -> Unit)? = null

    private var pillScreenX = 0
    private var pillScreenY = 0
    private var pillWidth = 0
    private var pillHeight = 0
    private var pillHighlightColor = 0
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

    fun show(state: PillState, pillRect: IntArray? = null, pillColor: Int = surfaceColor()) {
        if (isDismissing) {
            activeAnimator?.cancel()
            activeAnimator = null
            isDismissing = false
        }
        if (isShowing) {
            updateContent(state)
            return
        }

        if (pillRect != null && pillRect.size >= 4 && pillRect[2] > 0 && pillRect[3] > 0) {
            pillScreenX = pillRect[0]
            pillScreenY = pillRect[1]
            pillWidth = pillRect[2]
            pillHeight = pillRect[3]
        } else {
            val dm = context.resources.displayMetrics
            val fallbackW = dpToPx(140)
            val fallbackH = dpToPx(36)
            pillScreenX = (dm.widthPixels - fallbackW) / 2
            pillScreenY = dpToPx(8)
            pillWidth = fallbackW
            pillHeight = fallbackH
        }
        pillHighlightColor = pillColor

        val view = buildExpandedView(state)
        containerView = view

        // Hide until first layout pass sets morph initial state
        view.alpha = 0f
        val params = createLayoutParams()
        windowManager.addView(view, params)
        isShowing = true

        morphExpand(view)
    }

    fun dismiss() {
        if (!isShowing || isDismissing) return
        val rootView = containerView ?: return
        val morphContainer = rootView.findViewWithTag<MorphCardContainer>("morph_container")
        val scrimView = rootView.findViewWithTag<View>("scrim_view")

        isDismissing = true
        isShowing = false

        activeAnimator?.cancel()
        activeAnimator = null

        val dismissMorphStarted = onMorphStarted
        val dismissMorphFinished = onMorphFinished

        if (morphContainer != null && !morphContainer.startBounds.isEmpty && !morphContainer.endBounds.isEmpty) {
            dismissMorphStarted?.invoke()

            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = DISMISS_DURATION_MS
                interpolator = DISMISS_INTERPOLATOR
                addUpdateListener { anim ->
                    val p = anim.animatedValue as Float // 0 -> 1
                    val t = 1f - p // 1 -> 0
                    // Content fades out immediately in the first 25% of dismiss
                    val contentAlpha = if (p >= 0.25f) {
                        0f
                    } else {
                        (1f - (p / 0.25f)).coerceIn(0f, 1f)
                    }
                    morphContainer.setMorphState(morphFraction = t, contentAlpha = contentAlpha)
                    scrimView?.alpha = t * SCRIM_MAX_ALPHA
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        dismissMorphFinished?.invoke()
                        removeViewFromWindow(rootView)
                        onDismissListener?.invoke()
                        isDismissing = false
                        if (activeAnimator == this@apply) {
                            activeAnimator = null
                        }
                    }
                })
            }
            activeAnimator = animator
            animator.start()
        } else {
            dismissMorphFinished?.invoke()
            removeViewFromWindow(rootView)
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
        val root = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }

        // Dim backdrop scrim
        val scrim = View(context).apply {
            tag = "scrim_view"
            setBackgroundColor(0xFF000000.toInt())
            alpha = 0f
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    if (onDismissListener != null) {
                        onDismissListener?.invoke()
                    } else {
                        dismiss()
                    }
                }
                true
            }
        }
        root.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        // Morph container
        val morphContainer = MorphCardContainer(context).apply {
            tag = "morph_container"
        }

        val cardsContainer = LinearLayout(context).apply {
            id = R.id.expanded_cards_container
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            val pad = dpToPx(8)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
        }

        populateCards(state, cardsContainer)
        morphContainer.contentContainer = cardsContainer
        morphContainer.addView(cardsContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        val containerParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dpToPx(EXPANDED_MARGIN_TOP_DP)
            marginStart = dpToPx(EXPANDED_MARGIN_HORIZONTAL_DP)
            marginEnd = dpToPx(EXPANDED_MARGIN_HORIZONTAL_DP)
        }
        root.addView(morphContainer, containerParams)

        return root
    }

    private fun morphExpand(rootView: View) {
        val morphContainer = rootView.findViewWithTag<MorphCardContainer>("morph_container") ?: return
        val scrimView = rootView.findViewWithTag<View>("scrim_view")

        morphContainer.post {
            if (!isShowing || isDismissing) return@post
            val cardW = morphContainer.width.toFloat()
            val cardH = morphContainer.height.toFloat()
            if (cardW <= 0f || cardH <= 0f) return@post

            val containerLoc = IntArray(2)
            morphContainer.getLocationOnScreen(containerLoc)

            val endLeft = containerLoc[0].toFloat()
            val endTop = containerLoc[1].toFloat()
            val endRight = endLeft + cardW
            val endBottom = endTop + cardH

            val startLeft = pillScreenX.toFloat()
            val startTop = pillScreenY.toFloat()
            val startRight = (pillScreenX + pillWidth).toFloat()
            val startBottom = (pillScreenY + pillHeight).toFloat()

            morphContainer.startBounds.set(startLeft, startTop, startRight, startBottom)
            morphContainer.endBounds.set(endLeft, endTop, endRight, endBottom)

            // Dynamic capsule radius: height / 2 guarantees a true capsule pill shape at t=0
            morphContainer.startRadius = (pillHeight / 2f).coerceAtLeast(dpToPxF(14f))
            morphContainer.endRadius = dpToPxF(CARD_CORNER_RADIUS_DP)

            morphContainer.startColor = pillHighlightColor
            morphContainer.endColor = cardColor()

            // Initialize state at t=0: Content is fully hidden (alpha=0), container is exact pill shape/position
            morphContainer.setMorphState(morphFraction = 0f, contentAlpha = 0f)
            scrimView?.alpha = 0f

            // Reveal root now that initial morph geometry is set up (no flicker)
            rootView.alpha = 1f

            val expandMorphStarted = onMorphStarted
            val expandMorphFinished = onMorphFinished
            expandMorphStarted?.invoke()

            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = EXPAND_DURATION_MS
                interpolator = EXPAND_INTERPOLATOR
                addUpdateListener { anim ->
                    val t = anim.animatedValue as Float
                    // Text and controls appear near the end of the morph animation (t >= 0.65)
                    val contentAlpha = if (t < 0.65f) {
                        0f
                    } else {
                        ((t - 0.65f) / 0.35f).coerceIn(0f, 1f)
                    }
                    morphContainer.setMorphState(morphFraction = t, contentAlpha = contentAlpha)
                    scrimView?.alpha = t * SCRIM_MAX_ALPHA
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        morphContainer.setMorphState(morphFraction = 1f, contentAlpha = 1f)
                        scrimView?.alpha = SCRIM_MAX_ALPHA
                        expandMorphFinished?.invoke()
                        if (activeAnimator == this@apply) {
                            activeAnimator = null
                        }
                    }
                })
            }
            activeAnimator = animator
            animator.start()
        }
    }

    private fun removeViewFromWindow(view: View) {
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) { }
    }

    private fun populateCards(state: PillState, container: LinearLayout) {
        val count = state.activeSessions.size
        for (session in state.activeSessions) {
            val card = when (session) {
                is PillSession.Media -> createMediaCard(session, count > 1)
                is PillSession.Clock -> createClockCard(session, count > 1)
                is PillSession.Recording -> createRecordingCard(session, count > 1)
            }
            container.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (container.childCount > 1) topMargin = dpToPx(6)
            })
        }
    }

    private fun createMediaCard(media: PillSession.Media, hasMultiple: Boolean): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (hasMultiple) {
                background = createInnerCardBackground()
            }
            setPadding(dpToPx(16), dpToPx(14), dpToPx(12), dpToPx(14))
        }

        val info = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        info.addView(TextView(context).apply {
            text = media.title.ifEmpty { "Unknown" }
            setTextColor(textColor(true))
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })

        info.addView(TextView(context).apply {
            text = media.artist.ifEmpty { "Media" }
            setTextColor(textColor(false))
            textSize = 13f
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

    private fun createClockCard(clock: PillSession.Clock, hasMultiple: Boolean): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (hasMultiple) {
                background = createInnerCardBackground()
            }
            setPadding(dpToPx(16), dpToPx(14), dpToPx(12), dpToPx(14))
        }

        val info = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        info.addView(TextView(context).apply {
            text = if (clock.isStopwatch) "Stopwatch" else "Timer"
            setTextColor(textColor(false))
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })

        info.addView(TextView(context).apply {
            val displayMillis = if (clock.isStopwatch) {
                clock.elapsedMillis
            } else {
                (clock.totalCountdownMillis - clock.elapsedMillis).coerceAtLeast(0L)
            }
            text = formatTime(displayMillis)
            setTextColor(textColor(true))
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
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

    private fun createRecordingCard(recording: PillSession.Recording, hasMultiple: Boolean): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (hasMultiple) {
                background = createInnerCardBackground()
            }
            setPadding(dpToPx(16), dpToPx(14), dpToPx(12), dpToPx(14))
        }

        val info = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        info.addView(TextView(context).apply {
            text = if (recording.isScreenRecord) "Screen Recording" else "Voice Recording"
            setTextColor(textColor(false))
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })

        info.addView(TextView(context).apply {
            text = formatTime(recording.elapsedMillis)
            setTextColor(textColor(true))
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
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
            background = createRipple(22f)
            val size = dpToPx(44)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dpToPx(4)
                marginEnd = dpToPx(4)
            }
            setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
            setOnClickListener { onClick() }
            colorFilter = android.graphics.PorterDuffColorFilter(
                iconTint(),
                android.graphics.PorterDuff.Mode.SRC_IN,
            )
        }
    }

    private fun createInnerCardBackground(): GradientDrawable {
        return GradientDrawable().apply {
            val radiusPx = dpToPxF(CARD_INNER_CORNER_RADIUS_DP)
            cornerRadius = radiusPx
            setColor(innerCardColor())
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

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics,
    ).toInt()

    private fun dpToPxF(dp: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics,
    )

    // --- Custom Morphing View Container ---

    private class MorphCardContainer(context: Context) : FrameLayout(context) {

        private val bgDrawable = GradientDrawable()
        private val clipPath = Path()
        private val currentRect = RectF()

        val startBounds = RectF()
        val endBounds = RectF()
        var startRadius = 0f
        var endRadius = 0f
        var startColor = 0
        var endColor = 0

        var contentContainer: View? = null

        init {
            setWillNotDraw(false)
            clipChildren = false
            clipToPadding = false
        }

        fun setMorphState(morphFraction: Float, contentAlpha: Float) {
            if (startBounds.isEmpty || endBounds.isEmpty) {
                contentContainer?.alpha = contentAlpha
                return
            }

            // Smooth linear interpolation of coordinates, corner radius, and color
            val left = lerp(startBounds.left, endBounds.left, morphFraction)
            val top = lerp(startBounds.top, endBounds.top, morphFraction)
            val right = lerp(startBounds.right, endBounds.right, morphFraction)
            val bottom = lerp(startBounds.bottom, endBounds.bottom, morphFraction)
            val radius = lerp(startRadius, endRadius, morphFraction)
            val color = blendArgb(startColor, endColor, morphFraction)

            currentRect.set(left, top, right, bottom)

            // Translate container from its laid-out position (endBounds) to current position
            translationX = left - endBounds.left
            translationY = top - endBounds.top

            val w = (right - left).coerceAtLeast(0f)
            val h = (bottom - top).coerceAtLeast(0f)

            bgDrawable.setColor(color)
            bgDrawable.cornerRadius = radius
            bgDrawable.setBounds(0, 0, w.toInt(), h.toInt())

            clipPath.reset()
            clipPath.addRoundRect(0f, 0f, w, h, radius, radius, Path.Direction.CW)

            // Text and controls fade in smoothly with subtle upward settle
            contentContainer?.let { content ->
                content.alpha = contentAlpha
                content.translationY = (1f - contentAlpha) * dpToPx(8f)
            }

            invalidate()
        }

        override fun draw(canvas: Canvas) {
            if (currentRect.isEmpty) {
                super.draw(canvas)
                return
            }

            val saveCount = canvas.save()
            canvas.clipPath(clipPath)
            bgDrawable.draw(canvas)
            super.draw(canvas)
            canvas.restoreToCount(saveCount)
        }

        private fun lerp(start: Float, stop: Float, amount: Float): Float =
            start + (stop - start) * amount

        private fun blendArgb(color1: Int, color2: Int, ratio: Float): Int {
            val inverseRatio = 1f - ratio
            val a = (Color.alpha(color1) * inverseRatio + Color.alpha(color2) * ratio)
            val r = (Color.red(color1) * inverseRatio + Color.red(color2) * ratio)
            val g = (Color.green(color1) * inverseRatio + Color.green(color2) * ratio)
            val b = (Color.blue(color1) * inverseRatio + Color.blue(color2) * ratio)
            return Color.argb(a.toInt(), r.toInt(), g.toInt(), b.toInt())
        }

        private fun dpToPx(dp: Float): Float = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics
        )
    }

    // --- Material You dynamic colors ---

    private fun resolveColor(resId: Int): Int {
        return try {
            context.resources.getColor(resId, context.theme)
        } catch (_: Exception) {
            when (resId) {
                android.R.color.system_neutral2_900 -> 0xFF1C1B1F.toInt()
                android.R.color.system_neutral2_10 -> 0xFFF4EFF4.toInt()
                android.R.color.system_neutral1_900 -> 0xFF1C1B1F.toInt()
                android.R.color.system_neutral1_10 -> 0xFFE6E1E5.toInt()
                android.R.color.system_neutral1_100 -> 0xFF938F99.toInt()
                android.R.color.system_accent1_200 -> 0xFFD0BCFF.toInt()
                android.R.color.system_accent1_100 -> 0xFFE8DEFF.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
        }
    }

    private fun textColor(primary: Boolean): Int =
        resolveColor(if (primary) android.R.color.system_neutral1_10 else android.R.color.system_neutral1_100)

    private fun surfaceColor(): Int = resolveColor(android.R.color.system_neutral2_900)

    private fun cardColor(): Int {
        val base = resolveColor(android.R.color.system_neutral1_900)
        return blendAlpha(base, 0x1AFFFFFF)
    }

    private fun innerCardColor(): Int {
        val base = resolveColor(android.R.color.system_neutral1_900)
        return blendAlpha(base, 0x14FFFFFF)
    }

    private fun iconTint(): Int = resolveColor(android.R.color.system_accent1_200)

    private fun rippleColor(): Int = blendAlpha(resolveColor(android.R.color.system_accent1_100), 0x33000000)

    private fun blendAlpha(bg: Int, fg: Int): Int {
        val fgA = (fg ushr 24) and 0xFF
        val fgR = (fg shr 16) and 0xFF
        val fgG = (fg shr 8) and 0xFF
        val fgB = fg and 0xFF
        val bgR = (bg shr 16) and 0xFF
        val bgG = (bg shr 8) and 0xFF
        val bgB = bg and 0xFF
        val a = fgA / 255f
        val r = (fgR * a + bgR * (1 - a)).toInt()
        val g = (fgG * a + bgG * (1 - a)).toInt()
        val b = (fgB * a + bgB * (1 - a)).toInt()
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    private fun createRipple(cornerRadiusDp: Float): RippleDrawable {
        val radiusPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, cornerRadiusDp, resources.displayMetrics,
        )
        val outerShape = RoundRectShape(floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, radiusPx, radiusPx, radiusPx, radiusPx), null, null)
        val mask = ShapeDrawable(outerShape).apply {
            paint.color = 0xFFFFFFFF.toInt()
        }
        return RippleDrawable(
            android.content.res.ColorStateList.valueOf(rippleColor()),
            null,
            mask,
        )
    }

    private val resources get() = context.resources
}
