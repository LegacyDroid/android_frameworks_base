package com.android.systemui.legacydroid;

import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.util.Log;
import android.view.View;

public class LegacyBlur {
    private static final String TAG = "LegacyBlur";
    private static final float BLUR_RADIUS = 25f;
    private static final float SCALE_DOWN = 0.25f;

    private static Bitmap sBlurredWallpaper;
    private static boolean sReceiverRegistered;

    private static final BroadcastReceiver sWallpaperReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            invalidateCache();
        }
    };

    public static void register(Context context) {
        if (sReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED);
        context.registerReceiver(sWallpaperReceiver, filter);
        sReceiverRegistered = true;
    }

    public static void unregister(Context context) {
        if (!sReceiverRegistered) return;
        try {
            context.unregisterReceiver(sWallpaperReceiver);
        } catch (IllegalArgumentException e) {
            // not registered
        }
        sReceiverRegistered = false;
    }

    public static void apply(View target, Context context) {
        if (target == null || context == null) return;
        if (sBlurredWallpaper == null || sBlurredWallpaper.isRecycled()) {
            generateBlur(target, context);
        }
        if (sBlurredWallpaper != null && !sBlurredWallpaper.isRecycled()) {
            target.setBackground(new BitmapDrawable(context.getResources(), sBlurredWallpaper));
        }
    }

    public static void clear(View target) {
        if (target != null) {
            target.setBackground(null);
        }
    }

    public static void invalidateCache() {
        sBlurredWallpaper = null;
    }

    private static void generateBlur(View target, Context context) {
        WallpaperManager wm = WallpaperManager.getInstance(context);
        Drawable wallpaperDrawable = wm.getDrawable();
        if (wallpaperDrawable == null) return;

        int targetW = target.getWidth();
        int targetH = target.getHeight();
        if (targetW <= 0 || targetH <= 0) {
            targetW = context.getResources().getDisplayMetrics().widthPixels;
            targetH = context.getResources().getDisplayMetrics().heightPixels;
        }

        Bitmap wallpaper = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(wallpaper);
        wallpaperDrawable.setBounds(0, 0, targetW, targetH);
        wallpaperDrawable.draw(canvas);

        int smallW = Math.round(targetW * SCALE_DOWN);
        int smallH = Math.round(targetH * SCALE_DOWN);
        Bitmap scaled = Bitmap.createScaledBitmap(wallpaper, smallW, smallH, true);
        wallpaper.recycle();

        Bitmap blurredSmall = blur(context, scaled, BLUR_RADIUS);
        scaled.recycle();

        sBlurredWallpaper = Bitmap.createScaledBitmap(blurredSmall, targetW, targetH, true);
        if (blurredSmall != sBlurredWallpaper) {
            blurredSmall.recycle();
        }
    }

    private static Bitmap blur(Context context, Bitmap input, float radius) {
        RenderScript rs = null;
        try {
            rs = RenderScript.create(context);
            Allocation inputAlloc = Allocation.createFromBitmap(rs, input);
            Allocation outputAlloc = Allocation.createTyped(rs, inputAlloc.getType());
            ScriptIntrinsicBlur script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
            script.setRadius(Math.min(radius, 25f));
            script.setInput(inputAlloc);
            script.forEach(outputAlloc);
            Bitmap output = Bitmap.createBitmap(input.getWidth(), input.getHeight(),
                    input.getConfig());
            outputAlloc.copyTo(output);
            return output;
        } catch (Exception e) {
            Log.e(TAG, "RenderScript blur failed", e);
            return input;
        } finally {
            if (rs != null) rs.destroy();
        }
    }
}
