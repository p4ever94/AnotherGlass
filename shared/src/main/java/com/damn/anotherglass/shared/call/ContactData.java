package com.damn.anotherglass.shared.call;

import java.io.Serializable;

public class ContactData implements Serializable {
    public String id;
    public String displayName;
    public String phoneNumber;
    public String label;

    public ContactData() {
    }

    public ContactData(String id, String displayName, String phoneNumber, String label) {
        this.id = id;
        this.displayName = displayName;
        this.phoneNumber = phoneNumber;
        this.label = label;
    }
}
