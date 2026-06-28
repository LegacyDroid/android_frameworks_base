package com.android.systemui.qs.tiles;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.view.IWindowManager;
import android.view.Surface;
import android.view.WindowManager;

import com.android.internal.BoringdroidManager;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;

import javax.inject.Inject;

public class LegacyDexTile extends QSTileImpl<BooleanState> {

    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_legacy_dex);

    @Inject
    public LegacyDexTile(QSHost host) {
        super(host);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        boolean enabled = !BoringdroidManager.isPCModeEnabled();
        if (enabled) {
            showWarningDialog();
        } else {
            doSetEnabled(false);
        }
    }

    private void showWarningDialog() {
        Dialog dialog = new AlertDialog.Builder(mContext)
                .setTitle(R.string.legacy_dex_warning_title)
                .setMessage(R.string.legacy_dex_warning_message)
                .setPositiveButton(R.string.legacy_dex_warning_accept,
                        (d, which) -> doSetEnabled(true))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        mUiHandler.post(() -> dialog.show());
    }

    private void doSetEnabled(boolean enabled) {
        SystemProperties.set("persist.sys.pcmode.enabled", enabled ? "true" : "false");
        SystemProperties.set("persist.sys.systemuiplugin.enabled", enabled ? "true" : "false");
        IWindowManager wm = IWindowManager.Stub.asInterface(
                ServiceManager.getService("window"));
        try {
            if (enabled) {
                wm.freezeRotation(Surface.ROTATION_90);
            } else {
                wm.thawRotation();
            }
        } catch (RemoteException e) {
            // fallback: set system rotation manually
            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.USER_ROTATION,
                    enabled ? Surface.ROTATION_90 : Surface.ROTATION_0);
        }
        Process.killProcess(Process.myPid());
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_legacy_dex_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.value = BoringdroidManager.isPCModeEnabled();
        state.icon = mIcon;
        state.label = getTileLabel();
        if (state.value) {
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_legacy_dex_on);
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_legacy_dex_off);
            state.state = Tile.STATE_INACTIVE;
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.VIEW_UNKNOWN;
    }

    @Override
    public void handleSetListening(boolean listening) {
    }
}
