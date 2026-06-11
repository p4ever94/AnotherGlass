package com.damn.anotherglass.glass.host.bluetooth;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.damn.anotherglass.shared.bluetooth.BluetoothLeConstants;
import com.damn.anotherglass.shared.rpc.IRPCClient;
import com.damn.anotherglass.shared.rpc.JsonMessageCodec;
import com.damn.anotherglass.shared.rpc.RPCHandler;
import com.damn.anotherglass.shared.rpc.RPCMessage;
import com.damn.anotherglass.shared.rpc.RPCMessageListener;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

public class BluetoothLeClient implements IRPCClient {
    private static final String TAG = "BluetoothLeClient";
    private static final int SCAN_TIMEOUT_MS = 30_000;
    private static final int WRITE_CHUNK_SIZE = 20;
    private static final int CUSTOM_WRITE_RETRY_MS = 250;
    private static final int CUSTOM_WRITE_MAX_RETRIES = 5;
    private static final int ANCS_DISCOVERY_RETRY_MS = 1_000;
    private static final int ANCS_DISCOVERY_MAX_RETRIES = 8;
    private static final int ANCS_SETUP_TIMEOUT_MS = 6_000;
    private static final int CONNECTION_STARTED_DELAY_MS = 300;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Deque<byte[]> mWriteQueue = new ConcurrentLinkedDeque<>();
    private final ByteArrayOutputStream mReceiveBuffer = new ByteArrayOutputStream();

    private RPCHandler mRPCHandler;
    private Context mContext;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothGatt mGatt;
    private BluetoothGattCharacteristic mPhoneToGlass;
    private BluetoothGattCharacteristic mGlassToPhone;
    private AncsClient mAncsClient;
    private boolean mActive;
    private boolean mWriteInFlight;
    private byte[] mCurrentWriteChunk;
    private int mCurrentWriteRetries;
    private boolean mBondReceiverRegistered;
    private boolean mCustomNotificationsSubscribed;
    private boolean mConnectionStartedNotified;
    private boolean mPendingAncsStart;
    private boolean mReadyForCustomRpc;
    private boolean mWaitingForAncsSetup;
    private int mAncsDiscoveryRetryCount;

    private final Runnable mAncsSetupTimeout = () -> {
        if (mActive && mWaitingForAncsSetup) {
            markAncsSetupFinished("ANCS setup timed out");
        }
    };

    private final BroadcastReceiver mBondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction()) || mGatt == null) {
                return;
            }

            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null || !device.getAddress().equals(mGatt.getDevice().getAddress())) {
                return;
            }

            int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
            int previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
            Log.i(TAG, "BLE bond state changed: " + bondStateName(previousBondState) + " -> " + bondStateName(bondState));
            if (bondState == BluetoothDevice.BOND_BONDED) {
                Log.i(TAG, "BLE bond completed, rediscovering services for ANCS");
                mPendingAncsStart = false;
                mHandler.postDelayed(() -> {
                    if (mActive && mGatt != null) {
                        mGatt.discoverServices();
                    }
                }, 750);
            } else if (bondState == BluetoothDevice.BOND_NONE && mPendingAncsStart) {
                Log.w(TAG, "BLE bond was not completed; ANCS notification details may be unavailable");
                mPendingAncsStart = false;
                markAncsSetupFinished("BLE bond was not completed");
            }
        }
    };

    private final BluetoothAdapter.LeScanCallback mScanCallback = (device, rssi, scanRecord) -> {
        if (!mActive) {
            return;
        }
        if (!isAnotherGlassAdvertisement(device, scanRecord)) {
            return;
        }
        Log.i(TAG, "Found BLE companion: " + device.getAddress());
        stopScan();
        connect(device);
    };

    private final Runnable mScanTimeout = () -> {
        if (!mActive || mGatt != null) {
            return;
        }
        stopScan();
        shutdownWithError("Bluetooth LE companion not found");
    };

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (!mActive) {
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "BLE connected, discovering services");
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                shutdownWithError(status == BluetoothGatt.GATT_SUCCESS ? null : "Bluetooth LE disconnected: " + status);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (!mActive) {
                return;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                shutdownWithError("Bluetooth LE service discovery failed: " + status);
                return;
            }

            BluetoothGattService service = gatt.getService(BluetoothLeConstants.SERVICE_UUID);
            if (service == null) {
                shutdownWithError("AnotherGlass BLE service not found");
                return;
            }

            mPhoneToGlass = service.getCharacteristic(BluetoothLeConstants.PHONE_TO_GLASS_UUID);
            mGlassToPhone = service.getCharacteristic(BluetoothLeConstants.GLASS_TO_PHONE_UUID);
            if (mPhoneToGlass == null || mGlassToPhone == null) {
                shutdownWithError("AnotherGlass BLE characteristics not found");
                return;
            }

            if (mCustomNotificationsSubscribed) {
                startAncsOrRequestBond(gatt);
                return;
            }

            if (!gatt.setCharacteristicNotification(mPhoneToGlass, true)) {
                shutdownWithError("Unable to enable Bluetooth LE notifications");
                return;
            }

            BluetoothGattDescriptor descriptor = mPhoneToGlass.getDescriptor(BluetoothLeConstants.CLIENT_CHARACTERISTIC_CONFIG_UUID);
            if (descriptor == null) {
                shutdownWithError("Bluetooth LE notification descriptor not found");
                return;
            }
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(descriptor);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (mAncsClient != null && mAncsClient.handlesDescriptor(descriptor)) {
                mAncsClient.onDescriptorWrite(descriptor, status);
                if (mAncsClient.isReady()) {
                    markAncsSetupFinished("ANCS notifications enabled");
                } else if (!mAncsClient.hasPendingGattOperation()) {
                    mAncsClient = null;
                    scheduleAncsRediscovery("ANCS subscription did not complete");
                }
                drainWriteQueue();
                return;
            }

            if (!mActive) {
                return;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                shutdownWithError("Unable to subscribe to Bluetooth LE notifications: " + status);
                return;
            }
            mCustomNotificationsSubscribed = true;
            startAncsOrRequestBond(gatt);
            notifyConnectionStartedIfReady();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (mAncsClient != null && mAncsClient.handlesCharacteristic(characteristic)) {
                mAncsClient.onCharacteristicChanged(characteristic, characteristic.getValue());
            } else {
                onCharacteristicBytes(characteristic.getValue());
            }
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value) {
            if (mAncsClient != null && mAncsClient.handlesCharacteristic(characteristic)) {
                mAncsClient.onCharacteristicChanged(characteristic, value);
            } else {
                onCharacteristicBytes(value);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (mAncsClient != null && mAncsClient.handlesCharacteristic(characteristic)) {
                mAncsClient.onCharacteristicWrite(characteristic, status);
                drainWriteQueue();
                return;
            }

            mWriteInFlight = false;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                retryOrFailCurrentWrite(status);
                return;
            }
            mCurrentWriteChunk = null;
            mCurrentWriteRetries = 0;
            drainWriteQueue();
        }
    };

    @Override
    @SuppressLint("MissingPermission")
    public void start(Context context, RPCMessageListener listener) {
        if (mActive) {
            Log.e(TAG, "Bluetooth LE client already active");
            return;
        }

        mRPCHandler = new RPCHandler(listener);
        mContext = context.getApplicationContext();
        registerBondReceiver();
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            unregisterBondReceiver();
            listener.onConnectionLost("Bluetooth is disabled");
            return;
        }

        mActive = true;
        mRPCHandler.onWaiting();
        // Service UUID filters are unreliable on some old Android/Glass BLE stacks.
        // Scan broadly and filter the advertisement payload ourselves.
        mBluetoothAdapter.startLeScan(mScanCallback);
        mHandler.postDelayed(mScanTimeout, SCAN_TIMEOUT_MS);
    }

    @Override
    public void send(@NonNull RPCMessage message) {
        if (!mActive || mGlassToPhone == null) {
            return;
        }
        enqueue(JsonMessageCodec.toJsonLineBytes(message));
        drainWriteQueue();
    }

    @Override
    public void stop() {
        if (!mActive) {
            return;
        }
        send(new RPCMessage(null, null));
        mActive = false;
        closeGatt();
        stopScan();
        mHandler.removeCallbacks(mScanTimeout);
        mWriteQueue.clear();
        mReceiveBuffer.reset();
        unregisterBondReceiver();
    }

    private void connect(BluetoothDevice device) {
        Log.i(TAG, "Connecting to BLE companion " + device.getAddress() + " bond=" + bondStateName(device.getBondState()));
        mGatt = device.connectGatt(mContext, false, mGattCallback);
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (mBluetoothAdapter != null) {
            mBluetoothAdapter.stopLeScan(mScanCallback);
        }
        mHandler.removeCallbacks(mScanTimeout);
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        if (mGatt != null) {
            mGatt.disconnect();
            mGatt.close();
            mGatt = null;
        }
        mPhoneToGlass = null;
        mGlassToPhone = null;
        mAncsClient = null;
        mWriteInFlight = false;
        mCurrentWriteChunk = null;
        mCurrentWriteRetries = 0;
        mCustomNotificationsSubscribed = false;
        mConnectionStartedNotified = false;
        mPendingAncsStart = false;
        mReadyForCustomRpc = false;
        mWaitingForAncsSetup = false;
        mAncsDiscoveryRetryCount = 0;
        mHandler.removeCallbacks(mAncsSetupTimeout);
    }

    private void shutdownWithError(String error) {
        if (!mActive) {
            return;
        }
        mActive = false;
        closeGatt();
        stopScan();
        mWriteQueue.clear();
        unregisterBondReceiver();
        mRPCHandler.onConnectionLost(error);
    }

    @SuppressLint("MissingPermission")
    private void startAncsOrRequestBond(BluetoothGatt gatt) {
        if (!mReadyForCustomRpc) {
            mWaitingForAncsSetup = true;
            mHandler.removeCallbacks(mAncsSetupTimeout);
            mHandler.postDelayed(mAncsSetupTimeout, ANCS_SETUP_TIMEOUT_MS);
        }
        if (mAncsClient != null) {
            mAncsClient.retryPendingRequests();
            return;
        }

        if (gatt.getService(AncsClient.SERVICE_UUID) == null) {
            Log.i(TAG, "ANCS service not currently exposed by iOS");
            BluetoothDevice device = gatt.getDevice();
            if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                scheduleAncsRediscovery("ANCS service not exposed after bonding");
            } else {
                if (!requestBondForAncs(device, "ANCS service not exposed")) {
                    markAncsSetupFinished("Unable to request BLE bond for ANCS");
                }
            }
            return;
        }

        mAncsDiscoveryRetryCount = 0;
        mAncsClient = new AncsClient(mRPCHandler, this::onAncsAuthorizationRequired);
        if (!mAncsClient.start(gatt)) {
            mAncsClient = null;
            scheduleAncsRediscovery("ANCS client did not start");
        }
    }

    @SuppressLint("MissingPermission")
    private void scheduleAncsRediscovery(String reason) {
        if (!mActive || mGatt == null) {
            return;
        }
        if (mAncsDiscoveryRetryCount >= ANCS_DISCOVERY_MAX_RETRIES) {
            Log.w(TAG, "ANCS unavailable after retries: " + reason);
            markAncsSetupFinished("ANCS unavailable after retries");
            return;
        }

        mAncsDiscoveryRetryCount++;
        Log.i(TAG, "Retrying ANCS discovery (" + mAncsDiscoveryRetryCount + "/" + ANCS_DISCOVERY_MAX_RETRIES + "): " + reason);
        mHandler.postDelayed(() -> {
            if (mActive && mGatt != null && mAncsClient == null) {
                mGatt.discoverServices();
            }
        }, ANCS_DISCOVERY_RETRY_MS);
    }

    private void markAncsSetupFinished(String reason) {
        Log.i(TAG, "BLE RPC ready after ANCS setup: " + reason);
        mHandler.removeCallbacks(mAncsSetupTimeout);
        mWaitingForAncsSetup = false;
        notifyConnectionStartedIfReady();
    }

    private void notifyConnectionStartedIfReady() {
        if (!mActive || !mCustomNotificationsSubscribed || mWaitingForAncsSetup || mConnectionStartedNotified) {
            return;
        }
        mReadyForCustomRpc = true;
        mConnectionStartedNotified = true;
        mHandler.postDelayed(() -> {
            if (!mActive || !mReadyForCustomRpc) {
                return;
            }
            mRPCHandler.onConnectionStarted("iPhone BLE");
            drainWriteQueue();
        }, CONNECTION_STARTED_DELAY_MS);
    }

    @SuppressLint("MissingPermission")
    private boolean onAncsAuthorizationRequired(int status) {
        BluetoothGatt gatt = mGatt;
        if (gatt == null) {
            return false;
        }

        BluetoothDevice device = gatt.getDevice();
        int bondState = device.getBondState();
        Log.i(TAG, "ANCS authorization required, status=" + status + ", bond=" + bondStateName(bondState));
        if (bondState == BluetoothDevice.BOND_BONDING) {
            mPendingAncsStart = true;
            Log.i(TAG, "BLE bonding already in progress; waiting before retrying ANCS");
            return true;
        }
        if (bondState != BluetoothDevice.BOND_BONDED) {
            return requestBondForAncs(device, "ANCS details require pairing");
        }

        Log.i(TAG, "ANCS write status " + status + " after bonding; retrying shortly");
        mHandler.postDelayed(() -> {
            if (mActive && mAncsClient != null) {
                mAncsClient.retryPendingRequests();
            }
        }, 1_000);
        return true;
    }

    @SuppressLint("MissingPermission")
    private boolean requestBondForAncs(BluetoothDevice device, String reason) {
        int bondState = device.getBondState();
        if (bondState == BluetoothDevice.BOND_BONDED) {
            return true;
        }
        if (bondState == BluetoothDevice.BOND_BONDING) {
            mPendingAncsStart = true;
            Log.i(TAG, "BLE bonding already in progress for ANCS: " + reason);
            return true;
        }

        mPendingAncsStart = true;
        Log.i(TAG, "Requesting BLE bond for ANCS: " + reason);
        boolean started = device.createBond();
        if (!started) {
            mPendingAncsStart = false;
        }
        return started;
    }

    private void registerBondReceiver() {
        if (mBondReceiverRegistered || mContext == null) {
            return;
        }
        mContext.registerReceiver(mBondReceiver, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
        mBondReceiverRegistered = true;
    }

    private void unregisterBondReceiver() {
        if (!mBondReceiverRegistered || mContext == null) {
            return;
        }
        try {
            mContext.unregisterReceiver(mBondReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        mBondReceiverRegistered = false;
    }

    private void enqueue(byte[] data) {
        int offset = 0;
        while (offset < data.length) {
            int end = Math.min(data.length, offset + WRITE_CHUNK_SIZE);
            mWriteQueue.add(Arrays.copyOfRange(data, offset, end));
            offset = end;
        }
    }

    @SuppressLint("MissingPermission")
    private void drainWriteQueue() {
        if (!mActive || mWriteInFlight || mGatt == null || mGlassToPhone == null) {
            return;
        }
        if (!mReadyForCustomRpc) {
            return;
        }
        if (mWaitingForAncsSetup && mAncsClient != null && mAncsClient.hasPendingGattOperation()) {
            return;
        }

        byte[] chunk = mWriteQueue.poll();
        if (chunk == null) {
            return;
        }

        mGlassToPhone.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        mGlassToPhone.setValue(chunk);
        mCurrentWriteChunk = chunk;
        mWriteInFlight = mGatt.writeCharacteristic(mGlassToPhone);
        if (!mWriteInFlight) {
            mCurrentWriteChunk = null;
            Log.w(TAG, "Bluetooth LE custom write busy; retrying");
            mWriteQueue.addFirst(chunk);
            mHandler.postDelayed(this::drainWriteQueue, CUSTOM_WRITE_RETRY_MS);
        }
    }

    private void retryOrFailCurrentWrite(int status) {
        byte[] chunk = mCurrentWriteChunk;
        mCurrentWriteChunk = null;
        if (chunk == null) {
            shutdownWithError("Bluetooth LE write failed: " + status);
            return;
        }

        if (mCurrentWriteRetries < CUSTOM_WRITE_MAX_RETRIES) {
            mCurrentWriteRetries++;
            mWriteQueue.addFirst(chunk);
            int delayMs = CUSTOM_WRITE_RETRY_MS * mCurrentWriteRetries;
            Log.w(TAG, "Bluetooth LE custom write failed: " + status + "; retry " + mCurrentWriteRetries);
            mHandler.postDelayed(this::drainWriteQueue, delayMs);
            return;
        }

        mCurrentWriteRetries = 0;
        shutdownWithError("Bluetooth LE write failed: " + status);
    }

    private void onCharacteristicBytes(byte[] value) {
        if (value == null || value.length == 0) {
            return;
        }

        mReceiveBuffer.write(value, 0, value.length);
        byte[] bytes = mReceiveBuffer.toByteArray();
        int lineStart = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != '\n') {
                continue;
            }
            String line = new String(bytes, lineStart, i - lineStart, StandardCharsets.UTF_8);
            lineStart = i + 1;
            if (line.length() == 0) {
                continue;
            }
            RPCMessage message = JsonMessageCodec.fromJsonLine(line);
            if (message == null || message.service == null) {
                shutdownWithError(null);
                return;
            }
            mRPCHandler.onDataReceived(message);
        }

        mReceiveBuffer.reset();
        if (lineStart < bytes.length) {
            mReceiveBuffer.write(bytes, lineStart, bytes.length - lineStart);
        }
    }

    @SuppressLint("MissingPermission")
    private boolean isAnotherGlassAdvertisement(BluetoothDevice device, byte[] scanRecord) {
        String deviceName = device.getName();
        if (isAnotherGlassName(deviceName)) {
            return true;
        }
        if (scanRecord == null) {
            return false;
        }

        int offset = 0;
        while (offset < scanRecord.length) {
            int length = scanRecord[offset++] & 0xff;
            if (length == 0) {
                break;
            }
            int nextOffset = offset + length;
            if (nextOffset > scanRecord.length) {
                break;
            }

            int type = scanRecord[offset++] & 0xff;
            int dataLength = length - 1;
            if ((type == 0x08 || type == 0x09) && dataLength > 0) {
                String name = new String(scanRecord, offset, dataLength, StandardCharsets.UTF_8);
                if (isAnotherGlassName(name)) {
                    return true;
                }
            } else if ((type == 0x06 || type == 0x07) && dataLength >= 16) {
                for (int uuidOffset = offset; uuidOffset + 16 <= offset + dataLength; uuidOffset += 16) {
                    if (BluetoothLeConstants.SERVICE_UUID.equals(readUuid128LittleEndian(scanRecord, uuidOffset))) {
                        return true;
                    }
                }
            }
            offset = nextOffset;
        }
        return false;
    }

    private static boolean isAnotherGlassName(String name) {
        return "AGlass".equals(name) || "AnotherGlass".equals(name);
    }

    private static String bondStateName(int state) {
        switch (state) {
            case BluetoothDevice.BOND_NONE:
                return "BOND_NONE";
            case BluetoothDevice.BOND_BONDING:
                return "BOND_BONDING";
            case BluetoothDevice.BOND_BONDED:
                return "BOND_BONDED";
            case BluetoothDevice.ERROR:
                return "ERROR";
            default:
                return "UNKNOWN(" + state + ")";
        }
    }

    private static UUID readUuid128LittleEndian(byte[] bytes, int offset) {
        long mostSignificant = 0;
        long leastSignificant = 0;
        for (int i = 15; i >= 8; i--) {
            mostSignificant = (mostSignificant << 8) | (bytes[offset + i] & 0xffL);
        }
        for (int i = 7; i >= 0; i--) {
            leastSignificant = (leastSignificant << 8) | (bytes[offset + i] & 0xffL);
        }
        return new UUID(mostSignificant, leastSignificant);
    }
}
