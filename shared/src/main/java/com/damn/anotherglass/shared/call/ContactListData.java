package com.damn.anotherglass.shared.call;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class ContactListData implements Serializable {
    public List<ContactData> contacts = new ArrayList<>();
    public int offset;
    public boolean hasMore;

    public ContactListData() {
    }

    public ContactListData(List<ContactData> contacts) {
        this(contacts, 0, false);
    }

    public ContactListData(List<ContactData> contacts, int offset, boolean hasMore) {
        this.contacts = contacts;
        this.offset = offset;
        this.hasMore = hasMore;
    }
}
