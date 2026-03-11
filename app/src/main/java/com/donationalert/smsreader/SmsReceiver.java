package com.donationalert.smsreader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "DonationSmsReceiver";

    public interface OnDonationReceivedListener {
        void onDonationReceived(SmsParser.DonationData data);
    }

    private static OnDonationReceivedListener listener;

    public static void setOnDonationReceivedListener(OnDonationReceivedListener l) {
        listener = l;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;

        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        Object[] pdus = (Object[]) bundle.get("pdus");
        if (pdus == null) return;

        String format = bundle.getString("format");
        StringBuilder fullMessage = new StringBuilder();
        String senderAddress = null;

        for (Object pdu : pdus) {
            SmsMessage sms;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                sms = SmsMessage.createFromPdu((byte[]) pdu, format);
            } else {
                sms = SmsMessage.createFromPdu((byte[]) pdu);
            }
            if (sms != null) {
                if (senderAddress == null) senderAddress = sms.getOriginatingAddress();
                fullMessage.append(sms.getMessageBody());
            }
        }

        String sender = senderAddress != null ? senderAddress : "";
        String body = fullMessage.toString();

        Log.d(TAG, "SMS from: " + sender);

        SmsParser.DonationData donation = SmsParser.parse(sender, body);

        if (donation != null) {
            Log.d(TAG, "Parsed: " + donation.provider + " type=" + donation.smsType + " Tk" + donation.amount);

            // Only log "send" (money received) type in the app log
            // Other types are sent silently to the server
            boolean isSendType = "send".equals(donation.smsType);

            if (isSendType) {
                DonationLogger.logDonation(context, donation);
            }

            // Send ALL types to the server — plugin decides what to show
            ApiClient apiClient = new ApiClient(context);
            apiClient.sendDonation(donation, new ApiClient.Cb() {
                public void onSuccess(String response) {
                    Log.d(TAG, "Sent: " + response);
                    if (isSendType) {
                        DonationLogger.updateStatus(context, donation.transactionId, "sent");
                    }
                }

                public void onFailure(String error) {
                    Log.e(TAG, "Failed: " + error);
                    if (isSendType) {
                        DonationLogger.updateStatus(context, donation.transactionId, "failed");
                    }
                    RetryManager.queueForRetry(context, donation);
                }
            });

            // Only notify UI for "send" type (toast + log refresh)
            if (isSendType && listener != null) {
                listener.onDonationReceived(donation);
            }
        }
    }
}
