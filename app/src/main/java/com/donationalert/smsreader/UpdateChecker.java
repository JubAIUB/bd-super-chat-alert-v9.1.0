package com.donationalert.smsreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Auto-update checker.
 * Checks jubiplays.com/apk/version.json on app launch.
 * If newer version exists, shows download prompt and installs APK.
 */
public class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String VERSION_URL = "https://jubiplays.com/apk/version.json";
    private static final String AUTHORITY = "com.donationalert.smsreader.fileprovider";

    /**
     * Call from MainActivity.onCreate() — runs in background, shows dialog on UI thread if update found.
     */
    public static void check(Activity activity) {
        new Thread(() -> {
            try {
                // Fetch version.json
                URL url = new URL(VERSION_URL + "?_t=" + System.currentTimeMillis());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Cache-Control", "no-cache");

                if (conn.getResponseCode() != 200) {
                    Log.d(TAG, "Version check failed: HTTP " + conn.getResponseCode());
                    return;
                }

                // Read response
                InputStream is = conn.getInputStream();
                StringBuilder sb = new StringBuilder();
                byte[] buf = new byte[1024];
                int len;
                while ((len = is.read(buf)) != -1) {
                    sb.append(new String(buf, 0, len));
                }
                is.close();
                conn.disconnect();

                JSONObject json = new JSONObject(sb.toString());
                int serverVersionCode = json.getInt("version_code");
                String serverVersionName = json.getString("version_name");
                String apkUrl = json.getString("apk_url");
                String changelog = json.optString("changelog", "Bug fixes and improvements.");

                // Get current app version code
                int currentVersionCode;
                try {
                    currentVersionCode = activity.getPackageManager()
                            .getPackageInfo(activity.getPackageName(), 0).versionCode;
                } catch (Exception e) {
                    currentVersionCode = 0;
                }

                Log.d(TAG, "Current: " + currentVersionCode + " Server: " + serverVersionCode);

                if (serverVersionCode > currentVersionCode) {
                    // Show update dialog on UI thread
                    final String finalApkUrl = apkUrl;
                    final String finalVersionName = serverVersionName;
                    final String finalChangelog = changelog;
                    new Handler(Looper.getMainLooper()).post(() ->
                            showUpdateDialog(activity, finalVersionName, finalChangelog, finalApkUrl)
                    );
                }

            } catch (Exception e) {
                Log.d(TAG, "Version check error: " + e.getMessage());
                // Silently fail — don't bother user if check fails
            }
        }).start();
    }

    private static void showUpdateDialog(Activity activity, String versionName, String changelog, String apkUrl) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        new AlertDialog.Builder(activity)
                .setTitle("🔄 Update Available — v" + versionName)
                .setMessage(changelog + "\n\nDownload and install now?")
                .setPositiveButton("Update", (d, w) -> downloadAndInstall(activity, apkUrl))
                .setNegativeButton("Later", null)
                .setCancelable(true)
                .show();
    }

    private static void downloadAndInstall(Activity activity, String apkUrl) {
        ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle("Downloading Update");
        progress.setMessage("Please wait...");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            File apkFile = null;
            try {
                URL url = new URL(apkUrl + "?_t=" + System.currentTimeMillis());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("Cache-Control", "no-cache");

                if (conn.getResponseCode() != 200) {
                    throw new Exception("Download failed: HTTP " + conn.getResponseCode());
                }

                int totalSize = conn.getContentLength();

                // Save to app's cache directory
                apkFile = new File(activity.getCacheDir(), "update.apk");
                InputStream is = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(apkFile);
                byte[] buf = new byte[8192];
                int len;
                long downloaded = 0;

                while ((len = is.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                    downloaded += len;
                    if (totalSize > 0) {
                        int percent = (int) (downloaded * 100 / totalSize);
                        new Handler(Looper.getMainLooper()).post(() -> progress.setProgress(percent));
                    }
                }

                fos.close();
                is.close();
                conn.disconnect();

                // Trigger install
                final File finalApk = apkFile;
                new Handler(Looper.getMainLooper()).post(() -> {
                    progress.dismiss();
                    installApk(activity, finalApk);
                });

            } catch (Exception e) {
                Log.e(TAG, "Download failed: " + e.getMessage());
                final File cleanupFile = apkFile;
                new Handler(Looper.getMainLooper()).post(() -> {
                    progress.dismiss();
                    Toast.makeText(activity, "❌ Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    if (cleanupFile != null && cleanupFile.exists()) cleanupFile.delete();
                });
            }
        }).start();
    }

    private static void installApk(Activity activity, File apkFile) {
        try {
            Uri apkUri = FileProvider.getUriForFile(activity, AUTHORITY, apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Install failed: " + e.getMessage());
            Toast.makeText(activity, "❌ Install failed. Please install manually from Downloads.", Toast.LENGTH_LONG).show();
        }
    }
}
