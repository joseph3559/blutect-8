package com.smartwatch.atcfit;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PrimaryService extends Service {
    private final static String TAG = PrimaryService.class.getSimpleName();

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private BluetoothGatt mBluetoothGatt;
    private int mConnectionState = STATE_DISCONNECTED;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    public final static String ACTION_GATT_CONNECTED = "com.smartwatch.atcfit.ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED = "com.smartwatch.atcfit.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED = "com.smartwatch.atcfit.ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_GATT_CHARTERISTIC_NOTIFIED = "com.smartwatch.atcfit.ACTION_GATT_CHARTERISTIC_NOTIFIED";
    public final static String DEVICE_DOES_NOT_SUPPORT_UART = "com.smartwatch.atcfit.DEVICE_DOES_NOT_SUPPORT_UART";
    public final static String ACTION_SEND_MESSAGE = "com.smartwatch.atcfit.ACTION_SEND_MESSAGE";

    //ATC Watch P8 a
    public static final UUID RX_SERVICE_UUID = UUID.fromString("0000190a-0000-1000-8000-00805f9b34fb");
    public static final UUID RX_CHAR_UUID = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb");
    public static final UUID TX_CHAR_UUID = UUID.fromString("00000002-0000-1000-8000-00805f9b34fb");

    public static final UUID CONFIG_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final String EXTRAS_MESSAGE = "com.smartwatch.atcfit.EXTRAS_DEVICE_BATTERY";

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String intentAction;
            
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mBluetoothGatt = gatt;
                intentAction = ACTION_GATT_CONNECTED;
                mConnectionState = STATE_CONNECTED;
                broadcastUpdate(intentAction);
//                broadcastUpdate(ACTION_SEND_MESSAGE,"Connected!");
                // Attempts to discover services after successful connection.
                Log.i(TAG, "start service discovery:" + mBluetoothGatt.discoverServices());

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                intentAction = ACTION_GATT_DISCONNECTED;
                mConnectionState = STATE_DISCONNECTED;
                broadcastUpdate(intentAction);
//                broadcastUpdate(ACTION_SEND_MESSAGE,"Disonnected!");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
                BluetoothGattCharacteristic TxChar = mBluetoothGatt.getService(RX_SERVICE_UUID).getCharacteristic(TX_CHAR_UUID);
                if (TxChar == null) {
                    showMessage("Charateristic not found!");
//                    broadcastUpdate(ACTION_SEND_MESSAGE,"Characteristic Not Found");
                    broadcastUpdate(DEVICE_DOES_NOT_SUPPORT_UART);
                }else {
//                    broadcastUpdate(ACTION_SEND_MESSAGE,"Characteristic Found");
                    subscribeToNotification(TxChar);
                    mBluetoothGatt.readCharacteristic(TxChar);
                }
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);
            if(status == BluetoothGatt.GATT_SUCCESS) {
                if(characteristic.getUuid().equals(TX_CHAR_UUID)){

//                    broadcastUpdate(ACTION_SEND_MESSAGE,"Read value from watch!");
                    byte[] bytes = characteristic.getValue();
                    String str = new String(bytes, StandardCharsets.ISO_8859_1);
                    if(str.contains(":")){
                        String[] parts = str.split(":");
                        if(parts[0].equals("AT+BATT")){
                            broadcastUpdate(ACTION_SEND_MESSAGE,"The battery is "+parts[1]+"%");
                        }
                    }

                    if (!mBluetoothGatt.setCharacteristicNotification(characteristic, true)) {
//                        broadcastUpdate(ACTION_SEND_MESSAGE,"Failed to subscribe to watch data!");
                        return;
                    }else{
//                        broadcastUpdate(ACTION_SEND_MESSAGE,"Successfully subscribed to watch data!");
                    }
                    BluetoothGattDescriptor desc = characteristic.getDescriptor(CONFIG_DESCRIPTOR);
                    desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(desc);

                }else {
//                    broadcastUpdate(ACTION_SEND_MESSAGE,"Read value from other characteristic!");
                }
            }else if(status == BluetoothGatt.GATT_READ_NOT_PERMITTED){
//                broadcastUpdate(ACTION_SEND_MESSAGE,"Read not permitted!");
            }else {
//                broadcastUpdate(ACTION_SEND_MESSAGE,"Error "+status+" encountered in reading");
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if(characteristic.getUuid().equals(TX_CHAR_UUID)){
//                broadcastUpdate(ACTION_SEND_MESSAGE,"Read new value from watch!");
                byte[] bytes = characteristic.getValue();
                String str = new String(bytes, StandardCharsets.ISO_8859_1);
                if(str.contains(":")){
                    String[] parts = str.split(":");
                    if(parts[0].equals("AT+BATT")){
                        broadcastUpdate(ACTION_SEND_MESSAGE,"The battery is "+parts[1]+"%");
                    }
                }
            }else {
//                broadcastUpdate(ACTION_SEND_MESSAGE,"Read value from other characteristic!");
            }
        }
    };

    public void readTxCharacteristic(){
        BluetoothGattCharacteristic TxChar = mBluetoothGatt.getService(RX_SERVICE_UUID).getCharacteristic(TX_CHAR_UUID);
        if (TxChar != null) {
            mBluetoothGatt.readCharacteristic(TxChar);
        }
    }

    public void subscribeToNotification(BluetoothGattCharacteristic characteristic){
        if (!mBluetoothGatt.setCharacteristicNotification(characteristic, true)) {
//            broadcastUpdate(ACTION_SEND_MESSAGE,"Failed to subscribe to watch data!");
            return;
        }else{
//            broadcastUpdate(ACTION_SEND_MESSAGE,"Successfully subscribed to watch data!");
        }
        BluetoothGattDescriptor desc = characteristic.getDescriptor(CONFIG_DESCRIPTOR);
        desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        mBluetoothGatt.writeDescriptor(desc);
        broadcastUpdate(ACTION_GATT_CHARTERISTIC_NOTIFIED);
    }

    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastUpdate(final String action,
                                 final String message) {
        final Intent intent = new Intent(action);
        intent.putExtra(EXTRAS_MESSAGE, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    public class LocalBinder extends Binder {
        PrimaryService getService() {
            return PrimaryService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        close();
        return super.onUnbind(intent);
    }

    private final IBinder mBinder = new LocalBinder();


    public boolean initialize() {
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }

        return true;
    }

    public boolean connect(final String address) {
        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        // Previously connected device.  Try to reconnect.
        if (mBluetoothDeviceAddress != null && address.equals(mBluetoothDeviceAddress)
                && mBluetoothGatt != null) {
            Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
            if (mBluetoothGatt.connect()) {
                mConnectionState = STATE_CONNECTING;
                return true;
            } else {
                return false;
            }
        }

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.");
            return false;
        }

        mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
        Log.d(TAG, "Trying to create a new connection.");
        mBluetoothDeviceAddress = address;
        mConnectionState = STATE_CONNECTING;
        return true;
    }

    public void disconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.disconnect();
    }

    public void close() {
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothDeviceAddress = null;
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.readCharacteristic(characteristic);
    }

    
    public void writeRXCharacteristic(String value) throws Exception {
    	BluetoothGattService RxService = mBluetoothGatt.getService(RX_SERVICE_UUID);
    	showMessage("mBluetoothGatt "+ mBluetoothGatt);
    	if (RxService == null) {
            showMessage("Service not found!");
            broadcastUpdate(DEVICE_DOES_NOT_SUPPORT_UART);
            return;
        }
    	BluetoothGattCharacteristic RxChar = RxService.getCharacteristic(RX_CHAR_UUID);
        if (RxChar == null) {
            showMessage("Charateristic not found!");
            broadcastUpdate(DEVICE_DOES_NOT_SUPPORT_UART);
            return;
        }

        if(value.length()>20){
            List<String> parts = new ArrayList<String>();
            int len = value.length();
            for (int i=0; i<len; i+=20)
            {
                RxChar.setValue(value.substring(i, Math.min(len, i + 20)).getBytes("UTF-8"));
                boolean status = mBluetoothGatt.writeCharacteristic(RxChar);
            }

       }
        else{
            RxChar.setValue(value.getBytes("UTF-8"));
            boolean status = mBluetoothGatt.writeCharacteristic(RxChar);
        }

    }


    private void showMessage(String msg) {
        Log.e(TAG, msg);
    }

    public List<BluetoothGattService> getSupportedGattServices() {
        if (mBluetoothGatt == null) return null;

        return mBluetoothGatt.getServices();
    }
}
