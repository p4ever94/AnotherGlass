package com.damn.anotherglass.shared.call;

import java.io.Serializable;

public class CallRequestData implements Serializable {
    public String displayName;
    public String phoneNumber;

    public CallRequestData() {
    }

    public CallRequestData(String displayName, String phoneNumber) {
        this.displayName = displayName;
        this.phoneNumber = phoneNumber;
    }
}
