package com.damn.anotherglass.shared.call;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class ContactListData implements Serializable {
    public List<ContactData> contacts = new ArrayList<>();

    public ContactListData() {
    }

    public ContactListData(List<ContactData> contacts) {
        this.contacts = contacts;
    }
}
