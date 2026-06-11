package com.damn.anotherglass.shared.call;

import java.io.Serializable;

public class ContactsRequestData implements Serializable {
    public long requestedAtMs;

    public ContactsRequestData() {
    }

    public ContactsRequestData(long requestedAtMs) {
        this.requestedAtMs = requestedAtMs;
    }
}
