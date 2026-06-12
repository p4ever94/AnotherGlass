package com.damn.glass.shared.device;

import android.app.AlarmManager;
import android.content.Context;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.damn.anotherglass.shared.device.TimeSyncData;

public final class DeviceClock {
    private static long sSyncedWallClockOffsetMs;
    private static long sLastSyncPhoneTimeMillis;
    private static String sTimeZoneId;

    private DeviceClock() {
    }

    @NonNull
    public static Result apply(@NonNull Context context, @NonNull TimeSyncData data) {
        sync(data);
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return new Result(true, "AlarmManager unavailable");
        }

        try {
            if (data.timeZoneId != null && data.timeZoneId.length() > 0) {
                alarmManager.setTimeZone(data.timeZoneId);
            }
            alarmManager.setTime(data.currentTimeMillis);
            return new Result(true, null);
        } catch (SecurityException e) {
            return new Result(true, e.getMessage());
        } catch (RuntimeException e) {
            return new Result(true, e.getMessage());
        }
    }

    public static void sync(@NonNull TimeSyncData data) {
        sLastSyncPhoneTimeMillis = data.currentTimeMillis;
        sSyncedWallClockOffsetMs = data.currentTimeMillis - SystemClock.elapsedRealtime();
        sTimeZoneId = data.timeZoneId;
    }

    public static long now() {
        if (sLastSyncPhoneTimeMillis <= 0) {
            return System.currentTimeMillis();
        }
        return SystemClock.elapsedRealtime() + sSyncedWallClockOffsetMs;
    }

    public static long lastSyncPhoneTimeMillis() {
        return sLastSyncPhoneTimeMillis;
    }

    @Nullable
    public static String timeZoneId() {
        return sTimeZoneId;
    }

    public static final class Result {
        public final boolean success;
        @Nullable
        public final String error;

        private Result(boolean success, @Nullable String error) {
            this.success = success;
            this.error = error;
        }
    }
}
