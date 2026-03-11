package com.donationalert.smsreader;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements SmsReceiver.OnDonationReceivedListener {

    private EditText etApiKey, etDuration, etMinAmount;
    private Button btnConnect, btnSaveSettings, btnTestSms, btnClearLog;
    private Switch switchService;
    private TextView tvPerm, tvStatus, tvUsername, tvObsUrl, tvTotal, tvLog;
    private SharedPreferences prefs;
    private Handler mainHandler, syncHandler;
    private Runnable syncRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mainHandler = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences("donation_settings", MODE_PRIVATE);

        etApiKey = findViewById(R.id.et_api_key);
        etDuration = findViewById(R.id.et_duration);
        etMinAmount = findViewById(R.id.et_min_amount);
        btnConnect = findViewById(R.id.btn_connect);
        btnSaveSettings = findViewById(R.id.btn_save_settings);
        btnTestSms = findViewById(R.id.btn_test_sms);
        btnClearLog = findViewById(R.id.btn_clear_log);
        switchService = findViewById(R.id.switch_service);
        tvPerm = findViewById(R.id.tv_perm);
        tvStatus = findViewById(R.id.tv_status);
        tvUsername = findViewById(R.id.tv_username);
        tvObsUrl = findViewById(R.id.tv_obs_url);
        tvTotal = findViewById(R.id.tv_total);
        tvLog = findViewById(R.id.tv_log);

        etApiKey.setText(prefs.getString("api_key", ""));
        switchService.setChecked(prefs.getBoolean("service_enabled", false));
        loadCached();

        btnConnect.setOnClickListener(v -> connect());
        btnSaveSettings.setOnClickListener(v -> saveSettingsToServer());
        btnTestSms.setOnClickListener(v -> sendTest());
        btnClearLog.setOnClickListener(v -> { DonationLogger.clearLog(this); refreshLog(); Toast.makeText(this,"Cleared",Toast.LENGTH_SHORT).show(); });

        switchService.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("service_enabled", on).apply();
            Intent i = new Intent(this, SmsListenerService.class);
            if (on) { if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.O) startForegroundService(i); else startService(i); }
            else stopService(i);
        });

        requestPerms();
        SmsReceiver.setOnDonationReceivedListener(this);
        updatePerm(); refreshLog();

        // Auto sync every 60s
        syncHandler = new Handler(Looper.getMainLooper());
        syncRunnable = () -> { if (!prefs.getString("api_key","").isEmpty()) syncFromServer(); syncHandler.postDelayed(syncRunnable, 60000); };
        if (!prefs.getString("api_key","").isEmpty()) syncFromServer();

        // Check for app updates
        UpdateChecker.check(this);
    }

    @Override protected void onResume() {
        super.onResume(); SmsReceiver.setOnDonationReceivedListener(this);
        updatePerm(); refreshLog(); syncHandler.postDelayed(syncRunnable, 60000);
    }
    @Override protected void onPause() { super.onPause(); syncHandler.removeCallbacks(syncRunnable); }

    private void connect() {
        String key = etApiKey.getText().toString().trim();
        if (key.isEmpty()) { etApiKey.setError("Enter API Key"); return; }
        prefs.edit().putString("api_key", key).putString("site_url", ApiClient.SITE_URL).apply();
        tvStatus.setText("Connecting..."); tvStatus.setTextColor(0xFFAAAACC);
        syncFromServer();
    }

    private void syncFromServer() {
        new ApiClient(this).syncSettings(new ApiClient.Cb() {
            @Override public void onSuccess(String r) {
                mainHandler.post(() -> {
                    try {
                        JSONObject j = new JSONObject(r);
                        String un=j.optString("username","");
                        String ou=j.optString("overlay_url","");
                        int ad=j.optInt("alert_duration",5);
                        double ma=j.optDouble("min_amount",0);
                        double td=j.optDouble("total_donations",0);
                        int tc=j.optInt("total_count",0);

                        prefs.edit().putString("s_username",un).putString("s_overlay_url",ou)
                                .putInt("s_alert_duration",ad).putFloat("s_min_amount",(float)ma)
                                .putFloat("s_total",(float)td).putInt("s_count",tc)
                                .putBoolean("api_valid",true).apply();
                        loadCached();
                        tvStatus.setText("\u2705 Connected: " + un);
                        tvStatus.setTextColor(0xFF4CAF50);
                    } catch (Exception e) {
                        tvStatus.setText("\u2705 Connected"); tvStatus.setTextColor(0xFF4CAF50);
                    }
                });
            }
            @Override public void onFailure(String e) {
                mainHandler.post(() -> {
                    if ("API_KEY_INVALID".equals(e)) {
                        tvStatus.setText("\u274C API Key invalid/expired! Get new key from admin.");
                        prefs.edit().putBoolean("api_valid",false).apply();
                    } else tvStatus.setText("\u274C " + e);
                    tvStatus.setTextColor(0xFFF44336);
                });
            }
        });
    }

    private void saveSettingsToServer() {
        int dur; float min;
        try { dur = Integer.parseInt(etDuration.getText().toString()); } catch(Exception e) { dur = 5; }
        try { min = Float.parseFloat(etMinAmount.getText().toString()); } catch(Exception e) { min = 0; }
        dur = Math.max(3, Math.min(30, dur));
        min = Math.max(0, min);

        tvStatus.setText("Saving settings...");
        int finalDur = dur; float finalMin = min;
        new ApiClient(this).updateSettings(dur, min, new ApiClient.Cb() {
            @Override public void onSuccess(String r) {
                mainHandler.post(() -> {
                    try {
                        JSONObject j = new JSONObject(r);
                        prefs.edit().putInt("s_alert_duration", j.optInt("alert_duration", finalDur))
                                .putFloat("s_min_amount", (float)j.optDouble("min_amount", finalMin)).apply();
                        loadCached();
                        tvStatus.setText("\u2705 Settings saved & synced!"); tvStatus.setTextColor(0xFF4CAF50);
                        Toast.makeText(MainActivity.this, "Settings saved!", Toast.LENGTH_SHORT).show();
                    } catch(Exception e) {
                        tvStatus.setText("\u2705 Saved"); tvStatus.setTextColor(0xFF4CAF50);
                    }
                });
            }
            @Override public void onFailure(String e) {
                mainHandler.post(() -> { tvStatus.setText("\u274C Save failed: "+e); tvStatus.setTextColor(0xFFF44336); });
            }
        });
    }

    private void loadCached() {
        tvUsername.setText(prefs.getString("s_username","Not connected"));
        tvObsUrl.setText(prefs.getString("s_overlay_url","Connect to see"));
        etDuration.setText(String.valueOf(prefs.getInt("s_alert_duration",5)));
        etMinAmount.setText(String.valueOf(prefs.getFloat("s_min_amount",0f)));
        float t = prefs.getFloat("s_total",0f);
        int c = prefs.getInt("s_count",0);
        tvTotal.setText("\u09F3" + String.format("%.2f",t) + " (" + c + " donations)");
    }

    private void sendTest() {
        SmsParser.DonationData d = new SmsParser.DonationData();
        d.provider="bKash"; d.amount="100.00"; d.senderNumber="01712345678";
        d.maskedNumber="********678"; d.transactionId="TEST"+System.currentTimeMillis();
        d.reference="Test donation"; d.smsType="send";
        d.timestamp=System.currentTimeMillis();
        tvStatus.setText("Sending test...");
        new ApiClient(this).sendDonation(d, new ApiClient.Cb() {
            @Override public void onSuccess(String r) {
                mainHandler.post(() -> {
                    tvStatus.setText("\u2705 Test sent!"); tvStatus.setTextColor(0xFF4CAF50);
                    DonationLogger.logDonation(MainActivity.this,d);
                    DonationLogger.updateStatus(MainActivity.this,d.transactionId,"sent");
                    refreshLog(); syncFromServer();
                });
            }
            @Override public void onFailure(String e) {
                mainHandler.post(() -> { tvStatus.setText("\u274C "+e); tvStatus.setTextColor(0xFFF44336); });
            }
        });
    }

    private void updatePerm() {
        boolean ok = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)==PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED;
        tvPerm.setText(ok?"\u2705 SMS permissions granted":"\u274C SMS permissions required! Tap to grant.");
        tvPerm.setTextColor(ok?0xFF4CAF50:0xFFF44336);
        if (!ok) tvPerm.setOnClickListener(v->requestPerms());
    }

    private void refreshLog() {
        List<DonationLogger.LogEntry> entries = DonationLogger.getLog(this);
        if (entries.isEmpty()) { tvLog.setText("No donations yet.\nWaiting for bKash/Nagad SMS..."); return; }
        StringBuilder sb = new StringBuilder();
        for (DonationLogger.LogEntry e : entries) {
            String ic = "sent".equals(e.status)?"\u2705":"failed".equals(e.status)?"\u274C":"\u23F3";
            sb.append(ic).append(" [").append(e.provider).append("] \u09F3").append(e.amount).append(" from ").append(e.maskedNumber);
            if (e.reference!=null&&!e.reference.isEmpty()) sb.append("\n   ").append(e.reference);
            sb.append("\n   ").append(e.timestamp).append("\n\n");
        }
        tvLog.setText(sb.toString());
    }

    private void requestPerms() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.RECEIVE_SMS); p.add(Manifest.permission.READ_SMS);
        if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU) p.add(Manifest.permission.POST_NOTIFICATIONS);
        List<String> n = new ArrayList<>();
        for (String s:p) if(ContextCompat.checkSelfPermission(this,s)!=PackageManager.PERMISSION_GRANTED) n.add(s);
        if (!n.isEmpty()) ActivityCompat.requestPermissions(this, n.toArray(new String[0]), 100);
    }

    @Override public void onRequestPermissionsResult(int r, @NonNull String[] p, @NonNull int[] g) { super.onRequestPermissionsResult(r,p,g); updatePerm(); }
    @Override public void onDonationReceived(SmsParser.DonationData d) {
        mainHandler.post(() -> { Toast.makeText(this,"Donation: \u09F3"+d.amount+" via "+d.provider,Toast.LENGTH_LONG).show(); refreshLog(); syncFromServer(); });
    }
}
