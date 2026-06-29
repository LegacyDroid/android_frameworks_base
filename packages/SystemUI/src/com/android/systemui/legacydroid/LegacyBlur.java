package com.android.systemui.legacydroid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.hardware.display.DisplayManager;
import android.os.SystemProperties;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.View;

public class LegacyBlur {
    private static final String TAG = "LegacyBlur";
    private static final String PROP_ENABLED = "persist.sys.legacyblur.enabled";
    private static final String PROP_RADIUS = "persist.sys.legacyblur.radius";
    private static final String PROP_BACKEND = "persist.sys.legacyblur.backend";
    private static final String PROP_SCALE = "persist.sys.legacyblur.scale";

    private static float getScale() {
        int d = SystemProperties.getInt(PROP_SCALE, 8);
        return 1.0f / Math.max(d, 1);
    }

    private static Bitmap sScreenshot;
    private static Bitmap sScaled;
    private static RenderScript sRS;
    private static ScriptIntrinsicBlur sBlurScript;
    private static Allocation sInputAlloc;
    private static Allocation sOutputAlloc;
    private static Bitmap sBlurredSmall;

    private static Bitmap sOutputBmp;
    private static Canvas sOutputCanvas;
    private static BitmapDrawable sOutputDrawable;
    private static boolean sBlurActive;

    public static void onPanelExpansionChanged(View target, Context ctx,
                                                float expansion, boolean tracking) {
        if (target == null || ctx == null) return;

        if (!SystemProperties.getBoolean(PROP_ENABLED, true)) {
            sBlurActive = false;
            return;
        }

        if (expansion < 0.01f && !tracking) {
            clear(target);
            release();
            return;
        }

        if (tracking && !hasScreenshot()) {
            capture(ctx);
        }

        if (sScaled == null || sScaled.isRecycled()) return;

        float maxRadius = Math.min(SystemProperties.getInt(PROP_RADIUS, 25), 25);
        float radius = Math.max(1f, expansion * maxRadius);

        try {
            if (sRS == null) {
                sRS = RenderScript.create(ctx);
                sBlurScript = ScriptIntrinsicBlur.create(sRS, Element.U8_4(sRS));
                sInputAlloc = Allocation.createFromBitmap(sRS, sScaled);
                sOutputAlloc = Allocation.createTyped(sRS, sInputAlloc.getType());
                sBlurredSmall = Bitmap.createBitmap(
                        sScaled.getWidth(), sScaled.getHeight(), sScaled.getConfig());
            }

            sInputAlloc.copyFrom(sScaled);
            sBlurScript.setRadius(radius);
            sBlurScript.setInput(sInputAlloc);
            sBlurScript.forEach(sOutputAlloc);
            sOutputAlloc.copyTo(sBlurredSmall);

            int tw = target.getWidth();
            int th = target.getHeight();
            if (tw <= 0) tw = ctx.getResources().getDisplayMetrics().widthPixels;
            if (th <= 0) th = ctx.getResources().getDisplayMetrics().heightPixels;

            if (sOutputBmp == null || sOutputBmp.getWidth() != tw
                    || sOutputBmp.getHeight() != th) {
                sOutputBmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                sOutputCanvas = new Canvas(sOutputBmp);
                sOutputDrawable = new BitmapDrawable(ctx.getResources(), sOutputBmp);
            }

            sOutputCanvas.drawBitmap(sBlurredSmall, null,
                    new Rect(0, 0, tw, th), null);
            target.setBackground(sOutputDrawable);
        } catch (Exception e) {
            Log.e(TAG, "Blur frame failed", e);
        }
    }

    private static boolean hasScreenshot() {
        return sScreenshot != null && !sScreenshot.isRecycled();
    }

    private static void capture(Context ctx) {
        try {
            int rot = ctx.getSystemService(DisplayManager.class)
                    .getDisplay(Display.DEFAULT_DISPLAY).getRotation();
            int w = ctx.getResources().getDisplayMetrics().widthPixels;
            int h = ctx.getResources().getDisplayMetrics().heightPixels;
            Bitmap hwBmp = SurfaceControl.screenshot(new Rect(), w, h, rot);
            if (hwBmp == null) return;

            // Hardware-backed bitmap must be copied to software bitmap
            sScreenshot = hwBmp.copy(Bitmap.Config.ARGB_8888, false);
            hwBmp.recycle();
            if (sScreenshot == null) return;
            sBlurActive = true;

            float scale = getScale();
            int sw = Math.round(sScreenshot.getWidth() * scale);
            int sh = Math.round(sScreenshot.getHeight() * scale);
            sScaled = Bitmap.createScaledBitmap(sScreenshot, sw, sh, true);
        } catch (Exception e) {
            Log.e(TAG, "Screenshot capture failed", e);
        }
    }

    public static boolean isActive() {
        return sBlurActive;
    }

    public static void clear(View target) {
        if (target != null) target.setBackground(null);
    }

    private static void release() {
        if (sBlurScript != null) { sBlurScript.destroy(); sBlurScript = null; }
        if (sInputAlloc != null) { sInputAlloc.destroy(); sInputAlloc = null; }
        if (sOutputAlloc != null) { sOutputAlloc.destroy(); sOutputAlloc = null; }
        if (sRS != null) { sRS.destroy(); sRS = null; }
        if (sBlurredSmall != null) { sBlurredSmall.recycle(); sBlurredSmall = null; }
        if (sScaled != null) { sScaled.recycle(); sScaled = null; }
        if (sScreenshot != null) { sScreenshot.recycle(); sScreenshot = null; }
        sBlurActive = false;
        sOutputBmp = null;
        sOutputCanvas = null;
        sOutputDrawable = null;
    }
}
