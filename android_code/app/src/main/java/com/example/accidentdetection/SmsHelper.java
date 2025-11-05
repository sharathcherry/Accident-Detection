package com.example.accidentdetection;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.telephony.SmsManager;
import android.util.Log;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class SmsHelper {

    private static final String TAG = "SmsHelper";
    private static final String SMS_SENT_ACTION = "SMS_SENT";
    private static final String SMS_DELIVERED_ACTION = "SMS_DELIVERED";

    private final Context context;
    private final SmsManager smsManager;
    private final SmsCallback callback;

    // To track the status of multiple messages
    private final AtomicInteger sentCount = new AtomicInteger(0);
    private final AtomicInteger deliveredCount = new AtomicInteger(0);
    private final AtomicInteger processedSentCount = new AtomicInteger(0);
    private final AtomicInteger processedDeliveredCount = new AtomicInteger(0);
    private int totalMessagesToSend = 0;
    private int requestCodeCounter = 0;
    private volatile boolean isCancelled = false;

    public interface SmsCallback {
        void onAllSmsSent(int total, int sent);
        void onAllSmsDelivered(int total, int delivered);
        void onSmsError(String errorMessage);
        void onSmsCancelled();
    }

    public SmsHelper(Context context, SmsCallback callback) {
        this.context = context;
        this.callback = callback;
        this.smsManager = SmsManager.getDefault();
        registerSmsReceivers();
    }

    public void cancelEmergencySms() {
        isCancelled = true;
        Log.d(TAG, "Emergency SMS sending cancelled by user");
        callback.onSmsCancelled();
    }

    public void sendEmergencySms(List<ContactItem> contacts, String message) {
        // Reset cancel flag
        isCancelled = false;

        if (contacts == null || contacts.isEmpty()) {
            Log.w(TAG, "No contacts provided for SMS.");
            callback.onSmsError("No emergency contacts configured.");
            return;
        }

        if (message == null || message.isEmpty()) {
            Log.w(TAG, "SMS message is empty.");
            callback.onSmsError("Emergency message is empty.");
            return;
        }

        // Reset counts for a new batch of messages
        sentCount.set(0);
        deliveredCount.set(0);
        totalMessagesToSend = 0;

        ArrayList<String> parts = smsManager.divideMessage(message);

        for (ContactItem contact : contacts) {
            if (contact != null && contact.phone != null && !contact.phone.isEmpty()) {
                totalMessagesToSend += parts.size(); // Each part is a message
                Log.d(TAG, "Attempting to send " + parts.size() + " parts to: " + contact.phone);

                ArrayList<PendingIntent> sentPIs = new ArrayList<>();
                ArrayList<PendingIntent> deliveredPIs = new ArrayList<>();

                for (int i = 0; i < parts.size(); i++) {
                    Intent sentIntent = new Intent(SMS_SENT_ACTION);
                    sentIntent.putExtra("phoneNumber", contact.phone);
                    sentIntent.putExtra("part", i);
                    sentPIs.add(PendingIntent.getBroadcast(context, 0, sentIntent, PendingIntent.FLAG_IMMUTABLE));

                    Intent deliveredIntent = new Intent(SMS_DELIVERED_ACTION);
                    deliveredIntent.putExtra("phoneNumber", contact.phone);
                    deliveredIntent.putExtra("part", i);
                    deliveredPIs.add(PendingIntent.getBroadcast(context, 0, deliveredIntent, PendingIntent.FLAG_IMMUTABLE));
                }

                try {
                    smsManager.sendMultipartTextMessage(contact.phone, null, parts, sentPIs, deliveredPIs);
                    Log.i(TAG, "SMS sent request handed off for: " + contact.phone + ". Total parts: " + parts.size());
                } catch (Exception e) {
                    Log.e(TAG, "Failed to hand off SMS for " + contact.phone + ": " + e.getMessage());
                    callback.onSmsError("Failed to send SMS to " + contact.phone + ": " + e.getMessage());
                    // Decrease totalMessagesToSend for failed send off
                    totalMessagesToSend -= parts.size();
                }
            } else {
                Log.w(TAG, "Skipping invalid contact for SMS.");
            }
        }

        if (totalMessagesToSend == 0) {
            callback.onSmsError("No valid messages to send after filtering contacts.");
        }
    }

    private void registerSmsReceivers() {
        ContextCompat.registerReceiver(context, smsSentReceiver, new IntentFilter(SMS_SENT_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED);
        ContextCompat.registerReceiver(context, smsDeliveredReceiver, new IntentFilter(SMS_DELIVERED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED);
        Log.d(TAG, "SMS BroadcastReceivers registered with RECEIVER_NOT_EXPORTED flag (using ContextCompat).");
    }

    public void unregisterSmsReceivers() {
        try {
            context.unregisterReceiver(smsSentReceiver);
            context.unregisterReceiver(smsDeliveredReceiver);
            Log.d(TAG, "SMS BroadcastReceivers unregistered.");
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Receivers not registered or already unregistered: " + e.getMessage());
        }
    }

    private final BroadcastReceiver smsSentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context arg0, Intent arg1) {
            String phoneNumber = arg1.getStringExtra("phoneNumber");
            int part = arg1.getIntExtra("part", -1);
            switch (getResultCode()) {
                case Activity.RESULT_OK:
                    sentCount.incrementAndGet();
                    Log.d(TAG, "SMS Sent (Part " + part + ") to: " + phoneNumber + " - SUCCESS");
                    break;
                case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                    Log.e(TAG, "SMS Sent (Part " + part + ") to: " + phoneNumber + " - Generic failure");
                    break;
                case SmsManager.RESULT_ERROR_NO_SERVICE:
                    Log.e(TAG, "SMS Sent (Part " + part + ") to: " + phoneNumber + " - No service");
                    break;
                case SmsManager.RESULT_ERROR_NULL_PDU:
                    Log.e(TAG, "SMS Sent (Part " + part + ") to: " + phoneNumber + " - Null PDU");
                    break;
                case SmsManager.RESULT_ERROR_RADIO_OFF:
                    Log.e(TAG, "SMS Sent (Part " + part + ") to: " + phoneNumber + " - Radio off");
                    break;
            }
            // Check if all parts for all messages have been processed
            if (sentCount.get() + deliveredCount.get() >= totalMessagesToSend) {
                callback.onAllSmsSent(totalMessagesToSend, sentCount.get());
            }
        }
    };

    private final BroadcastReceiver smsDeliveredReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context arg0, Intent arg1) {
            String phoneNumber = arg1.getStringExtra("phoneNumber");
            int part = arg1.getIntExtra("part", -1);
            switch (getResultCode()) {
                case Activity.RESULT_OK:
                    deliveredCount.incrementAndGet();
                    Log.d(TAG, "SMS Delivered (Part " + part + ") to: " + phoneNumber + " - SUCCESS");
                    break;
                case Activity.RESULT_CANCELED:
                    Log.e(TAG, "SMS Delivered (Part " + part + ") to: " + phoneNumber + " - FAILED / CANCELED");
                    break;
            }
            // Check if all parts for all messages have been processed
            if (sentCount.get() + deliveredCount.get() >= totalMessagesToSend) {
                callback.onAllSmsDelivered(totalMessagesToSend, deliveredCount.get());
            }
        }
    };
}
