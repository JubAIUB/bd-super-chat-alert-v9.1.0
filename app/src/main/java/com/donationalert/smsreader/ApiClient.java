package com.donationalert.smsreader;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.*;

public class ApiClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    public static final String SITE_URL = "https://jubiplays.com";
    private static final String API = SITE_URL + "/wp-json/bdsca/v1";

    private final OkHttpClient client;
    private final Context ctx;

    public interface Cb { void onSuccess(String r); void onFailure(String e); }

    public ApiClient(Context c) {
        ctx = c.getApplicationContext();
        client = new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS).build();
    }

    private String key() { return ctx.getSharedPreferences("donation_settings", Context.MODE_PRIVATE).getString("api_key", ""); }

    public void sendDonation(SmsParser.DonationData d, Cb cb) {
        if (key().isEmpty()) { if(cb!=null) cb.onFailure("API Key not set."); return; }
        Request req = new Request.Builder().url(API+"/donate").post(RequestBody.create(d.toJson(), JSON))
                .addHeader("Content-Type","application/json").addHeader("X-Donation-Api-Key",key()).build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call c, IOException e) { if(cb!=null) cb.onFailure("Network: "+e.getMessage()); }
            @Override public void onResponse(Call c, Response r) throws IOException {
                String b=r.body()!=null?r.body().string():""; r.close();
                if(r.isSuccessful()) { if(cb!=null) cb.onSuccess(b); } else { if(cb!=null) cb.onFailure("Error "+r.code()); }
            }
        });
    }

    // GET sync - fetch settings from server
    public void syncSettings(Cb cb) {
        if (key().isEmpty()) { if(cb!=null) cb.onFailure("API Key not set."); return; }
        Request req = new Request.Builder().url(API+"/sync?_t="+System.currentTimeMillis()).get()
                .addHeader("X-Donation-Api-Key",key()).addHeader("Cache-Control","no-cache").build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call c, IOException e) { if(cb!=null) cb.onFailure("Network: "+e.getMessage()); }
            @Override public void onResponse(Call c, Response r) throws IOException {
                String b=r.body()!=null?r.body().string():""; r.close();
                if(r.isSuccessful()) { if(cb!=null) cb.onSuccess(b); }
                else if(r.code()==401) { if(cb!=null) cb.onFailure("API_KEY_INVALID"); }
                else { if(cb!=null) cb.onFailure("Error "+r.code()); }
            }
        });
    }

    // POST sync - update settings TO server (two-way)
    public void updateSettings(int alertDuration, float minAmount, Cb cb) {
        if (key().isEmpty()) { if(cb!=null) cb.onFailure("API Key not set."); return; }
        String json = "{\"alert_duration\":"+alertDuration+",\"min_amount\":"+minAmount+"}";
        Request req = new Request.Builder().url(API+"/sync?_t="+System.currentTimeMillis())
                .post(RequestBody.create(json, JSON))
                .addHeader("Content-Type","application/json").addHeader("X-Donation-Api-Key",key())
                .addHeader("Cache-Control","no-cache").build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call c, IOException e) { if(cb!=null) cb.onFailure("Network: "+e.getMessage()); }
            @Override public void onResponse(Call c, Response r) throws IOException {
                String b=r.body()!=null?r.body().string():""; r.close();
                if(r.isSuccessful()) { if(cb!=null) cb.onSuccess(b); }
                else if(r.code()==401) { if(cb!=null) cb.onFailure("API_KEY_INVALID"); }
                else { if(cb!=null) cb.onFailure("Error "+r.code()); }
            }
        });
    }

    public void testConnection(Cb cb) { syncSettings(cb); }
}
