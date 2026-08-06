/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.charging

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.net.Uri
import android.os.SystemProperties
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.Utils
import com.android.systemui.res.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.surfaceeffects.ripple.RippleView
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.time.SystemClock
import java.io.PrintWriter
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private const val MAX_DEBOUNCE_LEVEL = 3
private const val BASE_DEBOUNCE_TIME = 2000
private const val TAG = "WiredChargingRipple"

private const val SETTING_ANIMATION = "legacydroid_charging_animation"
private const val SETTING_IMAGE = "legacydroid_charging_image"
private const val SETTING_IMAGE_DATA = "legacydroid_charging_image_data"
private const val SETTING_TRANSPARENCY = "legacydroid_charging_image_transparency"
private const val SETTING_SIZE = "legacydroid_charging_image_size"

private const val MODE_AOSP = "aosp"
private const val MODE_NONE = "none"
private const val MODE_CUSTOM = "custom"

private const val CUSTOM_IMAGE_FADE_IN_MS = 350
private const val CUSTOM_IMAGE_HOLD_MS = 1300
private const val CUSTOM_IMAGE_FADE_OUT_MS = 350

/***
 * Controls the ripple effect that shows when wired charging begins.
 * The ripple uses the accent color of the current theme.
 */
@SysUISingleton
class WiredChargingRippleController @Inject constructor(
    commandRegistry: CommandRegistry,
    private val batteryController: BatteryController,
    private val configurationController: ConfigurationController,
    featureFlags: FeatureFlags,
    private val context: Context,
    private val windowManager: WindowManager,
    private val systemClock: SystemClock,
    private val uiEventLogger: UiEventLogger
) {
    private var pluggedIn: Boolean = false
    private val rippleEnabled: Boolean = featureFlags.isEnabled(Flags.CHARGING_RIPPLE) &&
            !SystemProperties.getBoolean("persist.debug.suppress-charging-ripple", false)
    private var normalizedPortPosX: Float = context.resources.getFloat(
            R.dimen.physical_charger_port_location_normalized_x)
    private var normalizedPortPosY: Float = context.resources.getFloat(
            R.dimen.physical_charger_port_location_normalized_y)
    private val windowLayoutParams = WindowManager.LayoutParams().apply {
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        format = PixelFormat.TRANSLUCENT
        type = WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG
        fitInsetsTypes = 0 // Ignore insets from all system bars
        title = "Wired Charging Animation"
        flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        setTrustedOverlay()
    }
    private var lastTriggerTime: Long? = null
    private var debounceLevel = 0
        /**
     * Converts a media document URI (content://com.android.providers.media.documents/
     * document/image%3A22) into its MediaStore URI (content://media/external/images/media/22),
     * or null if the URI is not a media document.
     */
    @VisibleForTesting
    fun mediaStoreUri(documentUri: String): Uri? {
        val docId = DocumentsContract.getDocumentId(Uri.parse(documentUri)) ?: return null
        val separator = docId.indexOf(':')
        if (separator <= 0) {
            return null
        }
        val id = docId.substring(separator + 1).toLongOrNull() ?: return null
        val type = docId.substring(0, separator)
        return when (type) {
            "image" -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL, id)
            "video" -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL, id)
            else -> null
        }
    }

    @VisibleForTesting
    var rippleView: RippleView = RippleView(context, attrs = null).also { it.setupShader() }

    init {
        pluggedIn = batteryController.isPluggedIn
        commandRegistry.registerCommand("charging-ripple") { ChargingRippleCommand() }
        updateRippleColor()
    }

    fun registerCallbacks() {
        val batteryStateChangeCallback = object : BatteryController.BatteryStateChangeCallback {
            override fun onBatteryLevelChanged(
                level: Int,
                nowPluggedIn: Boolean,
                charging: Boolean
            ) {
                // Suppresses the ripple when the state change comes from wireless charging or
                // its dock.
                if (batteryController.isPluggedInWireless ||
                        batteryController.isChargingSourceDock) {
                    return
                }

                if (!pluggedIn && nowPluggedIn) {
                    startRippleWithDebounce()
                }
                pluggedIn = nowPluggedIn
            }
        }
        batteryController.addCallback(batteryStateChangeCallback)

        val configurationChangedListener = object : ConfigurationController.ConfigurationListener {
            override fun onUiModeChanged() {
                updateRippleColor()
            }
            override fun onThemeChanged() {
                updateRippleColor()
            }

            override fun onConfigChanged(newConfig: Configuration?) {
                normalizedPortPosX = context.resources.getFloat(
                        R.dimen.physical_charger_port_location_normalized_x)
                normalizedPortPosY = context.resources.getFloat(
                        R.dimen.physical_charger_port_location_normalized_y)
            }
        }
        configurationController.addCallback(configurationChangedListener)
    }

    // Lazily debounce ripple to avoid triggering ripple constantly (e.g. from flaky chargers).
    internal fun startRippleWithDebounce() {
        val now = systemClock.elapsedRealtime()
        // Debounce wait time = 2 ^ debounce level
        if (lastTriggerTime == null ||
                (now - lastTriggerTime!!) > BASE_DEBOUNCE_TIME * (2.0.pow(debounceLevel))) {
            // Not waiting for debounce. Start ripple.
            startRipple()
            debounceLevel = 0
        } else {
            // Still waiting for debounce. Ignore ripple and bump debounce level.
            debounceLevel = min(MAX_DEBOUNCE_LEVEL, debounceLevel + 1)
        }
        lastTriggerTime = now
    }

    fun startRipple() {
        if (rippleView.rippleInProgress() || rippleView.parent != null) {
            // Skip if ripple is still playing, or not playing but already added the parent
            // (which might happen just before the animation starts or right after
            // the animation ends.)
            return
        }
        Log.i(TAG, "startRipple: mode=" + chargingAnimationMode)
        when (chargingAnimationMode) {
            MODE_NONE -> return
            MODE_CUSTOM -> {
                showCustomImage()
                return
            }
        }
        windowLayoutParams.packageName = context.opPackageName
        rippleView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(view: View) {}

            override fun onViewAttachedToWindow(view: View) {
                layoutRipple()
                rippleView.startRipple(Runnable {
                    windowManager.removeView(rippleView)
                })
                rippleView.removeOnAttachStateChangeListener(this)
            }
        })
        windowManager.addView(rippleView, windowLayoutParams)
        uiEventLogger.log(WiredChargingRippleEvent.CHARGING_RIPPLE_PLAYED)
    }

    private val chargingAnimationMode: String
        get() = Settings.Global.getString(context.contentResolver, SETTING_ANIMATION)
                ?: MODE_AOSP

    /** Shows the user's custom image centered on screen for a short while. */
    private fun showCustomImage() {
        var bitmap: Bitmap? = null
        // Primary channel: the media document's MediaStore URI, which SystemUI can open with
        // its READ_EXTERNAL_STORAGE grant (the documents provider itself refuses other grants).
        val uriString = Settings.Global.getString(context.contentResolver, SETTING_IMAGE)
        if (!uriString.isNullOrEmpty()) {
            val mediaUri = mediaStoreUri(uriString)
            if (mediaUri != null) {
                bitmap = try {
                    context.contentResolver.openInputStream(mediaUri)
                            ?.use { BitmapFactory.decodeStream(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "custom image: MediaStore read failed for $mediaUri", e)
                    null
                }
            }
            if (bitmap != null) {
                Log.i(TAG, "custom image: loaded from MediaStore")
            }
        }
        // Fallback channel: a size-bounded JPEG stored base64 in Settings.Global by Settings.
        if (bitmap == null) {
            val data = Settings.Global.getString(context.contentResolver, SETTING_IMAGE_DATA)
            if (data.isNullOrEmpty()) {
                Log.w(TAG, "custom image: no image data available")
                return
            }
            bitmap = try {
                BitmapFactory.decodeByteArray(Base64.decode(data, Base64.DEFAULT), 0, 0)
            } catch (e: Exception) {
                Log.w(TAG, "custom image: base64 decode failed", e)
                null
            }
            if (bitmap != null) {
                Log.i(TAG, "custom image: loaded from base64 fallback")
            }
        }
        if (bitmap == null) {
            Log.w(TAG, "custom image: nothing to show")
            return
        }
        val transparency = Settings.Global.getInt(
                context.contentResolver, SETTING_TRANSPARENCY, 0)
        val sizePercent = Settings.Global.getInt(
                context.contentResolver, SETTING_SIZE, 100)

        val bounds = windowManager.currentWindowMetrics.bounds
        val targetWidth = (bounds.width() * sizePercent / 100f)
                .roundToInt().coerceAtLeast(1)
        val targetHeight = (targetWidth * bitmap.height.toFloat() / bitmap.width)
                .roundToInt().coerceAtLeast(1)

        val imageAlpha = (100 - transparency) / 100f
        val imageView = ImageView(context).apply {
            setImageBitmap(Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true))
            alpha = 0f
        }
        val params = WindowManager.LayoutParams(
                targetWidth,
                targetHeight,
                WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.CENTER
            packageName = context.opPackageName
            setTrustedOverlay()
        }
        windowManager.addView(imageView, params)
        val totalMs = (CUSTOM_IMAGE_FADE_IN_MS + CUSTOM_IMAGE_HOLD_MS
                + CUSTOM_IMAGE_FADE_OUT_MS).toFloat()
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = totalMs.toLong()
            addUpdateListener { animation ->
                val t = animation.animatedValue as Float
                val alpha = when {
                    t < CUSTOM_IMAGE_FADE_IN_MS ->
                        t / CUSTOM_IMAGE_FADE_IN_MS
                    t < CUSTOM_IMAGE_FADE_IN_MS + CUSTOM_IMAGE_HOLD_MS -> 1f
                    else -> 1f - (t - CUSTOM_IMAGE_FADE_IN_MS - CUSTOM_IMAGE_HOLD_MS) /
                            CUSTOM_IMAGE_FADE_OUT_MS
                }
                imageView.alpha = alpha * imageAlpha
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator, isReverse: Boolean) {
                    if (imageView.parent != null) {
                        windowManager.removeView(imageView)
                    }
                }
            })
        }
        animator.start()
    }

    private fun layoutRipple() {
        val bounds = windowManager.currentWindowMetrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        val maxDiameter = Integer.max(width, height) * 2f
        rippleView.setMaxSize(maxDiameter, maxDiameter)
        when (context.display?.rotation) {
            Surface.ROTATION_0 -> {
                rippleView.setCenter(
                        width * normalizedPortPosX, height * normalizedPortPosY)
            }
            Surface.ROTATION_90 -> {
                rippleView.setCenter(
                        width * normalizedPortPosY, height * (1 - normalizedPortPosX))
            }
            Surface.ROTATION_180 -> {
                rippleView.setCenter(
                        width * (1 - normalizedPortPosX), height * (1 - normalizedPortPosY))
            }
            Surface.ROTATION_270 -> {
                rippleView.setCenter(
                        width * (1 - normalizedPortPosY), height * normalizedPortPosX)
            }
        }
    }

    private fun updateRippleColor() {
        rippleView.setColor(Utils.getColorAttr(context, android.R.attr.colorAccent).defaultColor)
    }

    inner class ChargingRippleCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            startRipple()
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar charging-ripple")
        }
    }

    enum class WiredChargingRippleEvent(private val _id: Int) : UiEventLogger.UiEventEnum {
        @UiEvent(doc = "Wired charging ripple effect played")
        CHARGING_RIPPLE_PLAYED(829);

        override fun getId() = _id
    }
}
