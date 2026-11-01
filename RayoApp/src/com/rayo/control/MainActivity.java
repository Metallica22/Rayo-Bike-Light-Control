package com.rayo.control;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements BleManager.BleListener {

    private static final int REQ_PERMISSIONS = 101;
    private static final int REQ_ENABLE_BT = 102;

    private BleManager bleManager;
    private boolean isLightOn = false;
    private boolean isConnected = false;
    private int currentProfile = 0; // 0..3

    private TextView tvStatus;
    private TextView tvBattery;
    private Button btnScan;

    private Button btnLightOn;
    private Button btnLightOff;

    private Button[] btnProfiles = new Button[4];
    private TextView tvActiveProfile;

    private TextView tvBrightnessLabel;
    private SeekBar sbBrightness;
    private Spinner spnLedTop, spnLedMid, spnLedBot;

    private TextView tvLedTopBrightLabel, tvLedMidBrightLabel, tvLedBotBrightLabel;
    private SeekBar sbLedTopBright, sbLedMidBright, sbLedBotBright;
    private Switch swPerLedBrightness;

    private Switch swBrake;
    private Spinner spnBrakeSens;
    private Button btnApplyLeds;

    private Switch swTheft;
    private Button btnApplyFeatures;

    private TextView tvBleLog;
    private Button btnClearLog;
    private long lastUserProfileSelectTime = 0;

    private String[] patterns;
    private String[] sensitivity;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        patterns = getResources().getStringArray(R.array.patterns_array);
        sensitivity = getResources().getStringArray(R.array.sensitivity_array);

        initViews();
        bleManager = new BleManager(this, this);

        checkPermissions();
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tvStatus);
        tvBattery = findViewById(R.id.tvBattery);
        btnScan = findViewById(R.id.btnScan);

        btnLightOn = findViewById(R.id.btnLightOn);
        btnLightOff = findViewById(R.id.btnLightOff);

        btnProfiles[0] = findViewById(R.id.btnProfile0);
        btnProfiles[1] = findViewById(R.id.btnProfile1);
        btnProfiles[2] = findViewById(R.id.btnProfile2);
        btnProfiles[3] = findViewById(R.id.btnProfile3);
        tvActiveProfile = findViewById(R.id.tvActiveProfile);

        tvBrightnessLabel = findViewById(R.id.tvBrightnessLabel);
        sbBrightness = findViewById(R.id.sbBrightness);

        spnLedTop = findViewById(R.id.spnLedTop);
        spnLedMid = findViewById(R.id.spnLedMid);
        spnLedBot = findViewById(R.id.spnLedBot);

        tvLedTopBrightLabel = findViewById(R.id.tvLedTopBrightLabel);
        sbLedTopBright = findViewById(R.id.sbLedTopBright);
        tvLedMidBrightLabel = findViewById(R.id.tvLedMidBrightLabel);
        sbLedMidBright = findViewById(R.id.sbLedMidBright);
        tvLedBotBrightLabel = findViewById(R.id.tvLedBotBrightLabel);
        sbLedBotBright = findViewById(R.id.sbLedBotBright);
        swPerLedBrightness = findViewById(R.id.swPerLedBrightness);

        ArrayAdapter<String> patternAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, patterns);
        spnLedTop.setAdapter(patternAdapter);
        spnLedMid.setAdapter(patternAdapter);
        spnLedBot.setAdapter(patternAdapter);

        // Initial default selections
        spnLedTop.setSelection(0); // Constant
        spnLedMid.setSelection(2); // Blink
        spnLedBot.setSelection(1); // Glow

        // Individual LED Brightness listeners
        sbLedTopBright.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvLedTopBrightLabel.setText(getString(R.string.brightness_label_format, progress));
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (!swPerLedBrightness.isChecked()) {
                    sbBrightness.setProgress(seekBar.getProgress());
                }
                applyCurrentPatterns(false);
            }
        });

        sbLedMidBright.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvLedMidBrightLabel.setText(getString(R.string.brightness_label_format, progress));
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (!swPerLedBrightness.isChecked()) {
                    sbBrightness.setProgress(seekBar.getProgress());
                }
                applyCurrentPatterns(false);
            }
        });

        sbLedBotBright.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvLedBotBrightLabel.setText(getString(R.string.brightness_label_format, progress));
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (!swPerLedBrightness.isChecked()) {
                    sbBrightness.setProgress(seekBar.getProgress());
                }
                applyCurrentPatterns(false);
            }
        });

        swBrake = findViewById(R.id.swBrake);
        spnBrakeSens = findViewById(R.id.spnBrakeSens);
        spnBrakeSens.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, sensitivity));
        spnBrakeSens.setSelection(1);

        btnApplyLeds = findViewById(R.id.btnApplyLeds);

        swTheft = findViewById(R.id.swTheft);
        btnApplyFeatures = findViewById(R.id.btnApplyFeatures);

        // Brightness SeekBar listener
        sbBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvBrightnessLabel.setText(getString(R.string.brightness_label_format, progress));
                if (fromUser && !swPerLedBrightness.isChecked()) {
                    sbLedTopBright.setProgress(progress);
                    sbLedMidBright.setProgress(progress);
                    sbLedBotBright.setProgress(progress);
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                applyCurrentPatterns(false);
            }
        });

        // Click Listeners
        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isConnected) {
                    bleManager.disconnect();
                } else {
                    if (ensureBluetoothEnabled()) {
                        bleManager.startScan();
                    }
                }
            }
        });

        // 1. DEDICATED LIGHT ON BUTTON
        btnLightOn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isLightOn = true;
                bleManager.setLight(true, swTheft.isChecked());
                Toast.makeText(MainActivity.this, getString(R.string.toast_light_on), Toast.LENGTH_SHORT).show();
            }
        });

        // 2. DEDICATED LIGHT OFF BUTTON
        btnLightOff.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isLightOn = false;
                bleManager.setLight(false, swTheft.isChecked());
                Toast.makeText(MainActivity.this, getString(R.string.toast_light_off), Toast.LENGTH_SHORT).show();
            }
        });

        // 3. PROFILE BUTTONS
        for (int i = 0; i < 4; i++) {
            final int pIdx = i;
            btnProfiles[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    lastUserProfileSelectTime = System.currentTimeMillis();
                    selectProfileUI(pIdx);
                    bleManager.selectProfile(pIdx);
                    Toast.makeText(MainActivity.this, getString(R.string.toast_profile_selected, pIdx + 1), Toast.LENGTH_SHORT).show();
                }
            });
        }
        selectProfileUI(0);

        // 4. APPLY LED PATTERNS & INDIVIDUAL BRIGHTNESS
        btnApplyLeds.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyCurrentPatterns(true);
            }
        });

        // 5. APPLY ALARM
        btnApplyFeatures.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bleManager.setLight(isLightOn, swTheft.isChecked());
                Toast.makeText(MainActivity.this, getString(R.string.toast_alarm_applied), Toast.LENGTH_SHORT).show();
            }
        });

        // 6. DEVELOPER OPTIONS (COLLAPSIBLE BLE LOG)
        Switch swShowDevOptions = findViewById(R.id.swShowDevOptions);
        final View layoutDevLogContainer = findViewById(R.id.layoutDevLogContainer);
        if (swShowDevOptions != null && layoutDevLogContainer != null) {
            swShowDevOptions.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    layoutDevLogContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                }
            });
        }

        tvBleLog = findViewById(R.id.tvBleLog);
        btnClearLog = findViewById(R.id.btnClearLog);
        if (btnClearLog != null) {
            btnClearLog.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (tvBleLog != null) tvBleLog.setText("");
                }
            });
        }

        // 7. BUY ME A COFFEE
        View btnBuyMeACoffee = findViewById(R.id.btnBuyMeACoffee);
        if (btnBuyMeACoffee != null) {
            btnBuyMeACoffee.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/02eOWKzm5W"));
                        startActivity(browserIntent);
                    } catch (Exception ignored) {}
                }
            });
        }
    }

    private void applyCurrentPatterns(boolean showToast) {
        if (!isConnected) {
            if (showToast) {
                Toast.makeText(this, getString(R.string.status_disconnected), Toast.LENGTH_SHORT).show();
            }
            return;
        }

        int masterBright = sbBrightness.getProgress();
        int topBri = sbLedTopBright.getProgress();
        int midBri = sbLedMidBright.getProgress();
        int botBri = sbLedBotBright.getProgress();

        int top = spnLedTop.getSelectedItemPosition();
        int mid = spnLedMid.getSelectedItemPosition();
        int bot = spnLedBot.getSelectedItemPosition();

        boolean brake = swBrake.isChecked();
        int brakeSens = spnBrakeSens.getSelectedItemPosition();
        boolean perLed = swPerLedBrightness.isChecked();

        if (!perLed) {
            topBri = masterBright;
            midBri = masterBright;
            botBri = masterBright;
        }

        bleManager.setModePattern(currentProfile, masterBright, topBri, top, midBri, mid, botBri, bot, brake, brakeSens, perLed);
        if (showToast) {
            Toast.makeText(this, getString(R.string.toast_apply_leds), Toast.LENGTH_SHORT).show();
        }
    }

    private void selectProfileUI(int index) {
        currentProfile = index;
        for (int i = 0; i < 4; i++) {
            if (i == index) {
                btnProfiles[i].setBackgroundColor(0xFFFF2A55); // Highlight red
            } else {
                btnProfiles[i].setBackgroundColor(0xFF282A33); // Dark gray
            }
        }
        tvActiveProfile.setText(getString(R.string.active_profile_format, index + 1));
    }

    private boolean checkPermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12, 13, 14, 15
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            // Android 11 and older
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQ_PERMISSIONS);
            return false;
        }
        return true;
    }

    private boolean ensureBluetoothEnabled() {
        if (!bleManager.isBluetoothEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            try {
                startActivityForResult(enableBtIntent, REQ_ENABLE_BT);
            } catch (Exception ignored) {}
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            boolean allGranted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) allGranted = false;
            }
            if (allGranted) {
                Toast.makeText(this, getString(R.string.perm_granted), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, getString(R.string.perm_denied), Toast.LENGTH_LONG).show();
            }
        }
    }

    // BLE LISTENER CALLBACKS
    @Override
    public void onConnectionStateChange(boolean connected, String deviceName, String mac) {
        this.isConnected = connected;
        if (connected) {
            tvStatus.setText(getString(R.string.status_connected, deviceName, mac));
            tvStatus.setTextColor(0xFF00E676);
            btnScan.setText(R.string.btn_disconnect);
            btnScan.setBackgroundColor(0xFF444444);
        } else {
            tvStatus.setText(R.string.status_disconnected);
            tvStatus.setTextColor(0xFF9E9EA7);
            tvBattery.setText(R.string.battery_placeholder);
            btnScan.setText(R.string.btn_scan);
            btnScan.setBackgroundColor(0xFFFF2A55);
        }
    }

    @Override
    public void onBatteryUpdate(int percent) {
        tvBattery.setText(getString(R.string.battery_format, percent));
    }

    @Override
    public void onLightStateUpdate(boolean isOn) {
        this.isLightOn = isOn;
    }

    @Override
    public void onActiveModeUpdate(int mode) {
        if (System.currentTimeMillis() - lastUserProfileSelectTime < 1500) {
            // Ignore temporary in-flight status right after user clicked a profile button
            return;
        }
        if (mode >= 0 && mode < 4 && mode != currentProfile) {
            selectProfileUI(mode);
        }
    }

    @Override
    public void onModeDataReceived(int mode, int masterBrightness,
                                   int topBri, int topPat,
                                   int midBri, int midPat,
                                   int botBri, int botPat,
                                   boolean brakeActive, int brakeSens) {
        selectProfileUI(mode);
        sbBrightness.setProgress(masterBrightness > 0 ? masterBrightness : 100);

        sbLedTopBright.setProgress(topBri > 0 ? topBri : 100);
        sbLedMidBright.setProgress(midBri > 0 ? midBri : 100);
        sbLedBotBright.setProgress(botBri > 0 ? botBri : 100);

        tvLedTopBrightLabel.setText(getString(R.string.brightness_label_format, sbLedTopBright.getProgress()));
        tvLedMidBrightLabel.setText(getString(R.string.brightness_label_format, sbLedMidBright.getProgress()));
        tvLedBotBrightLabel.setText(getString(R.string.brightness_label_format, sbLedBotBright.getProgress()));

        swBrake.setChecked(brakeActive);

        if (brakeSens >= 0 && brakeSens < sensitivity.length) {
            spnBrakeSens.setSelection(brakeSens);
        }
        if (topPat >= 0 && topPat < patterns.length) {
            spnLedTop.setSelection(topPat);
        }
        if (midPat >= 0 && midPat < patterns.length) {
            spnLedMid.setSelection(midPat);
        }
        if (botPat >= 0 && botPat < patterns.length) {
            spnLedBot.setSelection(botPat);
        }

        Toast.makeText(this, getString(R.string.toast_profile_loaded, mode + 1), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRawPacketLog(String direction, byte[] data) {
        if (tvBleLog == null || data == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(direction).append(": ");
        for (byte b : data) {
            sb.append(String.format("%02X ", b));
        }
        sb.append("\n");
        tvBleLog.append(sb.toString());

        // Keep last ~50 lines if buffer gets very long
        if (tvBleLog.getLineCount() > 50) {
            int linesToRemove = tvBleLog.getLineCount() - 40;
            String cur = tvBleLog.getText().toString();
            int idx = 0;
            for (int i = 0; i < linesToRemove && idx != -1; i++) {
                idx = cur.indexOf('\n', idx);
                if (idx != -1) idx++;
            }
            if (idx != -1 && idx < cur.length()) {
                tvBleLog.setText(cur.substring(idx));
            }
        }
    }

    @Override
    public void onTheftAlert(boolean alarmActive) {
        if (alarmActive) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.theft_dialog_title)
                    .setMessage(R.string.theft_dialog_message)
                    .setPositiveButton(R.string.theft_dialog_stop, null)
                    .show();
        }
    }

    @Override
    public void onStatusMessage(String message) {
        tvStatus.setText(message);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bleManager != null) {
            bleManager.disconnect();
        }
    }
}
