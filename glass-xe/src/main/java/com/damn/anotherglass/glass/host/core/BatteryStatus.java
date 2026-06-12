package com.damn.anotherglass.glass.host.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import com.damn.anotherglass.shared.device.BatteryStatusData;

public class BatteryStatus {

    public interface Listener {
        void onBatteryStatusChanged(BatteryStatusData data);
    }

    private final Context mContext;
    private final Listener mListener;
    private BroadcastReceiver mReceiver;

    public BatteryStatus(Context context, Listener listener) {
        mContext = context;
        mListener = listener;
    }

    public void start() {
        if (mReceiver != null) {
            return; // already started
        }
        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                BatteryStatusData data = fromIntent(intent);
                if (mListener != null && data != null) {
                    mListener.onBatteryStatusChanged(data);
                }
            }
        };

        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        mContext.registerReceiver(mReceiver, filter);

        Intent stickyIntent = mContext.registerReceiver(null, filter);
        if(stickyIntent != null) {
            mReceiver.onReceive(mContext, stickyIntent);
        }
    }

    public void stop() {
        if (mReceiver != null) {
            mContext.unregisterReceiver(mReceiver);
            mReceiver = null;
        }
    }

    public static BatteryStatusData readCurrent(Context context) {
        Intent stickyIntent = context.registerReceiver(
                null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return fromIntent(stickyIntent);
    }

    private static BatteryStatusData fromIntent(Intent intent) {
        if (intent == null) return null;

        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int percentage = -1;
        if(level >= 0 && scale > 0) {
            percentage = (int) ((level * 100.0) / scale);
        }
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL;

        return new BatteryStatusData(percentage, isCharging);
    }
}
