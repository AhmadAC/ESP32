// app\src\main\java\com\example\mybasicapp\HttpPollingService.java
package com.example.mybasicapp;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.core.app.NotificationCompat; 

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class HttpPollingService extends Service {

    private static final String TAG = "HttpPollingService_DBG";
    public static final String ACTION_START_FOREGROUND_SERVICE = "com.example.mybasicapp.ACTION_START_HTTP_FG_SERVICE";
    public static final String ACTION_STOP_FOREGROUND_SERVICE = "com.example.mybasicapp.ACTION_STOP_HTTP_FG_SERVICE";
    public static final String ACTION_START_POLLING = "com.example.mybasicapp.ACTION_START_POLLING"; 
    public static final String ACTION_STOP_POLLING = "com.example.mybasicapp.ACTION_STOP_POLLING";
    public static final String EXTRA_BASE_URL = "EXTRA_BASE_URL"; 

    public static final String ACTION_STATUS_UPDATE = "com.example.mybasicapp.ACTION_HTTP_STATUS_UPDATE";
    public static final String EXTRA_STATUS = "EXTRA_STATUS";
    public static final String ACTION_DATA_RECEIVED = "com.example.mybasicapp.ACTION_HTTP_DATA_RECEIVED";
    public static final String EXTRA_DATA_TYPE = "EXTRA_DATA_TYPE"; 
    public static final String EXTRA_DATA_JSON_STRING = "EXTRA_DATA_JSON_STRING";

    private static final String NOTIFICATION_CHANNEL_ID_SERVICE = "http_polling_service_status_channel";
    private static final String NOTIFICATION_CHANNEL_ID_MESSAGES = "esp32_http_notifications"; 
    private static final int SERVICE_NOTIFICATION_ID = 2; 
    private static final int MESSAGE_NOTIFICATION_ID_OFFSET = 1000; 

    private OkHttpClient httpClient;
    private final Handler pollingHandler = new Handler(Looper.getMainLooper());
    private boolean isServiceRunningAsForeground = false;
    private boolean isCurrentlyPolling = false;
    private String currentTargetBaseUrl; 

    private static final String DATA_ENDPOINT = "/"; 
    private static final String DATA_TYPE_MIC = "mic_data"; 

    private static final long POLLING_INTERVAL_MS = 2500; 

    private static final String PREFS_NAME = "MrCooperESP_Prefs"; 
    private static final String PREF_NOTIFICATIONS_ENABLED = "notifications_enabled"; 
    private static final String PREF_CUSTOM_ALERT_SOUND_URI = "custom_alert_sound_uri";
    private static final String PREF_CUSTOM_ALERT_SOUND_ENABLED = "custom_alert_sound_enabled";

    private static final String PREFS_HOME_FRAGMENT = "HomeFragmentPrefs_v2";
    private static final String PREF_APP_ALERT_LEVEL_DB = "app_alert_level_db";
    private static final String PREF_APP_ALERTS_ENABLED = "app_alerts_enabled"; 
    
    private static final int DEFAULT_APP_ALERT_THRESHOLD_DB = 70; 
    private static final boolean DEFAULT_NOTIFICATIONS_ENABLED = false;
    private static final boolean DEFAULT_CUSTOM_SOUND_ENABLED = true;

    private SharedPreferences serviceControlPrefs; 
    private SharedPreferences appAlertSettingsPrefs; 

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate: Service Creating");
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS) 
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
        createNotificationChannel(NOTIFICATION_CHANNEL_ID_SERVICE, getString(R.string.http_polling_service_channel_name), NotificationManager.IMPORTANCE_LOW);
        createNotificationChannel(NOTIFICATION_CHANNEL_ID_MESSAGES, getString(R.string.channel_name_http_alerts), NotificationManager.IMPORTANCE_HIGH);

        serviceControlPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        appAlertSettingsPrefs = getSharedPreferences(PREFS_HOME_FRAGMENT, Context.MODE_PRIVATE);

        Log.d(TAG, "onCreate: Service Created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            Log.w(TAG, "onStartCommand: Null intent or action. Flags=" + flags + ", StartId=" + startId);
            if (isServiceRunningAsForeground && currentTargetBaseUrl != null) {
                Log.i(TAG, "Service restarted, attempting to resume polling for: " + currentTargetBaseUrl);
                startPollingData(); 
            } else if (!isServiceRunningAsForeground && currentTargetBaseUrl != null) {
                startForegroundServiceWithNotification("Service Resuming Polling " + getHostFromUrl(currentTargetBaseUrl));
                startPollingData();
            }
            return START_STICKY; 
        }

        String action = intent.getAction();
        Log.i(TAG, "onStartCommand: Action='" + action + "', TargetURL='" + intent.getStringExtra(EXTRA_BASE_URL) + "', CurrentTarget='" + currentTargetBaseUrl + "'");

        switch (action) {
            case ACTION_START_FOREGROUND_SERVICE:
            case ACTION_START_POLLING: 
                String newTargetUrl = intent.getStringExtra(EXTRA_BASE_URL);
                if (newTargetUrl == null || newTargetUrl.isEmpty()){
                    Log.e(TAG, action + ": Base URL is missing! Stopping service or ignoring.");
                    sendBroadcastStatus("Error: Base URL missing for service start/poll.");
                    if (isCurrentlyPolling) {
                        stopPollingData();
                    }
                    if (isServiceRunningAsForeground) { 
                        updateServiceNotification("Service Active (Idle - No Target)");
                    }
                    return START_STICKY;
                }

                if (!newTargetUrl.toLowerCase().startsWith("http://") && !newTargetUrl.toLowerCase().startsWith("https://")) {
                    newTargetUrl = "http://" + newTargetUrl;
                }

                if (!Objects.equals(currentTargetBaseUrl, newTargetUrl) && isCurrentlyPolling) {
                    Log.i(TAG, "Target URL changed from " + currentTargetBaseUrl + " to " + newTargetUrl + ". Stopping old poll.");
                    stopPollingData(); 
                }
                currentTargetBaseUrl = newTargetUrl; 

                if (!isServiceRunningAsForeground) {
                    startForegroundServiceWithNotification("Polling " + getHostFromUrl(currentTargetBaseUrl));
                } else {
                     updateServiceNotification("Polling " + getHostFromUrl(currentTargetBaseUrl));
                }
                startPollingData(); 
                break;

            case ACTION_STOP_POLLING:
                Log.d(TAG, "onStartCommand: Handling ACTION_STOP_POLLING.");
                stopPollingData();
                if (isServiceRunningAsForeground) { 
                    updateServiceNotification("Polling Paused for " + getHostFromUrl(currentTargetBaseUrl));
                }
                break;

            case ACTION_STOP_FOREGROUND_SERVICE:
                Log.d(TAG, "onStartCommand: Handling ACTION_STOP_FOREGROUND_SERVICE.");
                stopServiceAndForeground(); 
                return START_NOT_STICKY; 

            default:
                Log.w(TAG, "onStartCommand: Unhandled action: " + action);
                break;
        }
        return START_STICKY; 
    }

    private String getHostFromUrl(String urlString) {
        if (urlString == null) return "Unknown Host";
        try {
            java.net.URL url = new java.net.URL(urlString);
            String host = url.getHost();
            int port = url.getPort();
            if (port != -1 && port != url.getDefaultPort()) {
                return host + ":" + port;
            }
            return host;
        } catch (java.net.MalformedURLException e) {
            return urlString.replaceFirst("^(http://|https://)", "");
        }
    }

    private void startPollingData() {
        if (currentTargetBaseUrl == null || currentTargetBaseUrl.isEmpty()) {
            Log.e(TAG, "startPollingData: Cannot start, target base URL is not set.");
            sendBroadcastStatus("Error: Target URL not set for polling.");
            return;
        }
        if (!isCurrentlyPolling) {
            isCurrentlyPolling = true;
            pollingHandler.post(pollingRunnable); 
            Log.i(TAG, "startPollingData: Polling started for " + currentTargetBaseUrl);
            sendBroadcastStatus("Polling started for " + getHostFromUrl(currentTargetBaseUrl));
            if(isServiceRunningAsForeground) { 
                updateServiceNotification("Polling active: " + getHostFromUrl(currentTargetBaseUrl));
            }
        } else {
            Log.d(TAG, "startPollingData: Polling already active for " + currentTargetBaseUrl);
            if(isServiceRunningAsForeground) {
                updateServiceNotification("Polling active: " + getHostFromUrl(currentTargetBaseUrl));
            }
        }
    }

    private void stopPollingData() {
        if (isCurrentlyPolling) {
            isCurrentlyPolling = false;
            pollingHandler.removeCallbacks(pollingRunnable);
            Log.i(TAG, "stopPollingData: Polling stopped for " + currentTargetBaseUrl);
            sendBroadcastStatus("Polling stopped for " + getHostFromUrl(currentTargetBaseUrl));
        }
    }

    private final Runnable pollingRunnable = new Runnable() {
        @Override
        public void run() {
            if (isCurrentlyPolling && currentTargetBaseUrl != null && !currentTargetBaseUrl.isEmpty()) {
                fetchDataFromServer(currentTargetBaseUrl, DATA_ENDPOINT, DATA_TYPE_MIC);
                pollingHandler.postDelayed(this, POLLING_INTERVAL_MS);
            } else {
                isCurrentlyPolling = false;
                Log.d(TAG, "PollingRunnable: Stopping as isCurrentlyPolling is false or currentTargetBaseUrl is null.");
            }
        }
    };

    private void fetchDataFromServer(String baseUrl, String endpoint, final String dataType) {
        String url = baseUrl + (endpoint.equals("/") && baseUrl.endsWith("/") ? "" : endpoint);
        Log.d(TAG, "HTTP Polling: GET " + url + " for dataType: " + dataType);

        Request request = new Request.Builder().url(url).build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "HTTP poll " + url + " onFailure: " + e.getMessage());
                sendBroadcastStatus("Error polling " + getHostFromUrl(baseUrl) + ": " + e.getMessage().substring(0, Math.min(e.getMessage().length(), 50)));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                final String responseBodyString = response.body() != null ? response.body().string() : null;
                final int responseCode = response.code();

                if (response.isSuccessful() && responseBodyString != null) {
                    Log.d(TAG, "HTTP poll " + url + " onResponse (" + responseCode + "): " + responseBodyString.substring(0, Math.min(responseBodyString.length(), 100)));
                    sendBroadcastData(dataType, responseBodyString); 

                    if (DATA_TYPE_MIC.equals(dataType)) {
                        try {
                            JSONObject json = new JSONObject(responseBodyString);
                            double dbCalibrated = json.optDouble("db_calibrated", -999.0);
                            String espError = json.optString("error", null);

                            boolean appNotificationsEnabled = appAlertSettingsPrefs.getBoolean(PREF_APP_ALERTS_ENABLED, DEFAULT_NOTIFICATIONS_ENABLED);
                            int appAlertThresholdDb = appAlertSettingsPrefs.getInt(PREF_APP_ALERT_LEVEL_DB, DEFAULT_APP_ALERT_THRESHOLD_DB);

                            if (appNotificationsEnabled && (espError == null || espError.isEmpty() || "null".equalsIgnoreCase(espError)) && dbCalibrated >= appAlertThresholdDb && dbCalibrated != -999.0) {
                                String notificationMsg = String.format(Locale.getDefault(),
                                        "Loud Noise: %.1f dB detected on %s (App Alert >= %d dB)",
                                        dbCalibrated, getHostFromUrl(baseUrl), appAlertThresholdDb);
                                int notificationId = MESSAGE_NOTIFICATION_ID_OFFSET + Math.abs(getHostFromUrl(baseUrl).hashCode() % 1000);
                                showDataNotification("Loud Noise Alert!", notificationMsg, notificationId);

                                boolean customSoundEnabled = serviceControlPrefs.getBoolean(PREF_CUSTOM_ALERT_SOUND_ENABLED, DEFAULT_CUSTOM_SOUND_ENABLED);
                                String customSoundUriString = serviceControlPrefs.getString(PREF_CUSTOM_ALERT_SOUND_URI, null);
                                String customSoundFileName = "None";
                                if (customSoundUriString != null) {
                                     customSoundFileName = getFileNameFromContentUri(Uri.parse(customSoundUriString));
                                }

                                logSensorTriggerToFile(String.format(Locale.getDefault(),
                                        "App-Side Alert: ESP(%s) Level: %.1f dB (Threshold: >= %d dB). Visual notification shown. Custom sound: %s (Enabled: %b, URI Set: %b)",
                                        getHostFromUrl(baseUrl), dbCalibrated, appAlertThresholdDb, customSoundFileName, customSoundEnabled, (customSoundUriString != null)));

                                if (customSoundEnabled && customSoundUriString != null) {
                                    Uri soundUri = Uri.parse(customSoundUriString);
                                    Intent alertSoundIntent = new Intent(HttpPollingService.this, AlertSoundService.class);
                                    alertSoundIntent.setAction(AlertSoundService.ACTION_PLAY_CUSTOM_SOUND);
                                    alertSoundIntent.putExtra(AlertSoundService.EXTRA_SOUND_URI, soundUri.toString());
                                    alertSoundIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                    
                                    // SAFELY START FOREGROUND SERVICE TO PREVENT ANDROID 12+ CRASHES
                                    try {
                                        ContextCompat.startForegroundService(HttpPollingService.this, alertSoundIntent);
                                        Log.i(TAG, "Custom alert sound service started for: " + customSoundFileName);
                                    } catch (Exception e_fg) {
                                        Log.e(TAG, "Foreground start failed (app in background?), falling back to normal start", e_fg);
                                        try {
                                            startService(alertSoundIntent);
                                        } catch (Exception e_bg) {
                                            Log.e(TAG, "Failed to start sound service entirely", e_bg);
                                        }
                                    }
                                }
                            }
                        } catch (JSONException e_json) {
                             Log.e(TAG, "Error parsing " + dataType + " JSON for app-side notification: " + e_json.getMessage());
                        }
                    }

                } else {
                    Log.e(TAG, "HTTP poll " + url + " onResponse Error: " + responseCode + " - " + response.message());
                    sendBroadcastStatus("Error polling " + getHostFromUrl(baseUrl) + ": " + responseCode);
                }
            }
        });
    }

    private String getFileNameFromContentUri(Uri uri) {
        if (uri == null) return "Unknown URI";
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (displayNameIndex != -1) {
                        result = cursor.getString(displayNameIndex);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting file name from content URI: " + uri, e);
            }
        }
        if (result == null) {
            result = uri.getPath();
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) {
                    result = result.substring(cut + 1);
                }
            } else {
                result = uri.toString();
            }
        }
        return result != null ? result : "File";
    }

    private void logSensorTriggerToFile(String message) {
        File logFile = new File(getFilesDir(), getString(R.string.log_sensor_trigger_file_name));
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        String logEntry = "[" + timestamp + "] " + message + "\n";

        try (FileOutputStream fos = new FileOutputStream(logFile, true); 
             OutputStreamWriter osw = new OutputStreamWriter(fos)) {
            osw.write(logEntry);
            Log.i(TAG, "Logged to sensor trigger file: " + message.substring(0, Math.min(message.length(),100)));
        } catch (IOException e) {
            Log.e(TAG, "Error writing to sensor trigger log file: " + logFile.getAbsolutePath(), e);
        }
    }


    @SuppressLint("MissingPermission")
    private void startForegroundServiceWithNotification(String statusText) {
        Log.d(TAG, "startForegroundServiceWithNotification: statusText='" + statusText + "'");
        Intent notificationIntent = new Intent(this, MainActivity.class); 
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_SERVICE)
                .setContentTitle(getString(R.string.app_name) + " " + getString(R.string.http_sync_notification_title_suffix))
                .setContentText(statusText)
                .setSmallIcon(R.drawable.ic_stat_service) 
                .setContentIntent(pendingIntent)
                .setOngoing(true) 
                .setPriority(NotificationCompat.PRIORITY_LOW) 
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(SERVICE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(SERVICE_NOTIFICATION_ID, notification);
            }
            isServiceRunningAsForeground = true;
            Log.i(TAG, "startForegroundServiceWithNotification: Service started in foreground. Notification: '" + statusText + "'");
        } catch (Exception e) { 
            Log.e(TAG, "startForegroundServiceWithNotification: Error starting foreground: " + e.getClass().getSimpleName() + " - " + e.getMessage(), e);
            isServiceRunningAsForeground = false;
        }
    }

    @SuppressLint("MissingPermission")
    private void updateServiceNotification(String text) {
        Log.d(TAG, "updateServiceNotification: text='" + text + "'. isServiceRunningAsForeground=" + isServiceRunningAsForeground);
        if (!isServiceRunningAsForeground) { 
            Log.w(TAG, "updateServiceNotification: Not in foreground, cannot update notification.");
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
             Log.e(TAG, "updateServiceNotification: NotificationManager is null.");
            return;
        }
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_SERVICE)
                .setContentTitle(getString(R.string.app_name) + " " + getString(R.string.http_sync_notification_title_suffix))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_service)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
        try {
            manager.notify(SERVICE_NOTIFICATION_ID, notification);
            Log.d(TAG, "Service notification updated: " + text);
        } catch (Exception e) {
            Log.e(TAG, "updateServiceNotification: Error notifying: " + e.getMessage(), e);
        }
    }

    private void stopServiceAndForeground() {
        Log.i(TAG, "stopServiceAndForeground: Initiated.");
        stopPollingData(); 
        if (isServiceRunningAsForeground) {
            Log.d(TAG, "stopServiceAndForeground: Stopping foreground state now.");
            stopForeground(true); 
            isServiceRunningAsForeground = false;
        }
        currentTargetBaseUrl = null; 
        stopSelf(); 
        sendBroadcastStatus("Service stopped.");
        Log.i(TAG, "stopServiceAndForeground: Service instance stopped and foreground state removed.");
    }

    private void sendBroadcastStatus(String status) {
        Intent intent = new Intent(ACTION_STATUS_UPDATE);
        intent.putExtra(EXTRA_STATUS, status);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        Log.v(TAG, "sendBroadcastStatus >> UI: " + status);
    }

    private void sendBroadcastData(String dataType, String jsonString) {
        Intent intent = new Intent(ACTION_DATA_RECEIVED);
        intent.putExtra(EXTRA_DATA_TYPE, dataType);
        intent.putExtra(EXTRA_DATA_JSON_STRING, jsonString);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        Log.v(TAG, "sendBroadcastData (" + dataType + ") >> UI: " + jsonString.substring(0, Math.min(jsonString.length(),100)));
    }

    private void createNotificationChannel(String channelId, String channelName, int importance) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, channelName, importance);
            if (NOTIFICATION_CHANNEL_ID_MESSAGES.equals(channelId)) {
                channel.setDescription(getString(R.string.channel_description_http_alerts)); 
                channel.enableLights(true);
                channel.enableVibration(true); 
            } else if (NOTIFICATION_CHANNEL_ID_SERVICE.equals(channelId)) {
                 channel.setDescription(getString(R.string.http_polling_service_channel_desc));
            }
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
                Log.d(TAG, "createNotificationChannel: Channel '" + channelId + "' ("+channelName+") created/updated.");
            } else {
                Log.e(TAG, "createNotificationChannel: NotificationManager is null for channel '" + channelId + "'");
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void showDataNotification(String title, String message, int notificationId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "showDataNotification: POST_NOTIFICATIONS permission NOT granted. Cannot show alert notification.");
                return;
            }
        }

        Intent intent = new Intent(this, MainActivity.class); 
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP); 
        PendingIntent pendingIntent = PendingIntent.getActivity(this, notificationId, intent, 
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message) 
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH) 
            .setAutoCancel(true) 
            .setContentIntent(pendingIntent)
            .setDefaults(Notification.DEFAULT_ALL); 
        
        NotificationManagerCompat.from(this).notify(notificationId, builder.build()); 
        Log.i(TAG, "showDataNotification: Sent. Title='" + title + "', ID=" + notificationId);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind: Called, returning null as this is not a bound service.");
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy: Service Destroying. Current target: " + currentTargetBaseUrl);
        stopPollingData(); 
        if (isServiceRunningAsForeground) { 
            stopForeground(true);
            isServiceRunningAsForeground = false;
        }
        if (httpClient != null) {
            Log.d(TAG, "onDestroy: Shutting down OkHttpClient dispatcher and connection pool.");
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
            try {
                if (httpClient.cache() != null) {
                    httpClient.cache().close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing OkHttp cache during onDestroy", e);
            }
        }
        sendBroadcastStatus("Service destroyed.");
        Log.i(TAG, "onDestroy: Service fully destroyed.");
        super.onDestroy();
    }
}