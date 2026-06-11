package com.damn.anotherglass.shared.bluetooth;

import java.util.UUID;

public final class BluetoothLeConstants {
    public static final UUID SERVICE_UUID = UUID.fromString("05f2934c-1e81-4554-bb08-44aa761afbfb");
    public static final UUID GLASS_TO_PHONE_UUID = UUID.fromString("05f2934c-1e81-4554-bb08-44aa761afbfc");
    public static final UUID PHONE_TO_GLASS_UUID = UUID.fromString("05f2934c-1e81-4554-bb08-44aa761afbfd");
    public static final UUID CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothLeConstants() {
    }
}
