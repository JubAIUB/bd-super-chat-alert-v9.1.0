package com.donationalert.smsreader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * Restarts the foreground service after device boot.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "DonationBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences prefs = context.getSharedPreferences("donation_settings", Context.MODE_PRIVATE);
            boolean serviceEnabled = prefs.getBoolean("service_enabled", false);

            if (serviceEnabled) {
                Log.d(TAG, "Boot completed - restarting SMS listener service");
                Intent serviceIntent = new Intent(context, SmsListenerService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }
        }
    }
}
