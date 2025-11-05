package com.example.accidentdetection;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

public class TestingActivity extends AppCompatActivity {

    private static final String TAG = "TestingActivity";
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String ESP32_NAME = "ESP32_AccidentDetector";

    private TextInputEditText etSensorValue;
    private MaterialButton btnSimulateAccident, btnTestLow, btnTestMedium, btnTestHigh, btnBackToMain;
    private MaterialButton btnConnectESP32;
    private TextView tvSimulationResult;
    private TextView tvThresholdValue;
    private TextView tvESP32Status;
    private TextView tvLiveAccel;
    private TextView tvLiveGyro;
    private SeekBar seekBarThreshold;

    // Dynamic threshold that can be adjusted via slider
    private double currentThreshold = 6.0; // Default: 6.0 m/s²

    // ESP32 Bluetooth
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private InputStream inputStream;
    private boolean isConnected = false;
    private volatile boolean stopWorker = false;

    // Live sensor data
    private float currentAccel = 0.0f;
    private float currentGyro = 0.0f;

    private Handler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_testing);

        handler = new Handler(Looper.getMainLooper());
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        initViews();
        setupListeners();
        updateThresholdDisplay();
        updateConnectionStatus(false);
    }

    private void initViews() {
        etSensorValue = findViewById(R.id.etSensorValue);
        btnSimulateAccident = findViewById(R.id.btnSimulateAccident);
        btnTestLow = findViewById(R.id.btnTestLow);
        btnTestMedium = findViewById(R.id.btnTestMedium);
        btnTestHigh = findViewById(R.id.btnTestHigh);
        btnBackToMain = findViewById(R.id.btnBackToMain);
        btnConnectESP32 = findViewById(R.id.btnConnectESP32);
        tvSimulationResult = findViewById(R.id.tvSimulationResult);
        tvThresholdValue = findViewById(R.id.tvThresholdValue);
        tvESP32Status = findViewById(R.id.tvESP32Status);
        tvLiveAccel = findViewById(R.id.tvLiveAccel);
        tvLiveGyro = findViewById(R.id.tvLiveGyro);
        seekBarThreshold = findViewById(R.id.seekBarThreshold);
    }

    private void setupListeners() {
        // Threshold SeekBar listener
        seekBarThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Map progress (0-150) to threshold (1.0-15.0)
                currentThreshold = 1.0 + (progress / 10.0);
                updateThresholdDisplay();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Toast.makeText(TestingActivity.this,
                    String.format("Threshold set to %.1f m/s²", currentThreshold),
                    Toast.LENGTH_SHORT).show();
            }
        });

        // ESP32 Connection button
        btnConnectESP32.setOnClickListener(v -> {
            if (!isConnected) {
                connectToESP32();
            } else {
                disconnectESP32();
            }
        });

        // Custom simulation button
        btnSimulateAccident.setOnClickListener(v -> simulateAccident());

        // Quick test buttons
        btnTestLow.setOnClickListener(v -> quickTest(3.0, "Low Impact"));
        btnTestMedium.setOnClickListener(v -> quickTest(6.0, "Medium Impact"));
        btnTestHigh.setOnClickListener(v -> quickTest(10.0, "High Impact"));

        // Back button
        btnBackToMain.setOnClickListener(v -> finish());
    }

    private void connectToESP32() {
        Log.d(TAG, "Attempting to connect to ESP32...");

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Bluetooth permission required", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        try {
            bluetoothAdapter.cancelDiscovery();
        } catch (SecurityException e) {
            Log.e(TAG, "Error cancelling discovery: " + e.getMessage());
        }

        Set<BluetoothDevice> pairedDevices;
        try {
            pairedDevices = bluetoothAdapter.getBondedDevices();
        } catch (SecurityException se) {
            Toast.makeText(this, "Missing Bluetooth permission", Toast.LENGTH_SHORT).show();
            return;
        }

        BluetoothDevice esp32Device = null;
        for (BluetoothDevice device : pairedDevices) {
            try {
                if (ESP32_NAME.equals(device.getName())) {
                    esp32Device = device;
                    break;
                }
            } catch (SecurityException e) {
                // Ignore devices we can't get a name for
            }
        }

        if (esp32Device == null) {
            Toast.makeText(this, "ESP32 not paired. Please pair it first.", Toast.LENGTH_LONG).show();
            return;
        }

        final BluetoothDevice target = esp32Device;
        btnConnectESP32.setEnabled(false);
        tvESP32Status.setText("Connecting...");

        new Thread(() -> {
            try {
                bluetoothSocket = target.createRfcommSocketToServiceRecord(MY_UUID);
                bluetoothSocket.connect();
                inputStream = bluetoothSocket.getInputStream();
                isConnected = true;

                runOnUiThread(() -> {
                    updateConnectionStatus(true);
                    Toast.makeText(this, "✓ Connected to ESP32", Toast.LENGTH_SHORT).show();
                    btnConnectESP32.setEnabled(true);
                });

                beginListenForData();
            } catch (IOException | SecurityException e) {
                Log.e(TAG, "Connection failed: " + e.getMessage());
                safeCloseSocket();
                runOnUiThread(() -> {
                    updateConnectionStatus(false);
                    Toast.makeText(this, "Connection failed", Toast.LENGTH_SHORT).show();
                    btnConnectESP32.setEnabled(true);
                });
            }
        }).start();
    }

    private void disconnectESP32() {
        stopWorker = true;
        safeCloseSocket();
        updateConnectionStatus(false);
        Toast.makeText(this, "Disconnected from ESP32", Toast.LENGTH_SHORT).show();
    }

    private void safeCloseSocket() {
        try {
            if (inputStream != null) inputStream.close();
        } catch (IOException e) {
            Log.e(TAG, "Error closing inputStream", e);
        }
        try {
            if (bluetoothSocket != null) bluetoothSocket.close();
        } catch (IOException e) {
            Log.e(TAG, "Error closing bluetoothSocket", e);
        }
        inputStream = null;
        bluetoothSocket = null;
        isConnected = false;
    }

    private void updateConnectionStatus(boolean connected) {
        if (connected) {
            tvESP32Status.setText("✓ Connected to ESP32");
            btnConnectESP32.setText("Disconnect ESP32");
            tvLiveAccel.setText("Accel: Waiting for data...");
            tvLiveGyro.setText("Gyro: Waiting for data...");
        } else {
            tvESP32Status.setText("✗ Not Connected");
            btnConnectESP32.setText("Connect to ESP32");
            tvLiveAccel.setText("Accel: -- m/s²");
            tvLiveGyro.setText("Gyro: -- °/s");
            currentAccel = 0.0f;
            currentGyro = 0.0f;
        }
    }

    private void beginListenForData() {
        stopWorker = false;
        Thread workerThread = new Thread(() -> {
            byte[] buffer = new byte[1024];
            int bytes;

            while (!Thread.currentThread().isInterrupted() && !stopWorker) {
                try {
                    if (inputStream != null) {
                        bytes = inputStream.read(buffer);
                        if (bytes > 0) {
                            String data = new String(buffer, 0, bytes, "UTF-8");
                            Log.v(TAG, "Received: " + data);
                            parseSensorData(data);
                        }
                    }
                } catch (IOException e) {
                    stopWorker = true;
                    Log.e(TAG, "Error reading data: " + e.getMessage());
                    runOnUiThread(() -> {
                        updateConnectionStatus(false);
                        Toast.makeText(this, "Connection lost", Toast.LENGTH_SHORT).show();
                    });
                }
            }
            Log.d(TAG, "Bluetooth listener stopped.");
        });
        workerThread.start();
    }

    private void parseSensorData(String data) {
        runOnUiThread(() -> {
            try {
                // Parse acceleration data
                if (data.contains("ACCEL:")) {
                    int accelStart = data.indexOf("ACCEL:") + 6;
                    int accelEnd = data.indexOf(" ", accelStart);
                    if (accelEnd == -1) accelEnd = data.length();
                    String accelStr = data.substring(accelStart, accelEnd).trim();
                    currentAccel = Float.parseFloat(accelStr);
                    tvLiveAccel.setText(String.format("Accel: %.2f m/s²", currentAccel));
                }

                // Parse gyroscope data
                if (data.contains("GYRO:")) {
                    int gyroStart = data.indexOf("GYRO:") + 5;
                    int gyroEnd = data.indexOf(" ", gyroStart);
                    if (gyroEnd == -1) gyroEnd = data.length();
                    String gyroStr = data.substring(gyroStart, gyroEnd).trim();
                    currentGyro = Float.parseFloat(gyroStr);
                    tvLiveGyro.setText(String.format("Gyro: %.2f °/s", currentGyro));
                }

                // Check if threshold exceeded
                if (currentAccel >= currentThreshold) {
                    tvSimulationResult.setText(String.format(
                        "🚨 THRESHOLD EXCEEDED!\n\n" +
                        "Live Accel: %.2f m/s²\n" +
                        "Threshold: %.1f m/s²\n" +
                        "Status: ACCIDENT DETECTED!\n\n" +
                        "Ready to trigger emergency alert.",
                        currentAccel, currentThreshold
                    ));
                } else {
                    tvSimulationResult.setText(String.format(
                        "📊 Live Monitoring Active\n\n" +
                        "Accel: %.2f m/s²\n" +
                        "Gyro: %.2f °/s\n" +
                        "Threshold: %.1f m/s²\n" +
                        "Status: Normal",
                        currentAccel, currentGyro, currentThreshold
                    ));
                }

            } catch (Exception e) {
                Log.e(TAG, "Error parsing sensor data: " + e.getMessage());
            }
        });
    }

    private void updateThresholdDisplay() {
        tvThresholdValue.setText(String.format("Current: %.1f m/s²", currentThreshold));
    }

    private void simulateAccident() {
        String input = etSensorValue.getText().toString();
        if (input.isEmpty()) {
            tvSimulationResult.setText("⚠️ Please enter a value.");
            Toast.makeText(this, "Please enter a sensor value", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            double simulatedValue = Double.parseDouble(input);
            evaluateAndTrigger(simulatedValue, "Custom");
        } catch (NumberFormatException e) {
            tvSimulationResult.setText("❌ Invalid number format.");
            Toast.makeText(this, "Invalid number format", Toast.LENGTH_SHORT).show();
        }
    }

    private void quickTest(double value, String testName) {
        etSensorValue.setText(String.valueOf(value));
        evaluateAndTrigger(value, testName);
    }

    private void evaluateAndTrigger(double simulatedValue, String testName) {
        boolean isAccident = simulatedValue >= currentThreshold;

        if (isAccident) {
            // Accident detected
            tvSimulationResult.setText(String.format(
                "🚨 ACCIDENT DETECTED!\n\n" +
                "Test: %s\n" +
                "Value: %.2f m/s²\n" +
                "Threshold: %.1f m/s²\n" +
                "Result: %.2f >= %.1f ✓\n\n" +
                "Emergency alert will be triggered!",
                testName, simulatedValue, currentThreshold, simulatedValue, currentThreshold
            ));

            Toast.makeText(this, "🚨 Accident detected! Triggering emergency alert...", Toast.LENGTH_LONG).show();

            // Trigger emergency alert in MainActivity
            Intent intent = new Intent(this, MainActivity.class);
            intent.setAction(MainActivity.ACTION_TRIGGER_EMERGENCY_ALERT);
            intent.putExtra("simulated_value", simulatedValue);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);

        } else {
            // No accident detected
            tvSimulationResult.setText(String.format(
                "✅ No Accident Detected\n\n" +
                "Test: %s\n" +
                "Value: %.2f m/s²\n" +
                "Threshold: %.1f m/s²\n" +
                "Result: %.2f < %.1f\n\n" +
                "Value is below threshold.",
                testName, simulatedValue, currentThreshold, simulatedValue, currentThreshold
            ));

            Toast.makeText(this, "✓ No accident - value below threshold", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnectESP32();
    }
}
