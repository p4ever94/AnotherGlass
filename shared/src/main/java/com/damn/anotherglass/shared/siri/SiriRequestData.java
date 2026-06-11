package com.damn.anotherglass.shared.siri;

import java.io.Serializable;

public class SiriRequestData implements Serializable {
    public final long requestedAtMs;

    public SiriRequestData(long requestedAtMs) {
        this.requestedAtMs = requestedAtMs;
    }
}
