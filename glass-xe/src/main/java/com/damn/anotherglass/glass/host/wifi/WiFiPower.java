package com.damn.anotherglass.glass.host.wifi;

import android.content.Context;
import android.net.wifi.WifiManager;

import androidx.annotation.NonNull;

public final class WiFiPower {

    private WiFiPower() {
    }

    public static boolean isEnabled(@NonNull Context context) {
        WifiManager wifiManager = getWifiManager(context);
        return wifiManager != null && wifiManager.isWifiEnabled();
    }

    @NonNull
    public static Result toggle(@NonNull Context context) {
        WifiManager wifiManager = getWifiManager(context);
        if (wifiManager == null) {
            return new Result(false, false);
        }

        boolean enable = !wifiManager.isWifiEnabled();
        boolean accepted = wifiManager.setWifiEnabled(enable);
        return new Result(accepted, enable);
    }

    private static WifiManager getWifiManager(@NonNull Context context) {
        return (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    public static final class Result {
        public final boolean success;
        public final boolean enabled;

        private Result(boolean success, boolean enabled) {
            this.success = success;
            this.enabled = enabled;
        }
    }
}
