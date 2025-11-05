package com.example.accidentdetection;

import android.view.View;

public class ContactItem {
    public String name;
    public String phone;
    public int type; // Spinner position
    public View view; // Reference to the inflated view in the list

    public ContactItem(String name, String phone, int type) {
        this.name = name;
        this.phone = phone;
        this.type = type;
    }
}
