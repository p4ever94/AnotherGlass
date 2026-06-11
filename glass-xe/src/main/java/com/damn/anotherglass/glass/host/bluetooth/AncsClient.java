package com.damn.anotherglass.glass.host.bluetooth;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.damn.anotherglass.shared.notifications.NotificationData;
import com.damn.anotherglass.shared.notifications.NotificationsAPI;
import com.damn.anotherglass.shared.rpc.RPCHandler;
import com.damn.anotherglass.shared.rpc.RPCMessage;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.TimeZone;
import java.util.UUID;

class AncsClient {
    private static final String TAG = "AncsClient";

    static final UUID SERVICE_UUID = UUID.fromString("7905f431-b5ce-4e99-a40f-4b1e122d00d0");
    private static final UUID NOTIFICATION_SOURCE_UUID = UUID.fromString("9fbf120d-6301-42d9-8c58-25e699a21dbd");
    private static final UUID CONTROL_POINT_UUID = UUID.fromString("69d1d8f3-45e1-49a8-9821-9bbdfdaad9d9");
    private static final UUID DATA_SOURCE_UUID = UUID.fromString("22eac6e9-24d6-4bb5-be44-b36ace7c7bfb");
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int COMMAND_GET_NOTIFICATION_ATTRIBUTES = 0;
    private static final int EVENT_ADDED = 0;
    private static final int EVENT_MODIFIED = 1;
    private static final int EVENT_REMOVED = 2;
    private static final long STARTUP_BACKLOG_SUPPRESSION_MS = 2_500L;
    private static final long PARTIAL_ATTRIBUTES_FLUSH_MS = 700L;

    private static final int ATTR_APP_IDENTIFIER = 0;
    private static final int ATTR_TITLE = 1;
    private static final int ATTR_SUBTITLE = 2;
    private static final int ATTR_MESSAGE = 3;
    private static final int ATTR_DATE = 5;
    private static final int[] REQUESTED_ATTRIBUTES = {
            ATTR_APP_IDENTIFIER,
            ATTR_TITLE,
            ATTR_SUBTITLE,
            ATTR_MESSAGE,
            ATTR_DATE
    };

    private final RPCHandler handler;
    private final ArrayDeque<Long> pendingAttributeRequests = new ArrayDeque<>();
    private final ByteArrayOutputStream dataSourceBuffer = new ByteArrayOutputStream();
    private final Map<Long, String> appIdByNotificationUid = new HashMap<>();
    private final AuthorizationHandler authorizationHandler;
    private final Handler handlerThread = new Handler(Looper.getMainLooper());

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic notificationSource;
    private BluetoothGattCharacteristic controlPoint;
    private BluetoothGattCharacteristic dataSource;
    private Long activeAttributeRequestUid;
    private long notificationSourceReadyAtMs;
    private boolean waitingForAttributeResponse;
    private boolean subscribingDataSource;
    private boolean descriptorWriteInFlight;
    private boolean controlPointWriteInFlight;
    private int authorizationRetryCount;
    private final Runnable flushPartialAttributes = this::flushPartialAttributeResponse;

    AncsClient(RPCHandler handler, AuthorizationHandler authorizationHandler) {
        this.handler = handler;
        this.authorizationHandler = authorizationHandler;
    }

    boolean start(BluetoothGatt gatt) {
        BluetoothGattService service = gatt.getService(SERVICE_UUID);
        if (service == null) {
            Log.i(TAG, "ANCS service not available");
            return false;
        }

        this.gatt = gatt;
        notificationSource = service.getCharacteristic(NOTIFICATION_SOURCE_UUID);
        controlPoint = service.getCharacteristic(CONTROL_POINT_UUID);
        dataSource = service.getCharacteristic(DATA_SOURCE_UUID);
        if (notificationSource == null || controlPoint == null || dataSource == null) {
            Log.w(TAG, "ANCS service is missing required characteristics");
            return false;
        }

        subscribingDataSource = true;
        return subscribe(dataSource);
    }

    boolean handlesCharacteristic(BluetoothGattCharacteristic characteristic) {
        UUID uuid = characteristic.getUuid();
        return NOTIFICATION_SOURCE_UUID.equals(uuid)
                || DATA_SOURCE_UUID.equals(uuid)
                || CONTROL_POINT_UUID.equals(uuid);
    }

    boolean handlesDescriptor(BluetoothGattDescriptor descriptor) {
        BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
        return characteristic != null && handlesCharacteristic(characteristic);
    }

    boolean hasPendingGattOperation() {
        return descriptorWriteInFlight || controlPointWriteInFlight;
    }

    boolean isReady() {
        return notificationSourceReadyAtMs > 0;
    }

    void onDescriptorWrite(BluetoothGattDescriptor descriptor, int status) {
        descriptorWriteInFlight = false;
        BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
        if (characteristic == null) {
            return;
        }

        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "ANCS subscription failed for " + characteristic.getUuid() + ": " + status);
            return;
        }

        if (DATA_SOURCE_UUID.equals(characteristic.getUuid()) && subscribingDataSource) {
            subscribingDataSource = false;
            subscribe(notificationSource);
        } else if (NOTIFICATION_SOURCE_UUID.equals(characteristic.getUuid())) {
            notificationSourceReadyAtMs = System.currentTimeMillis();
            Log.i(TAG, "ANCS notifications enabled");
        }
    }

    void onCharacteristicChanged(BluetoothGattCharacteristic characteristic, byte[] value) {
        UUID uuid = characteristic.getUuid();
        if (NOTIFICATION_SOURCE_UUID.equals(uuid)) {
            onNotificationSource(value);
        } else if (DATA_SOURCE_UUID.equals(uuid)) {
            onDataSource(value);
        }
    }

    void onCharacteristicWrite(BluetoothGattCharacteristic characteristic, int status) {
        if (!CONTROL_POINT_UUID.equals(characteristic.getUuid())) {
            return;
        }

        controlPointWriteInFlight = false;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "ANCS attribute request failed: " + status);
            Long failedUid = activeAttributeRequestUid;
            activeAttributeRequestUid = null;
            waitingForAttributeResponse = false;

            if (failedUid != null && authorizationRetryCount < 3) {
                pendingAttributeRequests.addFirst(failedUid);
                authorizationRetryCount++;
                if (authorizationHandler != null && authorizationHandler.onAncsAuthorizationRequired(status)) {
                    return;
                }
            }
            requestNextNotificationAttributes();
        } else {
            handlerThread.removeCallbacks(flushPartialAttributes);
            handlerThread.postDelayed(flushPartialAttributes, PARTIAL_ATTRIBUTES_FLUSH_MS);
        }
    }

    void retryPendingRequests() {
        waitingForAttributeResponse = false;
        requestNextNotificationAttributes();
    }

    @SuppressLint("MissingPermission")
    private boolean subscribe(BluetoothGattCharacteristic characteristic) {
        if (gatt == null || characteristic == null) {
            return false;
        }
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Log.w(TAG, "Unable to enable ANCS notification for " + characteristic.getUuid());
            return false;
        }

        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID);
        if (descriptor == null) {
            Log.w(TAG, "ANCS descriptor not found for " + characteristic.getUuid());
            return false;
        }

        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        descriptorWriteInFlight = gatt.writeDescriptor(descriptor);
        return descriptorWriteInFlight;
    }

    private void onNotificationSource(byte[] value) {
        if (value == null || value.length < 8) {
            return;
        }

        int eventId = value[0] & 0xff;
        long uid = readUInt32LE(value, 4);
        if (eventId == EVENT_ADDED || eventId == EVENT_MODIFIED) {
            if (isStartupBacklogEvent()) {
                Log.d(TAG, "Ignoring startup ANCS backlog notification uid=" + uid);
                return;
            }
            emitPlaceholder(uid);
            pendingAttributeRequests.offer(uid);
            requestNextNotificationAttributes();
        } else if (eventId == EVENT_REMOVED) {
            emitRemoved(uid);
        }
    }

    private boolean isStartupBacklogEvent() {
        return notificationSourceReadyAtMs > 0
                && System.currentTimeMillis() - notificationSourceReadyAtMs < STARTUP_BACKLOG_SUPPRESSION_MS;
    }

    @SuppressLint("MissingPermission")
    private void requestNextNotificationAttributes() {
        if (waitingForAttributeResponse || gatt == null || controlPoint == null) {
            return;
        }

        activeAttributeRequestUid = pendingAttributeRequests.poll();
        if (activeAttributeRequestUid == null) {
            return;
        }

        byte[] request = buildAttributeRequest(activeAttributeRequestUid);
        controlPoint.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        controlPoint.setValue(request);
        controlPointWriteInFlight = gatt.writeCharacteristic(controlPoint);
        waitingForAttributeResponse = controlPointWriteInFlight;
        if (!controlPointWriteInFlight) {
            Log.w(TAG, "Unable to queue ANCS attribute request");
            pendingAttributeRequests.addFirst(activeAttributeRequestUid);
            activeAttributeRequestUid = null;
            requestNextNotificationAttributes();
        } else {
            handlerThread.removeCallbacks(flushPartialAttributes);
            handlerThread.postDelayed(flushPartialAttributes, PARTIAL_ATTRIBUTES_FLUSH_MS);
        }
    }

    private byte[] buildAttributeRequest(long uid) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(COMMAND_GET_NOTIFICATION_ATTRIBUTES);
        writeUInt32LE(out, uid);
        out.write(ATTR_APP_IDENTIFIER);
        writeAttributeWithMaxLength(out, ATTR_TITLE, 80);
        writeAttributeWithMaxLength(out, ATTR_SUBTITLE, 80);
        writeAttributeWithMaxLength(out, ATTR_MESSAGE, 240);
        out.write(ATTR_DATE);
        return out.toByteArray();
    }

    private void onDataSource(byte[] value) {
        if (value == null || value.length == 0) {
            return;
        }

        dataSourceBuffer.write(value, 0, value.length);
        byte[] bytes = dataSourceBuffer.toByteArray();
        ParsedAttributes parsed = tryParseAttributes(bytes, true);
        if (parsed == null) {
            handlerThread.removeCallbacks(flushPartialAttributes);
            handlerThread.postDelayed(flushPartialAttributes, PARTIAL_ATTRIBUTES_FLUSH_MS);
            return;
        }

        completeAttributeResponse(parsed);
    }

    private void flushPartialAttributeResponse() {
        if (!waitingForAttributeResponse) {
            return;
        }

        if (dataSourceBuffer.size() > 0) {
            ParsedAttributes parsed = tryParseAttributes(dataSourceBuffer.toByteArray(), false);
            if (parsed != null) {
                completeAttributeResponse(parsed);
                return;
            }
        }

        Log.w(TAG, "Timed out waiting for ANCS attributes");
        dataSourceBuffer.reset();
        activeAttributeRequestUid = null;
        waitingForAttributeResponse = false;
        controlPointWriteInFlight = false;
        authorizationRetryCount = 0;
        requestNextNotificationAttributes();
    }

    private void completeAttributeResponse(ParsedAttributes parsed) {
        handlerThread.removeCallbacks(flushPartialAttributes);
        dataSourceBuffer.reset();
        activeAttributeRequestUid = null;
        waitingForAttributeResponse = false;
        authorizationRetryCount = 0;
        emitPosted(parsed);
        requestNextNotificationAttributes();
    }

    private ParsedAttributes tryParseAttributes(byte[] bytes, boolean requireAllAttributes) {
        if (bytes.length < 5 || (bytes[0] & 0xff) != COMMAND_GET_NOTIFICATION_ATTRIBUTES) {
            return null;
        }

        int offset = 1;
        long uid = readUInt32LE(bytes, offset);
        offset += 4;

        Map<Integer, String> attributes = new HashMap<>();
        while (offset < bytes.length && attributes.size() < REQUESTED_ATTRIBUTES.length) {
            if (offset + 3 > bytes.length) {
                return null;
            }

            int attributeId = bytes[offset++] & 0xff;
            int length = readUInt16LE(bytes, offset);
            offset += 2;
            if (offset + length > bytes.length) {
                return null;
            }

            attributes.put(attributeId, new String(bytes, offset, length, StandardCharsets.UTF_8));
            offset += length;
        }

        if (requireAllAttributes && attributes.size() < REQUESTED_ATTRIBUTES.length) {
            return null;
        }
        if (attributes.isEmpty()) {
            return null;
        }

        return new ParsedAttributes(uid, attributes);
    }

    private void emitPosted(ParsedAttributes parsed) {
        String appId = parsed.attributes.get(ATTR_APP_IDENTIFIER);
        if (appId == null || appId.length() == 0) {
            appId = "ios.ancs";
        }
        appIdByNotificationUid.put(parsed.uid, appId);

        String title = parsed.attributes.get(ATTR_TITLE);
        String subtitle = parsed.attributes.get(ATTR_SUBTITLE);
        String message = parsed.attributes.get(ATTR_MESSAGE);

        NotificationData data = new NotificationData();
        data.action = NotificationData.Action.Posted;
        data.id = notificationId(parsed.uid);
        data.packageName = "ios.ancs";
        data.appName = readableAppName(appId);
        data.title = firstNonEmpty(title, subtitle, data.appName);
        data.text = mergeText(subtitle, message);
        data.postedTime = parseDate(parsed.attributes.get(ATTR_DATE));
        data.isOngoing = false;
        data.deliveryMode = NotificationData.DeliveryMode.Sound;

        handler.onDataReceived(new RPCMessage(NotificationsAPI.ID, data));
    }

    private void emitPlaceholder(long uid) {
        NotificationData data = new NotificationData();
        data.action = NotificationData.Action.Posted;
        data.id = notificationId(uid);
        data.packageName = "ios.ancs";
        data.appName = "iPhone";
        data.title = "iPhone notification";
        data.text = "Notification received. Waiting for details from iOS.";
        data.postedTime = System.currentTimeMillis();
        data.isOngoing = false;
        data.deliveryMode = NotificationData.DeliveryMode.Sound;

        handler.onDataReceived(new RPCMessage(NotificationsAPI.ID, data));
    }

    private void emitRemoved(long uid) {
        NotificationData data = new NotificationData();
        data.action = NotificationData.Action.Removed;
        data.id = notificationId(uid);
        appIdByNotificationUid.remove(uid);
        data.packageName = "ios.ancs";
        data.appName = readableAppName(data.packageName);
        data.postedTime = System.currentTimeMillis();
        data.isOngoing = false;
        handler.onDataReceived(new RPCMessage(NotificationsAPI.ID, data));
    }

    private static String mergeText(String subtitle, String message) {
        if (isEmpty(subtitle)) {
            return message;
        }
        if (isEmpty(message) || subtitle.equals(message)) {
            return subtitle;
        }
        return subtitle + "\n" + message;
    }

    private static String firstNonEmpty(String first, String second, String fallback) {
        if (!isEmpty(first)) return first;
        if (!isEmpty(second)) return second;
        return fallback;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.length() == 0;
    }

    private static String readableAppName(String appId) {
        if (appId == null || appId.length() == 0) {
            return "iPhone";
        }
        int index = appId.lastIndexOf('.');
        String name = index >= 0 && index < appId.length() - 1 ? appId.substring(index + 1) : appId;
        return name.length() == 0 ? "iPhone" : name;
    }

    private static long parseDate(String value) {
        if (value == null || value.length() == 0) {
            return System.currentTimeMillis();
        }
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US);
            dateFormat.setTimeZone(TimeZone.getDefault());
            Date date = dateFormat.parse(value);
            return date != null ? date.getTime() : System.currentTimeMillis();
        } catch (Exception ignored) {
            return System.currentTimeMillis();
        }
    }

    private static int notificationId(long uid) {
        return (int) (uid & 0xffffffffL);
    }

    private static long readUInt32LE(byte[] bytes, int offset) {
        return ((long) bytes[offset] & 0xff)
                | (((long) bytes[offset + 1] & 0xff) << 8)
                | (((long) bytes[offset + 2] & 0xff) << 16)
                | (((long) bytes[offset + 3] & 0xff) << 24);
    }

    private static int readUInt16LE(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static void writeUInt32LE(ByteArrayOutputStream out, long value) {
        out.write((int) (value & 0xff));
        out.write((int) ((value >> 8) & 0xff));
        out.write((int) ((value >> 16) & 0xff));
        out.write((int) ((value >> 24) & 0xff));
    }

    private static void writeUInt16LE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }

    private static void writeAttributeWithMaxLength(ByteArrayOutputStream out, int attributeId, int maxLength) {
        out.write(attributeId);
        writeUInt16LE(out, maxLength);
    }

    private static final class ParsedAttributes {
        final long uid;
        final Map<Integer, String> attributes;

        ParsedAttributes(long uid, Map<Integer, String> attributes) {
            this.uid = uid;
            this.attributes = attributes;
        }
    }

    interface AuthorizationHandler {
        boolean onAncsAuthorizationRequired(int gattStatus);
    }
}
