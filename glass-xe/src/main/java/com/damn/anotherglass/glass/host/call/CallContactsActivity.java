package com.damn.anotherglass.glass.host.call;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;

import com.damn.anotherglass.glass.host.HostService;
import com.damn.anotherglass.glass.host.R;
import com.damn.anotherglass.shared.call.ContactData;
import com.damn.anotherglass.shared.call.ContactListData;
import com.google.android.glass.media.Sounds;
import com.google.android.glass.widget.CardBuilder;
import com.google.android.glass.widget.CardScrollAdapter;
import com.google.android.glass.widget.CardScrollView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CallContactsActivity extends Activity {
    private static final Object CONTACTS_LOCK = new Object();
    private static List<ContactData> latestContacts = new ArrayList<>();

    private CardScrollView cardScroller;
    private List<ContactData> contacts = Collections.emptyList();

    public static void start(Context context, ContactListData data) {
        synchronized (CONTACTS_LOCK) {
            latestContacts = data != null && data.contacts != null
                    ? new ArrayList<>(data.contacts)
                    : new ArrayList<>();
        }

        Intent intent = new Intent(context, CallContactsActivity.class);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        contacts = snapshotContacts();
        cardScroller = new CardScrollView(this);
        cardScroller.setAdapter(new ContactsAdapter());
        cardScroller.setOnItemClickListener((parent, view, position, id) -> {
            if (contacts.isEmpty()) {
                finish();
                return;
            }

            ContactData contact = contacts.get(position);
            Intent intent = new Intent(this, HostService.class)
                    .setAction(HostService.ACTION_REQUEST_CALL)
                    .putExtra(HostService.EXTRA_DISPLAY_NAME, contact.displayName)
                    .putExtra(HostService.EXTRA_PHONE_NUMBER, contact.phoneNumber);
            startService(intent);

            AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audio != null) {
                audio.playSoundEffect(Sounds.SUCCESS);
            }
            finish();
        });
        setContentView(cardScroller);
    }

    @Override
    protected void onResume() {
        super.onResume();
        cardScroller.activate();
    }

    @Override
    protected void onPause() {
        cardScroller.deactivate();
        super.onPause();
    }

    private static List<ContactData> snapshotContacts() {
        synchronized (CONTACTS_LOCK) {
            return new ArrayList<>(latestContacts);
        }
    }

    private class ContactsAdapter extends CardScrollAdapter {
        @Override
        public int getCount() {
            return contacts.isEmpty() ? 1 : contacts.size();
        }

        @Override
        public Object getItem(int position) {
            return contacts.isEmpty() ? null : contacts.get(position);
        }

        @Override
        public int getPosition(Object item) {
            if (!(item instanceof ContactData)) {
                return AdapterView.INVALID_POSITION;
            }
            return contacts.indexOf(item);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (contacts.isEmpty()) {
                return new CardBuilder(CallContactsActivity.this, CardBuilder.Layout.MENU)
                        .setText(R.string.msg_no_contacts)
                        .getView(convertView, parent);
            }

            ContactData contact = contacts.get(position);
            String title = !TextUtils.isEmpty(contact.displayName) ? contact.displayName : contact.phoneNumber;
            String footnote = contact.phoneNumber;
            if (!TextUtils.isEmpty(contact.label)) {
                footnote = contact.label + "  " + contact.phoneNumber;
            }

            return new CardBuilder(CallContactsActivity.this, CardBuilder.Layout.MENU)
                    .setText(title)
                    .setFootnote(footnote)
                    .getView(convertView, parent);
        }
    }
}
