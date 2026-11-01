package com.rayo.control;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BleManager {
    private static final String TAG = "RayoBle";

    // Nordic UART Service (NUS)
    public static final UUID NUS_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    public static final UUID NUS_TX_CHAR_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"); // Notify (Device -> Phone)
    public static final UUID NUS_RX_CHAR_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"); // Write (Phone -> Device)
    public static final UUID CLIENT_CONFIG_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    // Standard Battery Service
    public static final UUID BATTERY_SERVICE_UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB");
    public static final UUID BATTERY_LEVEL_CHAR_UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB");

    public interface BleListener {
        void onConnectionStateChange(boolean connected, String deviceName, String mac);
        void onBatteryUpdate(int percent);
        void onLightStateUpdate(boolean isOn);
        void onActiveModeUpdate(int mode);
        void onModeDataReceived(int mode, int masterBrightness,
                                int topBri, int topPat,
                                int midBri, int midPat,
                                int botBri, int botPat,
                                boolean brakeActive, int brakeSens);
        void onTheftAlert(boolean alarmActive);
        void onStatusMessage(String message);
        void onRawPacketLog(String direction, byte[] data);
    }

    private final Context context;
    private final BleListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic rxChar;
    private boolean isScanning = false;
    private boolean isConnected = false;

    public BleManager(Context context, BleListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            this.bluetoothAdapter = adapter;
            this.bleScanner = adapter.getBluetoothLeScanner();
        }
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    public void startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            notifyStatus(context.getString(R.string.status_bt_disabled));
            return;
        }
        if (bleScanner == null) {
            bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        }
        if (bleScanner == null) {
            notifyStatus(context.getString(R.string.status_no_scanner));
            return;
        }

        if (isScanning) {
            stopScan();
        }

        isScanning = true;
        notifyStatus(context.getString(R.string.status_scanning));

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        List<ScanFilter> filters = new ArrayList<>();
        try {
            bleScanner.startScan(filters, settings, scanCallback);
        } catch (SecurityException se) {
            notifyStatus(context.getString(R.string.status_permission_missing));
            Log.e(TAG, "SecurityException on startScan", se);
        }

        // Auto-stop scan after 15 seconds
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isScanning) {
                    stopScan();
                    if (!isConnected) {
                        notifyStatus(context.getString(R.string.status_scan_timeout));
                    }
                }
            }
        }, 15000);
    }

    public void stopScan() {
        if (isScanning && bleScanner != null) {
            try {
                bleScanner.stopScan(scanCallback);
            } catch (Exception ignored) {}
            isScanning = false;
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null) return;

            String name = "";
            try {
                name = device.getName();
            } catch (SecurityException ignored) {}

            String address = device.getAddress();

            boolean matchesName = name != null && (name.toUpperCase().contains("RAYO") || name.toUpperCase().contains("CLIQ"));
            boolean matchesMac = "ED:DB:13:F5:49:7B".equalsIgnoreCase(address);

            boolean matchesNus = false;
            if (result.getScanRecord() != null && result.getScanRecord().getServiceUuids() != null) {
                for (ParcelUuid u : result.getScanRecord().getServiceUuids()) {
                    if (NUS_SERVICE_UUID.equals(u.getUuid())) {
                        matchesNus = true;
                        break;
                    }
                }
            }

            if (matchesName || matchesMac || matchesNus) {
                Log.d(TAG, "Found target Rayo device: " + name + " [" + address + "]");
                stopScan();
                connectToDevice(device);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "Scan failed: " + errorCode);
            isScanning = false;
            notifyStatus(context.getString(R.string.status_scan_failed, errorCode));
        }
    };

    public void connectToDevice(BluetoothDevice device) {
        String devName = "RAYO";
        try {
            if (device.getName() != null) devName = device.getName();
        } catch (SecurityException ignored) {}

        notifyStatus(context.getString(R.string.status_connecting, devName));

        try {
            if (bluetoothGatt != null) {
                bluetoothGatt.close();
                bluetoothGatt = null;
            }
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException se) {
            notifyStatus(context.getString(R.string.status_permission_missing));
            Log.e(TAG, "Connect error", se);
        }
    }

    public void disconnect() {
        stopScan();
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (Exception ignored) {}
            bluetoothGatt = null;
        }
        isConnected = false;
        rxChar = null;
        postConnectionChange(false, null, null);
        notifyStatus(context.getString(R.string.status_disconnected));
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.d(TAG, "onConnectionStateChange: status=" + status + ", newState=" + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true;
                notifyStatus(context.getString(R.string.status_reading_services));
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (bluetoothGatt != null) bluetoothGatt.discoverServices();
                        } catch (SecurityException ignored) {}
                    }
                }, 300);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false;
                rxChar = null;
                postConnectionChange(false, null, null);
                notifyStatus(context.getString(R.string.status_disconnected));
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyStatus(context.getString(R.string.status_read_services_failed));
                return;
            }

            BluetoothGattService nus = gatt.getService(NUS_SERVICE_UUID);
            if (nus != null) {
                rxChar = nus.getCharacteristic(NUS_RX_CHAR_UUID);
                BluetoothGattCharacteristic txChar = nus.getCharacteristic(NUS_TX_CHAR_UUID);

                if (rxChar != null) {
                    int props = rxChar.getProperties();
                    if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) > 0) {
                        rxChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    } else {
                        rxChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                    }
                }

                if (txChar != null) {
                    try {
                        gatt.setCharacteristicNotification(txChar, true);
                        BluetoothGattDescriptor desc = txChar.getDescriptor(CLIENT_CONFIG_DESCRIPTOR);
                        if (desc != null) {
                            desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            gatt.writeDescriptor(desc);
                        }
                    } catch (SecurityException ignored) {}
                }
            }

            // Read Battery Service
            final BluetoothGattService batService = gatt.getService(BATTERY_SERVICE_UUID);
            if (batService != null) {
                final BluetoothGattCharacteristic batChar = batService.getCharacteristic(BATTERY_LEVEL_CHAR_UUID);
                if (batChar != null) {
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (bluetoothGatt != null) bluetoothGatt.readCharacteristic(batChar);
                            } catch (SecurityException ignored) {}
                        }
                    }, 600);
                }
            }

            String name = "RAYO";
            try {
                if (gatt.getDevice().getName() != null) name = gatt.getDevice().getName();
            } catch (SecurityException ignored) {}

            postConnectionChange(true, name, gatt.getDevice().getAddress());
            notifyStatus(context.getString(R.string.status_connected_simple, name));

            // Query initial mode & status
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    selectProfile(0); // Query Profil 1
                }
            }, 800);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value == null || value.length < 2) return;

            StringBuilder sb = new StringBuilder();
            for (byte b : value) sb.append(String.format("%02X ", b));
            Log.d(TAG, "RAW BLE RECV: " + sb.toString());
            postRawPacketLog("RX", value);

            // Format: [Length, Cmd, Payload...]
            int cmd = value[1] & 0xFF;

            if (cmd == 0x30 && value.length >= 5) {
                // Status packet: [Length, 0x30, Battery, LightState, Mode, Theft]
                int battery = value[2] & 0xFF;
                boolean isOn = (value[3] & 0xFF) % 2 == 1;
                int mode = value[4] & 0xFF;
                boolean theft = value.length > 5 && (value[5] & 0xFF) == 1;

                postBatteryUpdate(battery);
                postLightStateUpdate(isOn);
                postActiveModeUpdate(mode);
                if (theft) postTheftAlert(true);
            } else if (cmd == 0x20 && value.length >= 10) {
                // Mode query response: [len, 0x20, modeId, bright, brakeActive, brakeSens, sync, topByte, midByte, botByte]
                int modeId = value[2] & 0xFF;
                int masterBright = value[3] & 0xFF;
                boolean brakeOn = (value[4] & 0xFF) == 1;
                int brakeSens = value[5] & 0xFF;

                int topByte = value[7] & 0xFF;
                int midByte = value[8] & 0xFF;
                int botByte = value[9] & 0xFF;

                int topPat = topByte & 0x07;
                int midPat = midByte & 0x07;
                int botPat = botByte & 0x07;

                int topBri31 = (topByte >> 3) & 0x1F;
                int midBri31 = (midByte >> 3) & 0x1F;
                int botBri31 = (botByte >> 3) & 0x1F;

                int topBriPercent;
                int midBriPercent;
                int botBriPercent;

                if (topBri31 > 0 || midBri31 > 0 || botBri31 > 0) {
                    topBriPercent = Math.round((topBri31 / 31.0f) * 100.0f);
                    midBriPercent = Math.round((midBri31 / 31.0f) * 100.0f);
                    botBriPercent = Math.round((botBri31 / 31.0f) * 100.0f);
                } else {
                    topBriPercent = masterBright;
                    midBriPercent = masterBright;
                    botBriPercent = masterBright;
                }

                postModeDataReceived(modeId, masterBright, topBriPercent, topPat, midBriPercent, midPat, botBriPercent, botPat, brakeOn, brakeSens);
            } else if (cmd == 0x21) {
                notifyStatus(context.getString(R.string.status_saved_patterns));
            } else if (cmd == 0x11) {
                notifyStatus(context.getString(R.string.status_config_ack));
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS && BATTERY_LEVEL_CHAR_UUID.equals(characteristic.getUuid())) {
                byte[] val = characteristic.getValue();
                if (val != null && val.length > 0) {
                    int percent = val[0] & 0xFF;
                    postBatteryUpdate(percent);
                }
            }
        }
    };

    public boolean sendCommand(byte cmd, byte[] payload) {
        if (!isConnected || bluetoothGatt == null || rxChar == null) {
            notifyStatus(context.getString(R.string.status_not_connected));
            return false;
        }

        int payloadLen = (payload != null) ? payload.length : 0;
        byte[] packet = new byte[payloadLen + 2];
        packet[0] = (byte) (payloadLen + 1); // Length field (cmd + payload)
        packet[1] = cmd;
        if (payload != null) {
            System.arraycopy(payload, 0, packet, 2, payloadLen);
        }

        StringBuilder sb = new StringBuilder();
        for (byte b : packet) sb.append(String.format("%02X ", b));
        Log.d(TAG, "RAW BLE SEND: " + sb.toString());

        try {
            rxChar.setValue(packet);
            boolean ok = bluetoothGatt.writeCharacteristic(rxChar);
            Log.d(TAG, "WriteCharacteristic result: " + ok);
            if (ok) {
                postRawPacketLog("TX", packet);
            }
            return ok;
        } catch (SecurityException se) {
            Log.e(TAG, "Write error", se);
            return false;
        }
    }

    public void setLight(boolean on, boolean theft) {
        // cmd_set_config (0x11): [0xFF, 0xFF, theft ? 1 : 0, on ? 1 : 0]
        byte[] payload = new byte[4];
        payload[0] = (byte) 0xFF;
        payload[1] = (byte) 0xFF;
        payload[2] = theft ? (byte) 1 : (byte) 0;
        payload[3] = on ? (byte) 1 : (byte) 0;

        sendCommand((byte) 0x11, payload);
    }

    public void selectProfile(int profileIndex) {
        // cmd_get_mode (0x20): switches Rayo to profileIndex and replies with current LED configuration
        byte[] payload = new byte[]{(byte) (profileIndex & 0x03)};
        sendCommand((byte) 0x20, payload);
    }

    public void setModePattern(int profileIndex, int masterBrightness,
                               int topBrightness, int topPattern,
                               int midBrightness, int midPattern,
                               int botBrightness, int botPattern,
                               boolean brakeActive, int brakeSens,
                               boolean perLedBrightness) {
        // cmd_set_mode (0x21), 8 bytes payload
        byte[] payload = new byte[8];
        payload[0] = (byte) (profileIndex & 0x03);
        payload[1] = (byte) Math.max(0, Math.min(100, masterBrightness));
        payload[2] = brakeActive ? (byte) 1 : (byte) 0;
        payload[3] = (byte) (brakeSens & 0x03);
        payload[4] = 0; // Sync

        if (perLedBrightness) {
            int top31 = Math.round((Math.max(0, Math.min(100, topBrightness)) / 100.0f) * 31.0f);
            int mid31 = Math.round((Math.max(0, Math.min(100, midBrightness)) / 100.0f) * 31.0f);
            int bot31 = Math.round((Math.max(0, Math.min(100, botBrightness)) / 100.0f) * 31.0f);

            payload[5] = (byte) (((top31 & 0x1F) << 3) | (topPattern & 0x07));
            payload[6] = (byte) (((mid31 & 0x1F) << 3) | (midPattern & 0x07));
            payload[7] = (byte) (((bot31 & 0x1F) << 3) | (botPattern & 0x07));
        } else {
            payload[5] = (byte) (topPattern & 0x07);
            payload[6] = (byte) (midPattern & 0x07);
            payload[7] = (byte) (botPattern & 0x07);
        }

        sendCommand((byte) 0x21, payload);
    }

    private void notifyStatus(final String msg) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onStatusMessage(msg);
            }
        });
    }

    private void postConnectionChange(final boolean connected, final String name, final String mac) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onConnectionStateChange(connected, name, mac);
            }
        });
    }

    private void postBatteryUpdate(final int percent) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onBatteryUpdate(percent);
            }
        });
    }

    private void postLightStateUpdate(final boolean isOn) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onLightStateUpdate(isOn);
            }
        });
    }

    private void postActiveModeUpdate(final int mode) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onActiveModeUpdate(mode);
            }
        });
    }

    private void postModeDataReceived(final int mode, final int masterBrightness,
                                      final int topBri, final int topPat,
                                      final int midBri, final int midPat,
                                      final int botBri, final int botPat,
                                      final boolean brakeActive, final int brakeSens) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onModeDataReceived(mode, masterBrightness, topBri, topPat, midBri, midPat, botBri, botPat, brakeActive, brakeSens);
            }
        });
    }

    private void postTheftAlert(final boolean alarm) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onTheftAlert(alarm);
            }
        });
    }

    private void postRawPacketLog(final String dir, final byte[] data) {
        if (data == null) return;
        final byte[] copy = data.clone();
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onRawPacketLog(dir, copy);
            }
        });
    }
}
