package com.damn.anotherglass.glass.host;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.RemoteViews;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.damn.anotherglass.glass.host.bluetooth.BluetoothClient;
import com.damn.anotherglass.glass.host.bluetooth.BluetoothLeClient;
import com.damn.anotherglass.glass.host.call.CallContactsActivity;
import com.damn.anotherglass.glass.host.media.MediaCardController;
import com.damn.anotherglass.shared.call.CallAPI;
import com.damn.anotherglass.shared.call.CallRequestData;
import com.damn.anotherglass.shared.call.ContactListData;
import com.damn.anotherglass.shared.call.ContactsRequestData;
import com.damn.anotherglass.shared.rpc.IRPCClient;
import com.damn.glass.shared.gps.MockGPS;
import com.damn.glass.shared.media.MediaController;
import com.damn.glass.shared.rpc.WiFiClient;
import com.damn.anotherglass.glass.host.notifications.NotificationsCardController;
import com.damn.anotherglass.glass.host.ui.ICardViewProvider;
import com.damn.anotherglass.glass.host.ui.MapCard;
import com.damn.anotherglass.glass.host.wifi.WiFiActivity;
import com.damn.anotherglass.shared.device.BatteryStatusData;
import com.damn.anotherglass.shared.device.DeviceAPI;
import com.damn.anotherglass.shared.device.TimeSyncData;
import com.damn.anotherglass.shared.rpc.RPCMessage;
import com.damn.anotherglass.shared.rpc.RPCMessageListener;
import com.damn.anotherglass.shared.gps.GPSServiceAPI;
import com.damn.anotherglass.shared.gps.Location;
import com.damn.anotherglass.shared.media.MediaAPI;
import com.damn.anotherglass.shared.media.MediaStateData;
import com.damn.anotherglass.shared.notifications.NotificationData;
import com.damn.anotherglass.shared.notifications.NotificationsAPI;
import com.damn.anotherglass.shared.siri.SiriAPI;
import com.damn.anotherglass.shared.siri.SiriRequestData;
import com.damn.anotherglass.shared.wifi.WiFiAPI;
import com.damn.anotherglass.shared.wifi.WiFiConfiguration;
import com.damn.glass.shared.device.DeviceClock;
import com.google.android.glass.media.Sounds;
import com.google.android.glass.timeline.LiveCard;
import com.google.android.glass.timeline.LiveCard.PublishMode;
import com.google.android.glass.widget.CardBuilder;
import com.damn.anotherglass.glass.host.core.BatteryStatus;

/**
 * A {@link Service} that publishes a {@link LiveCard} in the timeline.
 */

public class HostService extends Service {

    private static final String LIVE_CARD_TAG = "HostService";
    private static final String PREFS_NAME = "host_service";
    private static final String PREF_CONNECTION_TYPE = "connection_type";
    private static final int BATTERY_RESEND_DELAY_SHORT_MS = 1_500;
    private static final int BATTERY_RESEND_DELAY_LONG_MS = 5_000;
    private static final int BATTERY_RESEND_INTERVAL_MS = 30_000;

    public static final String EXTRA_CONNECTION_TYPE = "connection_type";
    public static final String EXTRA_IP = "ip";
    public static final String EXTRA_DISPLAY_NAME = "display_name";
    public static final String EXTRA_PHONE_NUMBER = "phone_number";
    public static final String ACTION_REQUEST_SIRI = "com.damn.anotherglass.glass.host.action.REQUEST_SIRI";
    public static final String ACTION_REQUEST_CONTACTS = "com.damn.anotherglass.glass.host.action.REQUEST_CONTACTS";
    public static final String ACTION_REQUEST_CALL = "com.damn.anotherglass.glass.host.action.REQUEST_CALL";
    public static final String EXTRA_CONTACTS_OFFSET = "contacts_offset";
    public static final String EXTRA_CONTACTS_LIMIT = "contacts_limit";

    public static final String CONNECTION_TYPE_BLUETOOTH = "bluetooth";
    public static final String CONNECTION_TYPE_BLUETOOTH_LE = "bluetooth_le";
    public static final String CONNECTION_TYPE_WIFI = "wifi";

    public static final String DEFAULT_WIFI_IP = "192.168.1.180"; // kept for source compatibility, prefer gateway auto-detection

    private LiveCard mLiveCard;

    private MockGPS mGPS;
    private boolean mGpsMockUnavailableNotified;

    private IRPCClient mRPCClient;

    private ICardViewProvider mCardProvider;

    private NotificationsCardController mNotificationsCardController;
    private MediaCardController mMediaCardController;

    private BatteryStatus mBatteryStatus;
    private BatteryStatusData mLastBatteryStatus;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mBatteryResendRunnable = new Runnable() {
        @Override
        public void run() {
            sendLastBatteryStatus();
            mHandler.postDelayed(this, BATTERY_RESEND_INTERVAL_MS);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    @SuppressLint("WrongConstant")
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean requestSiri = intent != null && ACTION_REQUEST_SIRI.equals(intent.getAction());
        boolean requestContacts = intent != null && ACTION_REQUEST_CONTACTS.equals(intent.getAction());
        boolean requestCall = intent != null && ACTION_REQUEST_CALL.equals(intent.getAction());
        String callDisplayName = intent != null ? intent.getStringExtra(EXTRA_DISPLAY_NAME) : null;
        String callPhoneNumber = intent != null ? intent.getStringExtra(EXTRA_PHONE_NUMBER) : null;
        int contactsOffset = intent != null ? intent.getIntExtra(EXTRA_CONTACTS_OFFSET, 0) : 0;
        int contactsLimit = intent != null ? intent.getIntExtra(EXTRA_CONTACTS_LIMIT, 25) : 25;
        if (mLiveCard == null) {
            mLiveCard = new LiveCard(this, LIVE_CARD_TAG);

            RemoteViews remoteView = new CardBuilder(getApplicationContext(), CardBuilder.Layout.MENU)
                    .setText(R.string.title_updating)
                    .getRemoteViews();
            mLiveCard.setViews(remoteView);

            // Display the options menu when the live card is tapped.
            Intent menuIntent = new Intent(this, LiveCardMenuActivity.class);
            mLiveCard.setAction(PendingIntent.getActivity(this, 0, menuIntent, 0));
            mLiveCard.publish(PublishMode.REVEAL);

            mGPS = new MockGPS(this);

            mNotificationsCardController = new NotificationsCardController(this);
            mMediaCardController = new MediaCardController(this);

            mBatteryStatus = new BatteryStatus(this, data -> {
                mLastBatteryStatus = data;
                if(null != mRPCClient) {
                    mRPCClient.send(new RPCMessage(DeviceAPI.SERVICE_NAME, data));
                }
            });
            mBatteryStatus.start();

            AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

            final String connectionType = resolveConnectionType(intent);
            final String wifiIp = intent != null ? intent.getStringExtra(EXTRA_IP) : null;
            mRPCClient = createClient(connectionType, wifiIp);
            MediaController.getInstance().setService(message -> {
                if (mRPCClient != null) {
                    mRPCClient.send(message);
                }
            });
            mRPCClient.start(this, new RPCMessageListener() {

                @Override
                public void onWaiting() {
                    displayStatusCard(getString(R.string.msg_waiting_for_connection));
                }

                @Override
                public void onConnectionStarted(@NonNull String device) {
                    //noinspection ConstantConditions
                    audio.playSoundEffect(Sounds.SUCCESS);
                    // map can take a while or not show at all, so show status card
                    displayStatusCard(getString(R.string.msg_connected_to_s, device));
                    mCardProvider = new MapCard(mLiveCard, HostService.this);
                    mMediaCardController.onServiceConnected();
                    sendLastBatteryStatus();
                    mHandler.postDelayed(HostService.this::sendLastBatteryStatus, BATTERY_RESEND_DELAY_SHORT_MS);
                    mHandler.postDelayed(HostService.this::sendLastBatteryStatus, BATTERY_RESEND_DELAY_LONG_MS);
                    mHandler.removeCallbacks(mBatteryResendRunnable);
                    mHandler.postDelayed(mBatteryResendRunnable, BATTERY_RESEND_INTERVAL_MS);
                }

                @Override
                public void onDataReceived(@NonNull RPCMessage data) {
                    route(data);
                }

                @Override
                public void onConnectionLost(@Nullable String error) {
                    mHandler.removeCallbacks(mBatteryResendRunnable);
                    //noinspection ConstantConditions
                    audio.playSoundEffect(Sounds.ERROR);
                    Toast.makeText(
                            HostService.this,
                            null != error ? error : getString(R.string.msg_disconnected),
                            Toast.LENGTH_LONG).show();
                    stopSelf(); // do not restart for now
                }

                @Override
                public void onShutdown() {
                    // already stopped in onConnectionLost
                }
            });
            if (requestSiri) {
                requestSiri();
            } else if (requestContacts) {
                requestContacts(contactsOffset, contactsLimit);
            } else if (requestCall) {
                requestCall(callDisplayName, callPhoneNumber);
            }
        } else {
            if (requestSiri) {
                requestSiri();
                return START_STICKY;
            }
            if (requestContacts) {
                requestContacts(contactsOffset, contactsLimit);
                return START_STICKY;
            }
            if (requestCall) {
                requestCall(callDisplayName, callPhoneNumber);
                return START_STICKY;
            }
            mLiveCard.navigate();
        }
        return START_STICKY;
    }

    private void requestSiri() {
        if (mRPCClient == null) {
            Toast.makeText(this, R.string.msg_siri_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        mRPCClient.send(new RPCMessage(SiriAPI.ID, new SiriRequestData(System.currentTimeMillis())));
        Toast.makeText(this, R.string.msg_siri_requested, Toast.LENGTH_SHORT).show();
    }

    private void requestContacts() {
        requestContacts(0, 25);
    }

    private void requestContacts(int offset, int limit) {
        if (mRPCClient == null) {
            Toast.makeText(this, R.string.msg_contacts_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        mRPCClient.send(new RPCMessage(CallAPI.ID, new ContactsRequestData(System.currentTimeMillis(), offset, limit)));
        Toast.makeText(this, R.string.msg_contacts_requested, Toast.LENGTH_SHORT).show();
    }

    private void requestCall(@Nullable String displayName, @Nullable String phoneNumber) {
        if (mRPCClient == null || phoneNumber == null || phoneNumber.length() == 0) {
            Toast.makeText(this, R.string.msg_call_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        mRPCClient.send(new RPCMessage(CallAPI.ID, new CallRequestData(displayName, phoneNumber)));
        Toast.makeText(this, R.string.msg_call_requested, Toast.LENGTH_SHORT).show();
    }

    private void sendLastBatteryStatus() {
        BatteryStatusData currentBatteryStatus = BatteryStatus.readCurrent(this);
        if (currentBatteryStatus != null) {
            mLastBatteryStatus = currentBatteryStatus;
        }
        if (mRPCClient != null && mLastBatteryStatus != null) {
            mRPCClient.send(new RPCMessage(DeviceAPI.SERVICE_NAME, mLastBatteryStatus));
        }
    }

    private void route(@NonNull RPCMessage data) {
        // can use instanceof instead of .type, but for future sub-routing strings are more convenient
        if (GPSServiceAPI.ID.equals(data.service)) {
            if (data.type.equals(Location.class.getName()) && ensureGpsMockStarted())
                mGPS.publish((Location) data.payload);
        } else if (NotificationsAPI.ID.equals(data.service)) {
            if (data.type.equals(NotificationData.class.getName())) {
                mNotificationsCardController.onNotificationUpdate((NotificationData) data.payload);
            }
        } else if (MediaAPI.ID.equals(data.service)) {
            if (data.type.equals(MediaStateData.class.getName())) {
                MediaStateData state = (MediaStateData) data.payload;
                MediaController.getInstance().onMediaStateUpdate(state);
                if (mMediaCardController != null) {
                    mMediaCardController.onMediaStateUpdate(state);
                }
            }
        } else if (WiFiAPI.ID.equals(data.service)) {
            if (data.type.equals(WiFiConfiguration.class.getName()))
                WiFiActivity.start(this, (WiFiConfiguration) data.payload);
        } else if (CallAPI.ID.equals(data.service)) {
            if (data.type.equals(ContactListData.class.getName())) {
                CallContactsActivity.start(this, (ContactListData) data.payload);
            }
        } else if (DeviceAPI.SERVICE_NAME.equals(data.service)) {
            if (data.type.equals(TimeSyncData.class.getName())) {
                syncDeviceTime((TimeSyncData) data.payload);
            }
        }
    }

    private void syncDeviceTime(@NonNull TimeSyncData data) {
        DeviceClock.apply(this, data);
    }

    private boolean ensureGpsMockStarted() {
        if (mGPS == null) {
            return false;
        }
        if (mGPS.isInstalled()) {
            return true;
        }

        try {
            mGPS.start();
            return true;
        } catch (SecurityException e) {
            if (!mGpsMockUnavailableNotified) {
                mGpsMockUnavailableNotified = true;
                Toast.makeText(
                        this,
                        "GPS passthrough requires mock location permission",
                        Toast.LENGTH_LONG
                ).show();
            }
            return false;
        }
    }

    private void displayStatusCard(String status) {
        if (mLiveCard == null || !mLiveCard.isPublished())
            return;
        if(null != mCardProvider) {
            mCardProvider.onRemoved();
            mCardProvider = null;
        }
        mLiveCard.setViews(new CardBuilder(getApplicationContext(), CardBuilder.Layout.MENU)
                .setText(status)
                .getRemoteViews());
    }

    @NonNull
    private IRPCClient createClient(@NonNull String connectionType, @Nullable String wifiIp) {
        if (CONNECTION_TYPE_WIFI.equals(connectionType)) {
            return new WiFiClient(wifiIp); // null → WiFiClient auto-detects gateway via ConnectionUtils
        }
        if (CONNECTION_TYPE_BLUETOOTH_LE.equals(connectionType)) {
            return new BluetoothLeClient();
        }
        return new BluetoothClient();
    }

    @NonNull
    private String resolveConnectionType(@Nullable Intent intent) {
        String requestedType = intent != null ? intent.getStringExtra(EXTRA_CONNECTION_TYPE) : null;
        if (requestedType != null && isKnownConnectionType(requestedType)) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_CONNECTION_TYPE, requestedType)
                    .apply();
            return requestedType;
        }

        String persistedType = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_CONNECTION_TYPE, CONNECTION_TYPE_BLUETOOTH);
        return isKnownConnectionType(persistedType) ? persistedType : CONNECTION_TYPE_BLUETOOTH;
    }

    private boolean isKnownConnectionType(@Nullable String type) {
        return CONNECTION_TYPE_BLUETOOTH.equals(type)
                || CONNECTION_TYPE_BLUETOOTH_LE.equals(type)
                || CONNECTION_TYPE_WIFI.equals(type);
    }

    @Override
    public void onDestroy() {
        if(null != mBatteryStatus) {
            mBatteryStatus.stop();
            mBatteryStatus = null;
        }
        if (mRPCClient != null) {
            mRPCClient.stop();
        }
        mHandler.removeCallbacks(mBatteryResendRunnable);
        mHandler.removeCallbacksAndMessages(null);
        MediaController.getInstance().clearService();
        mNotificationsCardController.remove();
        if (mMediaCardController != null) {
            mMediaCardController.remove();
            mMediaCardController = null;
        }
        mGPS.remove();
        if(null != mCardProvider) {
            mCardProvider.onRemoved();
            mCardProvider = null;
        }
        if (mLiveCard != null && mLiveCard.isPublished()) {
            mLiveCard.unpublish();
            mLiveCard = null;
        }
        super.onDestroy();
    }

}
