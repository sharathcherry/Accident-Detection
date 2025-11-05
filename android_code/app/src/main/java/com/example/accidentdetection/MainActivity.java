package com.example.accidentdetection;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import android.util.Log; // Added for logging
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements LocationListener, SmsHelper.SmsCallback {

    private static final String TAG = "MainActivity";

    // UI Elements
    private TextView tvBluetoothStatus, tvStatus, tvLocation, tvEmergencyState;
    private MaterialButton btnConnectBluetooth, btnAddContact, btnManualAlert, btnTestingMode; // Added btnTestingMode
    private View statusIndicator;
    private LinearLayout contactsContainer, emptyStateView;

    // Contact list
    private List<ContactItem> contactsList = new ArrayList<>();

    // Bluetooth
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private InputStream inputStream;
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String ESP32_NAME = "ESP32_AccidentDetector";
    private boolean isConnected = false;
    private volatile boolean stopWorker = false;

    // Location
    private LocationManager locationManager;
    private double currentLatitude = 0.0;
    private double currentLongitude = 0.0;

    // SharedPreferences
    private SharedPreferences sharedPreferences;
    private static final String PREFS_NAME = "AccidentAlertPrefs";
    private static final String CONTACTS_KEY = "emergency_contacts";

    // Permissions
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int REQ_ENABLE_BT = 2001;

    // Dialog
    private Dialog emergencyDialog;
    private TextView tvSmsStatusInDialog;

    // SMS Helper
    private SmsHelper smsHelper;

    // --- ML classifier and cooldown ---
    private AccidentClassifier classifier = null;
    private long lastAlertTimeMillis = 0L;
    private static final long ALERT_COOLDOWN_MS = 30_000L; // 30 seconds
    public static final String ACTION_TRIGGER_EMERGENCY_ALERT = "com.example.ad1.TRIGGER_EMERGENCY_ALERT";

    // Single-send guard and reset handler
    private volatile boolean emergencySent = false;
    private static final long RESET_TIMEOUT_MS = 5 * 60_000L; // 5 minutes auto-reset
    private final Handler resetHandler = new Handler(Looper.getMainLooper());
    private Runnable resetRunnable = null;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeUI();
        requestPermissions();

        // Bluetooth Setup
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            showModernToast("Bluetooth not supported");
            finish();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            requestEnableBluetooth();
        }

        // Location Setup
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        startLocationUpdates();

        // SharedPreferences
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        loadSavedContacts();

        setupButtonListeners();
        startPulseAnimation();

        // ML Classifier
        classifier = new AccidentClassifier(this);
        if (classifier.isMlAvailable()) {
            tvStatus.setText("✓ ML Model Loaded - AI Detection Active");
            Log.i(TAG, "ML model loaded successfully - using AI-based accident detection.");
        } else {
            tvStatus.setText("✓ Threshold Detection Active");
            Log.w(TAG, "ML model failed to load - using threshold-based fallback detection.");
        }

        // SMS Helper initialization
        smsHelper = new SmsHelper(this, this);

        // Check if the activity was launched by the testing intent
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && ACTION_TRIGGER_EMERGENCY_ALERT.equals(intent.getAction())) {
            double simulatedValue = intent.getDoubleExtra("simulated_value", 0.0);
            Log.i(TAG, "Emergency alert triggered from TestingActivity with simulated value: " + simulatedValue);
            // Use single-send guarded trigger so testing also respects single-message behavior
            triggerEmergencyIfNeeded();
        }
    }

    private void initializeUI() {
        tvBluetoothStatus = findViewById(R.id.tvBluetoothStatus);
        tvStatus = findViewById(R.id.tvStatus);
        tvLocation = findViewById(R.id.tvLocation);
        tvEmergencyState = findViewById(R.id.tvEmergencyState);
        btnConnectBluetooth = findViewById(R.id.btnConnectBluetooth);
        btnAddContact = findViewById(R.id.btnAddContact);
        btnManualAlert = findViewById(R.id.btnManualAlert);
        btnTestingMode = findViewById(R.id.btnTestingMode); // Find the new button
        contactsContainer = findViewById(R.id.contactsContainer);
        emptyStateView = findViewById(R.id.emptyStateView);
        statusIndicator = findViewById(R.id.statusIndicator);
    }

    private void setupButtonListeners() {
        btnConnectBluetooth.setOnClickListener(v -> {
            if (!isConnected) connectToESP32();
            else disconnectBluetooth();
        });

        btnAddContact.setOnClickListener(v -> {
            animateButton(v);
            showAddEditContactDialog(null);
        });

        btnManualAlert.setOnClickListener(v -> {
            animateButton(v);
            // Manual alert always bypasses the guard - it's for emergencies!
            Log.d(TAG, "Manual alert button pressed - sending immediately.");
            sendEmergencyAlert();
        });

        btnTestingMode.setOnClickListener(v -> {
            animateButton(v);
            Intent intent = new Intent(MainActivity.this, TestingActivity.class);
            startActivity(intent);
        });
    }

    private void showAddEditContactDialog(final ContactItem existingContact) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_add_edit_contact);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.setCancelable(true);

        TextView tvDialogTitle = dialog.findViewById(R.id.tvDialogTitle);
        Spinner spinner = dialog.findViewById(R.id.spinnerContactType);
        TextInputEditText etPhone = dialog.findViewById(R.id.etContactPhone);
        TextInputEditText etName = dialog.findViewById(R.id.etContactName);
        MaterialButton btnCancel = dialog.findViewById(R.id.btnCancel);
        MaterialButton btnSave = dialog.findViewById(R.id.btnSaveContact);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, R.array.contact_types, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        if (existingContact != null) {
            tvDialogTitle.setText("Edit Contact");
            btnSave.setText("Save Changes");
            etName.setText(existingContact.name);
            etPhone.setText(existingContact.phone);
            spinner.setSelection(existingContact.type);
        } else {
            tvDialogTitle.setText("Add Emergency Contact");
            btnSave.setText("Add Contact");
        }

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnSave.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            String phone = etPhone.getText().toString().trim();
            int type = spinner.getSelectedItemPosition();

            if (phone.isEmpty()) {
                showModernToast("Phone number cannot be empty.");
                return;
            }

            if (existingContact != null) {
                existingContact.name = name;
                existingContact.phone = phone;
                existingContact.type = type;
                updateContactView(existingContact);
            } else {
                ContactItem newContact = new ContactItem(name, phone, type);
                contactsList.add(newContact);
                addContactView(newContact);
            }
            saveContacts();
            updateContactCount();
            dialog.dismiss();
        });

        dialog.show();
    }

    private void addContactView(final ContactItem contact) {
        LayoutInflater inflater = LayoutInflater.from(this);
        View contactView = inflater.inflate(R.layout.item_contact, contactsContainer, false);
        contact.view = contactView;

        updateContactView(contact);

        ImageButton btnEdit = contactView.findViewById(R.id.btnEditContact);
        ImageButton btnDelete = contactView.findViewById(R.id.btnDeleteContact);

        btnEdit.setOnClickListener(v -> showAddEditContactDialog(contact));
        btnDelete.setOnClickListener(v -> {
            contactsContainer.removeView(contact.view);
            contactsList.remove(contact);
            updateContactCount();
            saveContacts();
            showModernToast("Contact removed");
        });

        contactsContainer.addView(contactView);
    }

    private void updateContactView(ContactItem contact) {
        if (contact.view == null) return;
        TextView tvName = contact.view.findViewById(R.id.tvContactNameDisplay);
        TextView tvPhone = contact.view.findViewById(R.id.tvContactPhoneDisplay);

        String displayName = contact.name.isEmpty() ? "Emergency Contact" : contact.name;
        tvName.setText(displayName);
        tvPhone.setText(contact.phone);
    }

    private void updateContactCount() {
        emptyStateView.setVisibility(contactsList.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void saveContacts() {
        try {
            JSONArray jsonArray = new JSONArray();
            for (ContactItem contact : contactsList) {
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("name", contact.name);
                jsonObject.put("phone", contact.phone);
                jsonObject.put("type", contact.type);
                jsonArray.put(jsonObject);
            }
            sharedPreferences.edit().putString(CONTACTS_KEY, jsonArray.toString()).apply();
            Log.d(TAG, "Contacts saved successfully.");
        } catch (JSONException e) {
            Log.e(TAG, "Failed to save contacts: " + e.getMessage());
            showModernToast("Failed to save contacts");
        }
    }

    private void loadSavedContacts() {
        contactsContainer.removeAllViews();
        contactsList.clear();
        String contactsJson = sharedPreferences.getString(CONTACTS_KEY, "[]");

        try {
            JSONArray jsonArray = new JSONArray(contactsJson);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                ContactItem contact = new ContactItem(
                        obj.getString("name"),
                        obj.getString("phone"),
                        obj.getInt("type")
                );
                contactsList.add(contact);
                addContactView(contact);
            }
            Log.d(TAG, "Contacts loaded successfully. Count: " + contactsList.size());
        } catch (JSONException e) {
            Log.e(TAG, "Failed to load contacts: " + e.getMessage());
        }
        updateContactCount();
    }

    public void sendEmergencyAlert() {
        Log.d(TAG, "Attempting to send emergency alert...");
        if (contactsList.isEmpty()) {
            showModernToast("⚠ No emergency contacts configured");
            Log.w(TAG, "No emergency contacts configured. Alert not sent.");
            // Show a more prominent message
            runOnUiThread(() -> {
                if (tvStatus != null) {
                    tvStatus.setText("⚠ Add emergency contacts first!");
                }
            });
            return;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            showModernToast("Location permission required");
            Log.w(TAG, "Location permission not granted. Alert not sent.");
            runOnUiThread(() -> {
                if (tvStatus != null) {
                    tvStatus.setText("⚠ Location permission required!");
                }
            });
            return;
        }

        Location location = null;
        try {
            location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission denied or manager issue: " + e.getMessage());
        }

        if (location != null) {
            currentLatitude = location.getLatitude();
            currentLongitude = location.getLongitude();
            Log.d(TAG, "Current location: " + currentLatitude + ", " + currentLongitude);
        } else {
            Log.w(TAG, "Last known location is null. Using default 0,0.");
        }

        String message = "🚨 ACCIDENT DETECTED! 🚨\n\n" +
                "Emergency assistance needed.\n\n" +
                "📍 Location: " +
                "https://maps.google.com/?q=" + currentLatitude + "," + currentLongitude;

        smsHelper.sendEmergencySms(contactsList, message);
        showEmergencyDialog();
    }

    private void showEmergencyDialog() {
        if (emergencyDialog != null && emergencyDialog.isShowing()) {
            emergencyDialog.dismiss();
        }

        emergencyDialog = new Dialog(this);
        emergencyDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        emergencyDialog.setContentView(R.layout.dialog_emergency_alert);
        emergencyDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        emergencyDialog.setCancelable(false);

        TextView tvAlertLocation = emergencyDialog.findViewById(R.id.tvAlertLocation);
        tvSmsStatusInDialog = emergencyDialog.findViewById(R.id.tvSmsStatus);
        MaterialButton btnCloseDialog = emergencyDialog.findViewById(R.id.btnCloseDialog);
        MaterialButton btnCancelAlert = emergencyDialog.findViewById(R.id.btnCancelAlert);

        tvAlertLocation.setText("📍 Location: " + String.format("%.4f, %.4f", currentLatitude, currentLongitude));
        tvSmsStatusInDialog.setText("✉️ Sending SMS to " + contactsList.size() + " contacts...");
        btnCloseDialog.setVisibility(View.GONE);

        // Cancel button - visible by default, hides when SMS is complete
        btnCancelAlert.setOnClickListener(v -> {
            smsHelper.cancelEmergencySms();
            tvSmsStatusInDialog.setText("⚠️ Emergency alert cancelled");
            btnCancelAlert.setVisibility(View.GONE);
            btnCloseDialog.setVisibility(View.VISIBLE);
            Log.d(TAG, "User cancelled emergency alert");
        });

        btnCloseDialog.setOnClickListener(v -> {
            emergencyDialog.dismiss();
            emergencyDialog = null;
        });

        emergencyDialog.show();
        Log.d(TAG, "Emergency dialog shown.");
    }

    @Override
    public void onAllSmsSent(int total, int sent) {
        runOnUiThread(() -> {
            if (tvSmsStatusInDialog != null) {
                String status = String.format("✉️ Sent %d of %d messages.", sent, total);
                tvSmsStatusInDialog.setText(status);
                Log.d(TAG, "SMS Sent Callback: " + status);
            }
        });
    }

    @Override
    public void onAllSmsDelivered(int total, int delivered) {
        runOnUiThread(() -> {
            if (tvSmsStatusInDialog != null) {
                String status = String.format("✓ Delivered %d of %d messages.", delivered, total);
                tvSmsStatusInDialog.setText(status);
                if (emergencyDialog != null) {
                    MaterialButton btnCloseDialog = emergencyDialog.findViewById(R.id.btnCloseDialog);
                    MaterialButton btnCancelAlert = emergencyDialog.findViewById(R.id.btnCancelAlert);
                    if (btnCloseDialog != null) {
                        btnCloseDialog.setVisibility(View.VISIBLE);
                    }
                    if (btnCancelAlert != null) {
                        btnCancelAlert.setVisibility(View.GONE);
                    }
                }
                Log.d(TAG, "SMS Delivered Callback: " + status + ". Close button now visible.");
            }
        });
    }

    @Override
    public void onSmsCancelled() {
        runOnUiThread(() -> {
            Log.d(TAG, "SMS Cancelled Callback received");
            showModernToast("Emergency alert cancelled");
            // If user cancels SMS sending, allow future alerts
            resetEmergencyState();
        });
    }

    @Override
    public void onSmsError(String errorMessage) {
        runOnUiThread(() -> {
            if (tvSmsStatusInDialog != null) {
                String status = "✗ SMS Error: " + errorMessage;
                tvSmsStatusInDialog.setText(status);
                if (emergencyDialog != null) {
                    MaterialButton btnCloseDialog = emergencyDialog.findViewById(R.id.btnCloseDialog);
                    if (btnCloseDialog != null) {
                        btnCloseDialog.setVisibility(View.VISIBLE);
                    }
                }
            }
            showModernToast("SMS Error: " + errorMessage);
            Log.e(TAG, "SMS Error Callback: " + errorMessage);
            // Allow retrying after error
            resetEmergencyState();
        });
    }

    private void requestPermissions() {
        String[] permissions = {
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.SEND_SMS
        };

        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
                break;
            }
        }
    }

    private boolean hasBtConnectPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBtConnectPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBtConnectPermission()) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                    PERMISSION_REQUEST_CODE
            );
        }
    }

    private void requestEnableBluetooth() {
        try {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Bluetooth CONNECT permission not granted for enable request.");
                return;
            }
            startActivityForResult(enableBtIntent, REQ_ENABLE_BT);
        } catch (Exception e) {
            Log.e(TAG, "Unable to prompt Bluetooth enable: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ENABLE_BT) {
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                showModernToast("Bluetooth enabled");
            } else {
                showModernToast("Bluetooth is required to connect");
            }
        }
    }

    private void connectToESP32() {
        Log.d(TAG, "Attempting to connect to ESP32...");
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            requestEnableBluetooth();
            return;
        }

        if (!hasBtConnectPermission()) {
            requestBtConnectPermissionIfNeeded();
            return;
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
            showModernToast("Missing Bluetooth permission");
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
            showModernToast("ESP32 not paired. Pair it in system Bluetooth settings.");
            return;
        }

        final BluetoothDevice target = esp32Device;
        btnConnectBluetooth.setEnabled(false);
        tvBluetoothStatus.setText("Connecting...");

        new Thread(() -> {
            try {
                bluetoothSocket = target.createRfcommSocketToServiceRecord(MY_UUID);
                bluetoothSocket.connect();
                inputStream = bluetoothSocket.getInputStream();
                isConnected = true;
                runOnUiThread(() -> {
                    updateConnectionStatus(true);
                    showModernToast("✓ Connected to ESP32");
                    btnConnectBluetooth.setEnabled(true);
                });
                beginListenForData();
            } catch (IOException | SecurityException e) {
                Log.e(TAG, "Bluetooth connection failed: " + e.getMessage());
                safeCloseSocket();
                runOnUiThread(() -> {
                    updateConnectionStatus(false);
                    showModernToast("Connection failed");
                    btnConnectBluetooth.setEnabled(true);
                });
            }
        }).start();
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

    private void disconnectBluetooth() {
        stopWorker = true;
        safeCloseSocket();
        updateConnectionStatus(false);
        showModernToast("Disconnected from ESP32");
    }

    private void updateConnectionStatus(boolean connected) {
        if (connected) {
            tvBluetoothStatus.setText("✓ Connected to ESP32");
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_connected);
            btnConnectBluetooth.setText("Disconnect");
            btnConnectBluetooth.setBackgroundColor(getColor(R.color.text_secondary));
        } else {
            tvBluetoothStatus.setText("✗ Disconnected from ESP32");
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_disconnected);
            btnConnectBluetooth.setText("Connect to ESP32");
            btnConnectBluetooth.setBackgroundColor(getColor(R.color.primary_blue));
        }
    }

    private void beginListenForData() {
        stopWorker = false;
        Thread workerThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && !stopWorker) {
                try {
                    if (inputStream != null) {
                        int bytesAvailable = inputStream.available();
                        if (bytesAvailable > 0) {
                            byte[] packetBytes = new byte[bytesAvailable];
                            inputStream.read(packetBytes);
                            String data = new String(packetBytes, "UTF-8");
                            Log.v(TAG, "Received Bluetooth data: " + data);

                            if (data.contains("ACCIDENT_DETECTED")) {
                                Log.i(TAG, "ACCIDENT_DETECTED signal received from ESP32.");
                                // Use guarded trigger to ensure only one emergency alert is sent per incident
                                runOnUiThread(this::triggerEmergencyIfNeeded);
                            }

                            parseSensorData(data);
                        }
                    }
                } catch (IOException e) {
                    stopWorker = true;
                    Log.e(TAG, "Error reading Bluetooth data, stopping worker: " + e.getMessage());
                }
            }
            Log.d(TAG, "Bluetooth data listener stopped.");
        });
        workerThread.start();
    }

    private void parseSensorData(String data) {
        runOnUiThread(() -> {
            try {
                float accel = Float.NaN;
                float gyro = Float.NaN;

                if (data.contains("ACCEL:")) {
                    String accelValue = data.substring(data.indexOf("ACCEL:") + 6).trim();
                    accel = Float.parseFloat(accelValue);
                }
                if (data.contains("GYRO:")) {
                    String gyroValue = data.substring(data.indexOf("GYRO:") + 5).trim();
                    gyro = Float.parseFloat(gyroValue);
                }

                if (classifier != null) {
                    if (classifier.isMlAvailable()) {
                        tvStatus.setText("✓ ML Model Active - Analyzing...");
                    } else {
                        tvStatus.setText("✓ Threshold Detection Active");
                    }

                    if (!Float.isNaN(accel) && !Float.isNaN(gyro)) {
                        final float fAccel = accel;
                        final float fGyro = gyro;
                        new Thread(() -> {
                            float prob = classifier.predict(fAccel, fGyro);
                            if (prob >= 0.7f) {
                                String detectionType = classifier.isMlAvailable() ? "ML" : "Threshold";
                                Log.i(TAG, detectionType + " detected accident! Accel: " + fAccel + ", Gyro: " + fGyro + ", Probability: " + prob);
                                // Uses single-send guard to prevent duplicate alerts
                                runOnUiThread(this::triggerEmergencyIfNeeded);
                            }
                        }).start();
                    }
                } else {
                    tvStatus.setText("✓ System Active - Monitoring...");
                }

            } catch (Exception e) {
                Log.e(TAG, "Error in parseSensorData: " + e.getMessage());
            }
        });
    }

    private void handleAccidentDetection() {
        // Backwards compatibility: route to guarded trigger
        Log.d(TAG, "handleAccidentDetection triggered.");
        triggerEmergencyIfNeeded();
    }

    private synchronized void triggerEmergencyIfNeeded() {
        long now = System.currentTimeMillis();
        Log.d(TAG, "triggerEmergencyIfNeeded called. emergencySent=" + emergencySent);

        // Only check the guard for automatic triggers (ESP32/ML), not manual button
        if (emergencySent) {
            Log.d(TAG, "Emergency already sent from automatic trigger; ignoring subsequent automatic trigger.");
            return;
        }

        // Mark as sent and schedule auto-reset
        emergencySent = true;
        lastAlertTimeMillis = now;
        if (resetRunnable != null) {
            resetHandler.removeCallbacks(resetRunnable);
        }
        resetRunnable = () -> {
            synchronized (MainActivity.this) {
                emergencySent = false;
                resetRunnable = null;
                Log.d(TAG, "Emergency state auto-reset after timeout.");
                updateEmergencyStateUI();
            }
        };
        resetHandler.postDelayed(resetRunnable, RESET_TIMEOUT_MS);

        Log.i(TAG, "Triggering emergency alert.");
        updateEmergencyStateUI();
        sendEmergencyAlert();
    }

    private synchronized void resetEmergencyState() {
        if (resetRunnable != null) {
            resetHandler.removeCallbacks(resetRunnable);
            resetRunnable = null;
        }
        emergencySent = false;
        Log.d(TAG, "Emergency state reset.");
        updateEmergencyStateUI();
    }

    private void updateEmergencyStateUI() {
        runOnUiThread(() -> {
            if (tvEmergencyState != null) {
                if (emergencySent) {
                    long timeRemaining = RESET_TIMEOUT_MS - (System.currentTimeMillis() - lastAlertTimeMillis);
                    int minutesRemaining = (int) (timeRemaining / 60_000);
                    tvEmergencyState.setText("⏳ Alert sent — resets in ~" + minutesRemaining + "m");
                    tvEmergencyState.setAlpha(1.0f);
                } else {
                    tvEmergencyState.setText("✓ Ready to send alerts");
                    tvEmergencyState.setAlpha(0.85f);
                }
            }
        });
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 10, this);
    }

    private void startPulseAnimation() {
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(statusIndicator, "scaleX", 1f, 1.3f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(statusIndicator, "scaleY", 1f, 1.3f, 1f);
        scaleX.setDuration(1500);
        scaleY.setDuration(1500);
        scaleX.setRepeatCount(ObjectAnimator.INFINITE);
        scaleY.setRepeatCount(ObjectAnimator.INFINITE);
        scaleX.setInterpolator(new AccelerateDecelerateInterpolator());
        scaleY.setInterpolator(new AccelerateDecelerateInterpolator());
        scaleX.start();
        scaleY.start();
    }

    private void animateButton(View view) {
        ObjectAnimator.ofFloat(view, "scaleX", 1f, 0.95f, 1f).setDuration(200).start();
        ObjectAnimator.ofFloat(view, "scaleY", 1f, 0.95f, 1f).setDuration(200).start();
    }

    private void showModernToast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        currentLatitude = location.getLatitude();
        currentLongitude = location.getLongitude();
        tvLocation.setText(String.format("GPS: %.4f, %.4f", currentLatitude, currentLongitude));
    }

    @Override
    public void onProviderEnabled(@NonNull String provider) {
        showModernToast("GPS Enabled");
    }

    @Override
    public void onProviderDisabled(@NonNull String provider) {
        showModernToast("GPS Disabled - Please enable location");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnectBluetooth();
        if (emergencyDialog != null && emergencyDialog.isShowing()) {
            emergencyDialog.dismiss();
        }
        if (classifier != null) {
            classifier.close();
        }
        if (locationManager != null) {
            locationManager.removeUpdates(this);
        }
        if (smsHelper != null) {
            smsHelper.unregisterSmsReceivers();
        }
        // Clean up any pending reset callbacks
        if (resetRunnable != null) {
            resetHandler.removeCallbacks(resetRunnable);
            resetRunnable = null;
        }
    }

}
