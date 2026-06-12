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
    private static final int CONTACTS_PAGE_SIZE = 25;
    private static final int LOAD_MORE_THRESHOLD = 4;
    private static List<ContactData> latestContacts = new ArrayList<>();
    private static boolean latestHasMore;
    private static int latestNextOffset;
    private static boolean loadingMore;
    private static CallContactsActivity activeActivity;

    private CardScrollView cardScroller;
    private ContactsAdapter adapter;
    private List<ContactData> contacts = Collections.emptyList();
    private boolean hasMore;
    private int nextOffset;

    public static void start(Context context, ContactListData data) {
        CallContactsActivity activityToUpdate;
        synchronized (CONTACTS_LOCK) {
            int offset = data != null ? data.offset : 0;
            List<ContactData> incoming = data != null && data.contacts != null
                    ? data.contacts
                    : Collections.emptyList();

            if (offset <= 0) {
                latestContacts = new ArrayList<>(incoming);
            } else {
                latestContacts.addAll(incoming);
            }
            latestHasMore = data != null && data.hasMore;
            latestNextOffset = latestContacts.size();
            loadingMore = false;
            activityToUpdate = activeActivity;
        }

        if (activityToUpdate != null) {
            activityToUpdate.refreshContacts();
        } else {
            Intent intent = new Intent(context, CallContactsActivity.class);
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        }
    }

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        refreshContactState();
        cardScroller = new CardScrollView(this);
        adapter = new ContactsAdapter();
        cardScroller.setAdapter(adapter);
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
        synchronized (CONTACTS_LOCK) {
            activeActivity = this;
        }
        cardScroller.activate();
    }

    @Override
    protected void onPause() {
        cardScroller.deactivate();
        synchronized (CONTACTS_LOCK) {
            if (activeActivity == this) {
                activeActivity = null;
            }
        }
        super.onPause();
    }

    private void refreshContacts() {
        runOnUiThread(() -> {
            refreshContactState();
            adapter.notifyDataSetChanged();
        });
    }

    private void refreshContactState() {
        synchronized (CONTACTS_LOCK) {
            contacts = new ArrayList<>(latestContacts);
            hasMore = latestHasMore;
            nextOffset = latestNextOffset;
        }
    }

    private void requestNextContactsIfNeeded(int position) {
        if (!hasMore || position < contacts.size() - LOAD_MORE_THRESHOLD) {
            return;
        }

        synchronized (CONTACTS_LOCK) {
            if (loadingMore) {
                return;
            }
            loadingMore = true;
        }

        Intent intent = new Intent(this, HostService.class)
                .setAction(HostService.ACTION_REQUEST_CONTACTS)
                .putExtra(HostService.EXTRA_CONTACTS_OFFSET, nextOffset)
                .putExtra(HostService.EXTRA_CONTACTS_LIMIT, CONTACTS_PAGE_SIZE);
        startService(intent);
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
            requestNextContactsIfNeeded(position);

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
