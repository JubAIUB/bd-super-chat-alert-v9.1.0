package com.donationalert.smsreader;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Logs donations locally for history display and debugging.
 */
public class DonationLogger {

    private static final String TAG = "DonationLogger";
    private static final String PREFS_NAME = "donation_log";
    private static final String KEY_DONATIONS = "donations";
    private static final int MAX_LOG_SIZE = 200;

    public static class LogEntry {
        public String provider;
        public String amount;
        public String maskedNumber;
        public String transactionId;
        public String reference;
        public String status; // "pending", "sent", "failed"
        public String timestamp;

        public LogEntry(SmsParser.DonationData data) {
            this.provider = data.provider;
            this.amount = data.amount;
            this.maskedNumber = data.maskedNumber;
            this.transactionId = data.transactionId;
            this.reference = data.reference;
            this.status = "pending";
            this.timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date(data.timestamp));
        }
    }

    public static void logDonation(Context context, SmsParser.DonationData data) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            Gson gson = new Gson();

            List<LogEntry> entries = getLog(context);
            entries.add(0, new LogEntry(data));

            // Keep only the latest entries
            if (entries.size() > MAX_LOG_SIZE) {
                entries = entries.subList(0, MAX_LOG_SIZE);
            }

            prefs.edit().putString(KEY_DONATIONS, gson.toJson(entries)).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error logging donation: " + e.getMessage());
        }
    }

    public static List<LogEntry> getLog(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_DONATIONS, "[]");
            Gson gson = new Gson();
            Type type = new TypeToken<ArrayList<LogEntry>>() {}.getType();
            List<LogEntry> list = gson.fromJson(json, type);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            Log.e(TAG, "Error reading log: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public static void updateStatus(Context context, String transactionId, String status) {
        try {
            List<LogEntry> entries = getLog(context);
            for (LogEntry entry : entries) {
                if (entry.transactionId != null && entry.transactionId.equals(transactionId)) {
                    entry.status = status;
                    break;
                }
            }
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_DONATIONS, new Gson().toJson(entries)).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error updating status: " + e.getMessage());
        }
    }

    public static void clearLog(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_DONATIONS, "[]").apply();
    }
}
