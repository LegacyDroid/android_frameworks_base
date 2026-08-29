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
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.android.systemui.R
import java.util.Locale

/**
 * Compact pill view that replaces the status bar clock when active sessions exist.
 *
 * Displays a Material You-themed capsule showing the most recently triggered session data.
 * Handles tap to expand and morphing animation between clock text and pill state.
 */
class DynamicPillView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr, defStyleRes) {

    companion object {
        private const val CORNER_RADIUS_DP = 100f
        private const val ANIM_DURATION_MS = 250L
    }

    private val pillBackground = GradientDrawable().apply {
        val cornerRadiusPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            CORNER_RADIUS_DP,
            resources.displayMetrics,
        )
        cornerRadius = cornerRadiusPx
        setColor(resolveSurfaceColor())
    }

    private val pillText: TextView
    private val clockText: TextView

    private var currentState: PillState = PillState()
    private var onPillClickListener: OnClickListener? = null
    private var activeAnimator: AnimatorSet? = null

    private val interpolator = DecelerateInterpolator(2f)

    init {
        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.dynamic_pill_content, this, true)
        pillText = findViewById(R.id.dynamic_pill_text)!!
        clockText = findViewById(R.id.dynamic_pill_clock_text)!!

        background = pillBackground
        clipToPadding = false

        setOnClickListener { view ->
            onPillClickListener?.onClick(view)
        }
    }

    fun setPillClickListener(listener: OnClickListener?) {
        onPillClickListener = listener
    }

    fun updateState(state: PillState) {
        val previousHasSessions = currentState.hasActiveSessions
        currentState = state

        if (state.hasActiveSessions && !previousHasSessions) {
            // First time showing pill (clock → pill transition with animation)
            transitionToPill(state)
        } else if (!state.hasActiveSessions && previousHasSessions) {
            // Last session removed (pill → clock transition with animation)
            transitionToClock()
        } else if (state.hasActiveSessions) {
            // Re-appearing after dialog dismiss or content update — just show instantly
            activeAnimator?.cancel()
            activeAnimator = null
            updatePillContent(state)
            clockText.visibility = View.GONE
            pillText.visibility = View.VISIBLE
            pillText.alpha = 1f
            this.alpha = 1f
            scaleX = 1f
            scaleY = 1f
        } else {
            activeAnimator?.cancel()
            activeAnimator = null
            clockText.visibility = View.VISIBLE
            pillText.visibility = View.GONE
            clockText.alpha = 1f
            this.alpha = 1f
            scaleX = 1f
            scaleY = 1f
        }
    }

    fun showClock() {
        activeAnimator?.cancel()
        activeAnimator = null
        clockText.visibility = View.VISIBLE
        pillText.visibility = View.GONE
        clockText.alpha = 1f
        this.alpha = 1f
        scaleX = 1f
        scaleY = 1f
    }

    private fun transitionToPill(state: PillState) {
        activeAnimator?.cancel()
        activeAnimator = null

        updatePillContent(state)

        clockText.visibility = View.VISIBLE
        pillText.visibility = View.VISIBLE
        pillBackground.alpha = 255

        val fadeOut = ObjectAnimator.ofFloat(clockText, View.ALPHA, clockText.alpha, 0f)
        val fadeIn = ObjectAnimator.ofFloat(pillText, View.ALPHA, pillText.alpha, 1f)
        val scaleUp = ObjectAnimator.ofFloat(this, View.SCALE_X, scaleX, 1f)
        val scaleUpY = ObjectAnimator.ofFloat(this, View.SCALE_Y, scaleY, 1f)

        val animator = AnimatorSet().apply {
            playTogether(fadeOut, fadeIn, scaleUp, scaleUpY)
            duration = ANIM_DURATION_MS
            interpolator = this@DynamicPillView.interpolator
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    clockText.visibility = View.GONE
                    pillText.visibility = View.VISIBLE
                    pillText.alpha = 1f
                    scaleX = 1f
                    scaleY = 1f
                    if (activeAnimator == this@apply) {
                        activeAnimator = null
                    }
                }
            })
            start()
        }
        activeAnimator = animator
    }

    private fun transitionToClock() {
        activeAnimator?.cancel()
        activeAnimator = null

        pillText.visibility = View.VISIBLE
        clockText.visibility = View.VISIBLE

        val fadeOut = ObjectAnimator.ofFloat(pillText, View.ALPHA, pillText.alpha, 0f)
        val fadeIn = ObjectAnimator.ofFloat(clockText, View.ALPHA, clockText.alpha, 1f)
        val scaleDown = ObjectAnimator.ofFloat(this, View.SCALE_X, scaleX, 0.8f)
        val scaleDownY = ObjectAnimator.ofFloat(this, View.SCALE_Y, scaleY, 0.8f)

        val animator = AnimatorSet().apply {
            playTogether(fadeOut, fadeIn, scaleDown, scaleDownY)
            duration = ANIM_DURATION_MS
            interpolator = this@DynamicPillView.interpolator
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    pillText.visibility = View.GONE
                    clockText.visibility = View.VISIBLE
                    clockText.alpha = 1f
                    scaleX = 1f
                    scaleY = 1f
                    if (activeAnimator == this@apply) {
                        activeAnimator = null
                    }
                }
            })
            start()
        }
        activeAnimator = animator
    }

    private fun updatePillContent(state: PillState) {
        val session = state.compactSession ?: return
        when (session) {
            is PillSession.Media -> {
                pillText.text = session.title.ifEmpty { session.artist }
            }
            is PillSession.Clock -> {
                pillText.text = formatTime(session.elapsedMillis)
            }
            is PillSession.Recording -> {
                pillText.text = formatTime(session.elapsedMillis)
            }
        }
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun resolveSurfaceColor(): Int {
        val typedValue = TypedValue()
        val theme = context.theme
        return if (theme.resolveAttribute(com.android.internal.R.attr.colorAccentPrimary, typedValue, true)) {
            typedValue.data
        } else {
            0xFF6750A4.toInt()
        }
    }
}
