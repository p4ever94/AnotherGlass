package com.damn.anotherglass.shared.call;

import java.io.Serializable;

public class ContactsRequestData implements Serializable {
    public long requestedAtMs;
    public int offset;
    public int limit;

    public ContactsRequestData() {
    }

    public ContactsRequestData(long requestedAtMs) {
        this(requestedAtMs, 0, 25);
    }

    public ContactsRequestData(long requestedAtMs, int offset, int limit) {
        this.requestedAtMs = requestedAtMs;
        this.offset = offset;
        this.limit = limit;
    }
}
