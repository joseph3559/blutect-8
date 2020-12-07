package com.smartwatch.atcfit;


import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

public class MainActivity extends Activity implements RadioGroup.OnCheckedChangeListener {
    private static final int REQUEST_SELECT_DEVICE = 1;
    private static final int REQUEST_ENABLE_BT = 2;
    private static final int UART_PROFILE_READY = 10;
    public static final String TAG = "ATCFitUART";
    private static final int UART_PROFILE_CONNECTED = 20;
    private static final int UART_PROFILE_DISCONNECTED = 21;
    private static final int STATE_OFF = 10;

    TextView mRemoteRssiVal;
    RadioGroup mRg;
    private int mState = UART_PROFILE_DISCONNECTED;
    private PrimaryService mService = null;
    private BluetoothDevice mDevice = null;
    private BluetoothAdapter mBtAdapter = null;
    private FloatingActionButton btnConnectDisconnect;
    private TextView txtStatus;
    private TextView txtBattery;
    private boolean isConnected = false;
    private boolean isNotified = false;

    final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_);
        mBtAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBtAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "Ble not supported.", Toast.LENGTH_SHORT).show();
            finish();
        }

        if (mBtAdapter == null || !mBtAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }

        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        boolean gps_enabled = false;
        boolean network_enabled = false;

        try {
            gps_enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception ex) {
        }

        try {
            network_enabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception ex) {
        }

        if (!gps_enabled && !network_enabled) {
            // notify user
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        }

        int permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION);
        if (!(permissionCheck == PackageManager.PERMISSION_GRANTED)) {

            // Should we show an explanation?
            //if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_COARSE_LOCATION)) {

            //} else {
                // do request the permission
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 8);
            //}
        }
        txtStatus = (TextView) findViewById(R.id.txtname);
        txtBattery = (TextView) findViewById(R.id.txtbattery);

        btnConnectDisconnect = findViewById(R.id.scanButton);

        service_init();

        findViewById(R.id.syncButton).setOnClickListener(new View.OnClickListener() {
               @Override
               public void onClick(View v) {
                   //send data to
                   String syncDate = android.text.format.DateFormat.format("yyyyMMddkkmm", new java.util.Date()).toString();

                   try {
                       mService.writeRXCharacteristic("AT+DT=" + syncDate + "\r\n");
                   } catch (Exception e) {
                       e.printStackTrace();
                   }
               }
           }
        );

        // Handle Disconnect & Connect button
        btnConnectDisconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mBtAdapter.isEnabled()) {
                    Log.i(TAG, "onClick - BT not enabled yet");
                    Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
                } else {
                    if (!isConnected) {
                        Intent newIntent = new Intent(MainActivity.this, DeviceListActivity.class);
                        startActivityForResult(newIntent, REQUEST_SELECT_DEVICE);
                    } else {
                        //Disconnect button pressed
                        if (mDevice != null) {
                            mService.disconnect();
                        }
                    }
                }
            }
        });

//        btnBattery.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View view) {
//                if (!mBtAdapter.isEnabled()) {
//                    Log.i(TAG, "onClick - BT not enabled yet");
//                    Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
//                    startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
//                } else {
//                    if (!isConnected) {
//                        Intent newIntent = new Intent(MainActivity.this, DeviceListActivity.class);
//                        startActivityForResult(newIntent, REQUEST_SELECT_DEVICE);
//                    } else {
//                        //Disconnect button pressed
//                        if (mService != null) {
//                            try {
//                                mService.writeRXCharacteristic("AT+BATT" + "\r\n");
//                            } catch (Exception e) {
//                                e.printStackTrace();
//                            }
//                        }
//                    }
//                }
//            }
//        });

    }

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder rawBinder) {
            mService = ((PrimaryService.LocalBinder) rawBinder).getService();
            Log.d(TAG, "onServiceConnected mService= " + mService);
            if (!mService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finish();
            }
        }

        public void onServiceDisconnected(ComponentName classname) {
            mService = null;
        }
    };

    private final BroadcastReceiver UARTStatusChangeReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(PrimaryService.ACTION_GATT_CONNECTED)) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        isConnected = true;
                        //btnConnectDisconnect.setText("Disconnect");
                        txtStatus.setText(mDevice.getName() + " is connected.");
                        mState = UART_PROFILE_CONNECTED;
                    }
                });
            }

            //*********************//
            if (action.equals(PrimaryService.ACTION_GATT_DISCONNECTED)) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        isConnected = false;
                        isNotified = false;
                        //btnConnectDisconnect.setText("Connect");
                        txtStatus.setText("Not Connected");
                        mState = UART_PROFILE_DISCONNECTED;
                        mService.close();
                    }
                });
            }

            //*********************//
            if (action.equals(PrimaryService.ACTION_GATT_SERVICES_DISCOVERED)) {

                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        String syncDate = android.text.format.DateFormat.format("yyyyMMddkkmm", new java.util.Date()).toString();

                        try {
                            mService.writeRXCharacteristic("AT+DT=" + syncDate + "\r\n");
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }, 2000);

            }

            if (action.equals(PrimaryService.ACTION_GATT_CHARTERISTIC_NOTIFIED)) {
                isNotified = true;
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            mService.writeRXCharacteristic("AT+BATT" + "\r\n");
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        handler.postDelayed(this,300000);
                    }
                }, 1000);

            }

            if (action.equals(PrimaryService.ACTION_SEND_MESSAGE)) {
                final String message = intent.getStringExtra(PrimaryService.EXTRAS_MESSAGE);
                runOnUiThread(new Runnable() {
                    public void run() {
                        txtBattery.setText(message);
                    }
                });
            }

            if (action.equals(PrimaryService.DEVICE_DOES_NOT_SUPPORT_UART)) {
                showMessage("Device doesn't support USART. Disconnecting");
                mService.disconnect();
            }
        }
    };


    private void service_init() {
        Intent bindIntent = new Intent(this, PrimaryService.class);
        bindService(bindIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
        LocalBroadcastManager.getInstance(this).registerReceiver(UARTStatusChangeReceiver, makeGattUpdateIntentFilter());
    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(PrimaryService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(PrimaryService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(PrimaryService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(PrimaryService.ACTION_GATT_CHARTERISTIC_NOTIFIED);
        intentFilter.addAction(PrimaryService.DEVICE_DOES_NOT_SUPPORT_UART);
        intentFilter.addAction(PrimaryService.ACTION_SEND_MESSAGE);
        return intentFilter;
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy()");

        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(UARTStatusChangeReceiver);
        } catch (Exception ignore) {
            Log.e(TAG, ignore.toString());
        }
        unbindService(mServiceConnection);
        mService.stopSelf();
        mService = null;

    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onRestart() {
        super.onRestart();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!mBtAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
        }
        if(isNotified){
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        mService.writeRXCharacteristic("AT+BATT" + "\r\n");
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    handler.postDelayed(this,300000);
                }
            }, 1000);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {

            case REQUEST_SELECT_DEVICE:
                if (resultCode == Activity.RESULT_OK && data != null) {
                    String deviceAddress = data.getStringExtra(BluetoothDevice.EXTRA_DEVICE);
                    mDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(deviceAddress);

                    ((TextView) findViewById(R.id.txtname)).setText(mDevice.getName() + " - connecting");
                    mService.connect(deviceAddress);


                }
                break;
            case REQUEST_ENABLE_BT:
                if (resultCode == Activity.RESULT_OK) {
                    Toast.makeText(this, "Bluetooth has turned on ", Toast.LENGTH_SHORT).show();

                } else {
                    Toast.makeText(this, "Problem in BT Turning ON ", Toast.LENGTH_SHORT).show();
                    finish();
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onCheckedChanged(RadioGroup group, int checkedId) {

    }


    private void showMessage(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onBackPressed() {
        if (mState == UART_PROFILE_CONNECTED) {
            Intent startMain = new Intent(Intent.ACTION_MAIN);
            startMain.addCategory(Intent.CATEGORY_HOME);
            startMain.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(startMain);
        } else {
            new AlertDialog.Builder(this)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setTitle(R.string.popup_title)
                    .setMessage(R.string.popup_message)
                    .setPositiveButton(R.string.popup_yes, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            finish();
                        }
                    })
                    .setNegativeButton(R.string.popup_no, null)
                    .show();
        }
    }
}
