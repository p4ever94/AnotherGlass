package com.damn.anotherglass.shared.device;

import java.io.Serializable;

public class TimeSyncData implements Serializable {
    public long currentTimeMillis;
    public String timeZoneId;

    public TimeSyncData(long currentTimeMillis, String timeZoneId) {
        this.currentTimeMillis = currentTimeMillis;
        this.timeZoneId = timeZoneId;
    }
}
