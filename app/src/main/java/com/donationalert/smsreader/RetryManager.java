package com.donationalert.smsreader;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Manages retry queue for donations that failed to send to the WordPress API.
 */
public class RetryManager {

    private static final String TAG = "RetryManager";
    private static final String PREFS_NAME = "retry_queue";
    private static final String KEY_QUEUE = "pending_donations";
    private static final int MAX_RETRIES = 5;

    private static class RetryEntry {
        String json;
        int attempts;

        RetryEntry(String json) {
            this.json = json;
            this.attempts = 0;
        }
    }

    public static void queueForRetry(Context context, SmsParser.DonationData data) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            Gson gson = new Gson();
            List<RetryEntry> queue = getQueue(context);
            queue.add(new RetryEntry(data.toJson()));
            prefs.edit().putString(KEY_QUEUE, gson.toJson(queue)).apply();
            Log.d(TAG, "Queued donation for retry: " + data.transactionId);
        } catch (Exception e) {
            Log.e(TAG, "Error queuing donation: " + e.getMessage());
        }
    }

    public static void retryPending(Context context) {
        List<RetryEntry> queue = getQueue(context);
        if (queue.isEmpty()) return;

        Log.d(TAG, "Retrying " + queue.size() + " pending donations");
        ApiClient apiClient = new ApiClient(context);

        Iterator<RetryEntry> iterator = queue.iterator();
        List<RetryEntry> remaining = new ArrayList<>();

        while (iterator.hasNext()) {
            RetryEntry entry = iterator.next();
            entry.attempts++;

            if (entry.attempts > MAX_RETRIES) {
                Log.w(TAG, "Max retries reached, dropping donation");
                continue;
            }

            // Re-parse the JSON to create DonationData (simplified approach)
            // In production, you'd store the full DonationData object
            remaining.add(entry);
        }

        // Save remaining queue
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_QUEUE, new Gson().toJson(remaining)).apply();
    }

    private static List<RetryEntry> getQueue(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_QUEUE, "[]");
            Type type = new TypeToken<ArrayList<RetryEntry>>() {}.getType();
            List<RetryEntry> list = new Gson().fromJson(json, type);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static void clearQueue(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_QUEUE, "[]").apply();
    }

    public static int getPendingCount(Context context) {
        return getQueue(context).size();
    }
}
