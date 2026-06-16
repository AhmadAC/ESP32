// app\src\main\java\com\example\mybasicapp\fragments\HomeFragment.java
package com.example.mybasicapp.fragments;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver; 
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import android.provider.OpenableColumns;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.example.mybasicapp.AlertSoundService; 
import com.example.mybasicapp.HttpPollingService;
import com.example.mybasicapp.R;
import com.example.mybasicapp.viewmodels.AppViewModel;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

public class HomeFragment extends Fragment {
    private static final String TAG = "HomeFragment_DBG";

    private AppViewModel appViewModel;
    private TextView textViewHomeStatusTitle, textViewMicData, textViewAppAlertLevelValue;
    private SwitchMaterial switchEnableMicMonitoring, switchEnableAppAlerts;
    private SeekBar seekBarAppAlertLevel;

    private String currentActiveEspIpForFragment = null;
    private boolean isPollingThisEsp = false; 

    private static final String PREFS_HOME_FRAGMENT = "HomeFragmentPrefs_v2"; 
    private static final String PREF_APP_ALERT_LEVEL_DB = "app_alert_level_db";
    private static final String PREF_APP_ALERTS_ENABLED = "app_alerts_enabled";

    private static final String SERVICE_PREFS_NAME = "MrCooperESP_Prefs"; 
    private static final String PREF_CUSTOM_ALERT_SOUND_URI = "custom_alert_sound_uri";
    private static final String PREF_CUSTOM_ALERT_SOUND_ENABLED = "custom_alert_sound_enabled";

    private SharedPreferences homeFragmentPrefs; 
    private SharedPreferences serviceSharedPrefs;  

    private Button buttonSelectCustomSound, buttonTestCustomSound;
    private TextView textViewSelectedCustomSound;
    private SwitchMaterial switchEnableCustomSoundAlert; 
    private Uri currentCustomSoundUriForPicker = null; 
    private MediaPlayer localTestMediaPlayer;
    private ActivityResultLauncher<Intent> selectCustomSoundLauncher;
    private ActivityResultLauncher<String> requestStoragePermissionLauncher;


    public HomeFragment() {
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appViewModel = new ViewModelProvider(requireActivity()).get(AppViewModel.class);
        homeFragmentPrefs = requireActivity().getSharedPreferences(PREFS_HOME_FRAGMENT, Context.MODE_PRIVATE);
        serviceSharedPrefs = requireActivity().getSharedPreferences(SERVICE_PREFS_NAME, Context.MODE_PRIVATE);

        selectCustomSoundLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (!isAdded() || getContext() == null) return; // Null safety 
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        try {
                            final int takeFlags = result.getData().getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                            requireContext().getContentResolver().takePersistableUriPermission(uri, takeFlags);
                            currentCustomSoundUriForPicker = uri;
                            saveCustomSoundUriToServicePrefs(currentCustomSoundUriForPicker); 
                            updateCustomSoundDisplayUI();
                            Toast.makeText(getContext(), getString(R.string.custom_sound_selected_toast, getFileNameFromUri(currentCustomSoundUriForPicker)), Toast.LENGTH_SHORT).show();
                        } catch (SecurityException e) {
                            Log.e(TAG, "Failed to take persistable URI permission for custom sound", e);
                            Toast.makeText(getContext(), "Failed to get permanent access to sound file.", Toast.LENGTH_LONG).show();
                            currentCustomSoundUriForPicker = null;
                            saveCustomSoundUriToServicePrefs(null); 
                            updateCustomSoundDisplayUI();
                        }
                    }
                }
            });

        requestStoragePermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (!isAdded() || getContext() == null) return; // Null safety
                if (isGranted) {
                    Log.d(TAG, "Storage permission granted for custom sound selection.");
                    openCustomSoundPicker();
                } else {
                    Toast.makeText(getContext(), R.string.storage_permission_required_toast, Toast.LENGTH_LONG).show();
                }
            });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        textViewHomeStatusTitle = view.findViewById(R.id.textViewHomeStatusTitle);
        textViewMicData = view.findViewById(R.id.textViewMicData);
        switchEnableMicMonitoring = view.findViewById(R.id.switchEnableMicMonitoring);
        seekBarAppAlertLevel = view.findViewById(R.id.seekBarAppAlertLevel);
        textViewAppAlertLevelValue = view.findViewById(R.id.textViewAppAlertLevelValue);
        switchEnableAppAlerts = view.findViewById(R.id.switchEnableAppAlerts);

        buttonSelectCustomSound = view.findViewById(R.id.buttonSelectCustomSound);
        textViewSelectedCustomSound = view.findViewById(R.id.textViewSelectedCustomSound);
        buttonTestCustomSound = view.findViewById(R.id.buttonTestCustomSound);
        switchEnableCustomSoundAlert = view.findViewById(R.id.switchEnableCustomSoundAlert); 

        loadFragmentSettings(); 
        setupUIListeners();
            
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        appViewModel.getActiveEspAddressLiveData().observe(getViewLifecycleOwner(), address -> {
            if (!isAdded() || getContext() == null) return;
            String oldAddress = currentActiveEspIpForFragment;
            currentActiveEspIpForFragment = address; 

            if (address != null && !address.isEmpty()) {
                textViewHomeStatusTitle.setText(getString(R.string.home_status_active_esp, getHostFromUrlSafe(address)));
                switchEnableMicMonitoring.setEnabled(true);
                if (switchEnableMicMonitoring.isChecked()) {
                    startOrUpdateMicMonitoring(address);
                }
            } else {
                textViewHomeStatusTitle.setText(R.string.home_status_no_active_esp);
                textViewMicData.setText(R.string.mic_data_no_active_esp);
                switchEnableMicMonitoring.setEnabled(false);
                switchEnableMicMonitoring.setChecked(false); 
                if (isPollingThisEsp) { 
                    stopMicMonitoringService();
                }
            }
        });

        appViewModel.getLastServiceStatusLiveData().observe(getViewLifecycleOwner(), status -> {
            if (!isAdded() || getContext() == null) return;
            if (status != null && currentActiveEspIpForFragment != null) {
                 if (status.toLowerCase().contains("polling started for " + getHostFromUrlSafe(currentActiveEspIpForFragment).toLowerCase())) {
                    isPollingThisEsp = true;
                    textViewHomeStatusTitle.setText(getString(R.string.home_status_polling, getHostFromUrlSafe(currentActiveEspIpForFragment)));
                } else if (status.toLowerCase().contains("polling stopped") || status.toLowerCase().contains("service stopped")) {
                    isPollingThisEsp = false;
                    textViewHomeStatusTitle.setText(getString(R.string.home_status_paused, getHostFromUrlSafe(currentActiveEspIpForFragment)));
                }
                switchEnableMicMonitoring.setChecked(isPollingThisEsp);
            } else if (currentActiveEspIpForFragment == null) {
                isPollingThisEsp = false;
                switchEnableMicMonitoring.setChecked(false);
            }
        });

        appViewModel.getLastSensorJsonDataLiveData().observe(getViewLifecycleOwner(), jsonData -> {
            if (!isAdded() || getContext() == null) return;
            if (jsonData != null && !jsonData.isEmpty()) {
                processAndDisplaySensorData(jsonData);
            } else if (currentActiveEspIpForFragment != null) {
                textViewMicData.setText(R.string.mic_data_waiting);
            } else {
                textViewMicData.setText(R.string.mic_data_no_active_esp);
            }
        });
    }

    private void loadFragmentSettings() {
        if (!isAdded() || getContext() == null) return;
        int alertLevel = homeFragmentPrefs.getInt(PREF_APP_ALERT_LEVEL_DB, 70); 
        boolean alertsEnabled = homeFragmentPrefs.getBoolean(PREF_APP_ALERTS_ENABLED, false);
        seekBarAppAlertLevel.setProgress(alertLevel);
        textViewAppAlertLevelValue.setText(getString(R.string.decibel_format, alertLevel));
        switchEnableAppAlerts.setChecked(alertsEnabled);

        String soundUriString = serviceSharedPrefs.getString(PREF_CUSTOM_ALERT_SOUND_URI, null);
        if (soundUriString != null) {
            currentCustomSoundUriForPicker = Uri.parse(soundUriString);
            try {
                if (getContext() != null) {
                    getContext().getContentResolver().openInputStream(currentCustomSoundUriForPicker).close();
                }
            } catch (Exception e) { 
                Log.w(TAG, "loadFragmentSettings: Lost permission or file not found for custom sound URI: " + currentCustomSoundUriForPicker, e);
                Toast.makeText(getContext(), R.string.custom_sound_no_longer_accessible_toast, Toast.LENGTH_LONG).show();
                currentCustomSoundUriForPicker = null;
                saveCustomSoundUriToServicePrefs(null); 
            }
        } else {
            currentCustomSoundUriForPicker = null;
        }
        boolean customSoundIsEnabledInPrefs = serviceSharedPrefs.getBoolean(PREF_CUSTOM_ALERT_SOUND_ENABLED, currentCustomSoundUriForPicker != null);
        switchEnableCustomSoundAlert.setChecked(customSoundIsEnabledInPrefs);
        updateCustomSoundDisplayUI();
    }

    private void saveAppSettingsToHomePrefs() {
        SharedPreferences.Editor editor = homeFragmentPrefs.edit();
        editor.putInt(PREF_APP_ALERT_LEVEL_DB, seekBarAppAlertLevel.getProgress());
        editor.putBoolean(PREF_APP_ALERTS_ENABLED, switchEnableAppAlerts.isChecked());
        editor.apply();
        Log.d(TAG, "Saved app alert settings to HomeFragmentPrefs.");
    }

    private void setupUIListeners() {
        switchEnableMicMonitoring.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isAdded() || getContext() == null) return;
            if (currentActiveEspIpForFragment != null && !currentActiveEspIpForFragment.isEmpty()) {
                if (isChecked) {
                    startOrUpdateMicMonitoring(currentActiveEspIpForFragment);
                } else {
                    stopMicMonitoringService(); 
                }
            } else {
                if (isChecked) { 
                    Toast.makeText(getContext(), R.string.no_active_esp_selected_toast, Toast.LENGTH_SHORT).show();
                    buttonView.setChecked(false); 
                }
            }
        });

        seekBarAppAlertLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!isAdded() || getContext() == null) return;
                textViewAppAlertLevelValue.setText(getString(R.string.decibel_format, progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) { saveAppSettingsToHomePrefs(); }
        });

        switchEnableAppAlerts.setOnCheckedChangeListener((buttonView, isChecked) -> saveAppSettingsToHomePrefs());
        
        buttonSelectCustomSound.setOnClickListener(v -> checkStoragePermissionAndOpenPicker());
        buttonTestCustomSound.setOnClickListener(v -> {
            if (!isAdded() || getContext() == null) return;
            if (currentCustomSoundUriForPicker != null) {
                playLocalTestSound(currentCustomSoundUriForPicker);
            } else {
                Toast.makeText(getContext(), R.string.no_custom_sound_selected, Toast.LENGTH_SHORT).show();
            }
        });
        switchEnableCustomSoundAlert.setOnCheckedChangeListener((buttonView, isChecked) -> {
            saveCustomSoundEnabledToServicePrefs(isChecked);
        });
    }

    private void startOrUpdateMicMonitoring(String espIpAddress) {
        if (!isAdded() || getContext() == null) return;
        if (espIpAddress == null || espIpAddress.isEmpty()) {
            Toast.makeText(getContext(), R.string.esp_address_not_set_toast, Toast.LENGTH_SHORT).show();
            switchEnableMicMonitoring.setChecked(false);
            return;
        }
        Intent serviceIntent = new Intent(getActivity(), HttpPollingService.class);
        serviceIntent.setAction(HttpPollingService.ACTION_START_FOREGROUND_SERVICE);
        serviceIntent.putExtra(HttpPollingService.EXTRA_BASE_URL, "http://" + espIpAddress);
        
        // SAFELY START FOREGROUND SERVICE (Fixes Android 12+ Background Crashes)
        Context context = getContext();
        if (context != null) {
            try {
                ContextCompat.startForegroundService(context, serviceIntent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start monitoring via ForegroundService, trying background", e);
                try {
                    context.startService(serviceIntent);
                } catch (Exception e2) {
                    Log.e(TAG, "Failed to start monitoring entirely", e2);
                }
            }
        }
    }

    private void stopMicMonitoringService() {
        if (!isAdded() || getContext() == null) return;
        Log.i(TAG, "Requesting to stop mic monitoring polling.");
        Intent serviceIntent = new Intent(getActivity(), HttpPollingService.class);
        serviceIntent.setAction(HttpPollingService.ACTION_STOP_POLLING);
        Context context = getContext();
        if (context != null) {
             context.startService(serviceIntent);
        }
    }

    private void processAndDisplaySensorData(String jsonData) {
        if (!isAdded() || getContext() == null) return; 

        try {
            JSONObject json = new JSONObject(jsonData);
            double dbCalibrated = json.optDouble("db_calibrated", -999.0); 
            double rms = json.optDouble("rms", -1.0);
            String espDeviceStatus = json.optString("status", "N/A");
            String espError = json.optString("error", ""); 

            if (!espError.isEmpty() && !"null".equalsIgnoreCase(espError)) {
                textViewMicData.setText(getString(R.string.mic_data_esp_error, espError));
            } else if (dbCalibrated == -999.0) { 
                 textViewMicData.setText(R.string.mic_data_invalid_default);
            }
            else {
                textViewMicData.setText(getString(R.string.mic_data_format, dbCalibrated, rms, espDeviceStatus));
            }

            if (switchEnableAppAlerts.isChecked() && (espError.isEmpty() || "null".equalsIgnoreCase(espError)) && dbCalibrated != -999.0) {
                int appAlertThreshold = seekBarAppAlertLevel.getProgress();
                if (dbCalibrated >= appAlertThreshold) {
                    if (switchEnableCustomSoundAlert.isChecked() && currentCustomSoundUriForPicker != null) {
                        Intent alertSoundIntent = new Intent(getContext(), AlertSoundService.class);
                        alertSoundIntent.setAction(AlertSoundService.ACTION_PLAY_CUSTOM_SOUND);
                        alertSoundIntent.putExtra(AlertSoundService.EXTRA_SOUND_URI, currentCustomSoundUriForPicker.toString());
                        alertSoundIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        
                        try {
                            ContextCompat.startForegroundService(requireContext(), alertSoundIntent);
                        } catch (Exception e) {
                            Log.e(TAG, "Could not start AlertSoundService", e);
                            try { getContext().startService(alertSoundIntent); } catch (Exception ignored) {}
                        }
                    }
                    Toast.makeText(getContext(), "LOUD! " + String.format(Locale.getDefault(), "%.1f dB", dbCalibrated), Toast.LENGTH_SHORT).show();
                }
            }

        } catch (JSONException e) {
            textViewMicData.setText(R.string.mic_data_parse_error);
            Log.e(TAG, "Error parsing mic data JSON in HomeFragment: " + e.getMessage());
        }
    }
        
    private String getHostFromUrlSafe(String urlString) {
        if (urlString == null || urlString.trim().isEmpty()) return "Unknown";
        try {
            return urlString; 
        } catch (Exception e) { 
            Log.w(TAG, "Malformed URL for getHostFromUrlSafe: " + urlString);
            return urlString; 
        }
    }
    
    private void saveCustomSoundUriToServicePrefs(Uri uri) {
        SharedPreferences.Editor editor = serviceSharedPrefs.edit();
        if (uri != null) {
            editor.putString(PREF_CUSTOM_ALERT_SOUND_URI, uri.toString());
        } else {
            editor.remove(PREF_CUSTOM_ALERT_SOUND_URI);
        }
        editor.apply();
        Log.d(TAG, "Saved custom sound URI to service prefs: " + (uri != null ? uri.toString() : "null"));
    }

    private void saveCustomSoundEnabledToServicePrefs(boolean enabled) {
        serviceSharedPrefs.edit().putBoolean(PREF_CUSTOM_ALERT_SOUND_ENABLED, enabled).apply();
        Log.d(TAG, "Saved custom sound enabled status to service prefs: " + enabled);
    }

    private void updateCustomSoundDisplayUI() {
        if (!isAdded() || getContext() == null) return;
        if (currentCustomSoundUriForPicker != null) {
            textViewSelectedCustomSound.setText(getString(R.string.custom_sound_selected_label, getFileNameFromUri(currentCustomSoundUriForPicker)));
            buttonTestCustomSound.setEnabled(true);
            switchEnableCustomSoundAlert.setEnabled(true); 
        } else {
            textViewSelectedCustomSound.setText(R.string.no_custom_sound_selected);
            buttonTestCustomSound.setEnabled(false);
            switchEnableCustomSoundAlert.setEnabled(false); 
            switchEnableCustomSoundAlert.setChecked(false); 
            saveCustomSoundEnabledToServicePrefs(false); 
        }
    }

    private String getFileNameFromUri(Uri uri) {
        if (uri == null || getContext() == null) return "None";
        String result = null;
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            try (Cursor cursor = getContext().getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (displayNameIndex != -1) { 
                        result = cursor.getString(displayNameIndex);
                    } else {
                        Log.w(TAG, "Display name column not found for URI: " + uri);
                    }
                }
            } catch (Exception e) { Log.e(TAG, "Error getting file name from content URI: " + uri, e); }
        }
        if (result == null) { 
            result = uri.getPath();
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) result = result.substring(cut + 1);
            } else result = uri.toString(); 
        }
        return result != null ? result : "Unknown File";
    }

    private void checkStoragePermissionAndOpenPicker() {
        if (!isAdded() || getContext() == null) return;
        String permission;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permission = Manifest.permission.READ_MEDIA_AUDIO;
        } else {
            permission = Manifest.permission.READ_EXTERNAL_STORAGE; 
        }

        if (ContextCompat.checkSelfPermission(getContext(), permission) == PackageManager.PERMISSION_GRANTED) {
            openCustomSoundPicker();
        } else {
            Log.d(TAG, "Requesting storage permission: " + permission);
            requestStoragePermissionLauncher.launch(permission);
        }
    }

    private void openCustomSoundPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            selectCustomSoundLauncher.launch(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error launching custom sound picker", e);
            if (isAdded() && getContext() != null) Toast.makeText(getContext(), "Could not open sound picker.", Toast.LENGTH_SHORT).show();
        }
    }

    private void playLocalTestSound(Uri soundUri) {
        if (!isAdded() || getContext() == null) return;
        if (localTestMediaPlayer != null) {
            localTestMediaPlayer.release();
            localTestMediaPlayer = null;
        }
        localTestMediaPlayer = new MediaPlayer();
        localTestMediaPlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA) 
                        .build());
        try {
            localTestMediaPlayer.setDataSource(getContext(), soundUri);
            localTestMediaPlayer.prepareAsync(); 
            localTestMediaPlayer.setOnPreparedListener(mp -> {
                Log.d(TAG, "Local test MediaPlayer prepared, starting playback.");
                mp.start();
                if (isAdded() && getContext() != null) Toast.makeText(getContext(), R.string.custom_sound_playing_test, Toast.LENGTH_SHORT).show();
            });
            localTestMediaPlayer.setOnCompletionListener(mp -> {
                Log.d(TAG, "Local test MediaPlayer playback completed.");
                if (isAdded() && getContext() != null) Toast.makeText(getContext(), R.string.custom_sound_test_finished, Toast.LENGTH_SHORT).show();
                if(localTestMediaPlayer != null) { mp.release(); localTestMediaPlayer = null;}
            });
            localTestMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "Local test MediaPlayer error: what=" + what + ", extra=" + extra);
                if (isAdded() && getContext() != null) Toast.makeText(getContext(), R.string.custom_sound_error_playing, Toast.LENGTH_SHORT).show();
                if(localTestMediaPlayer != null) {mp.release(); localTestMediaPlayer = null;}
                return true; 
            });
        } catch (IOException e) {
            Log.e(TAG, "IOException setting data source for local MediaPlayer: " + soundUri, e);
            if (isAdded() && getContext() != null) Toast.makeText(getContext(), R.string.custom_sound_error_preparing, Toast.LENGTH_SHORT).show();
             if (localTestMediaPlayer != null) { localTestMediaPlayer.release(); localTestMediaPlayer = null; }
        } catch (SecurityException se) {
             Log.e(TAG, "SecurityException setting data source (URI permission issue?): " + soundUri, se);
            if (isAdded() && getContext() != null) Toast.makeText(getContext(), "Permission error for sound file.", Toast.LENGTH_SHORT).show();
            if (localTestMediaPlayer != null) { localTestMediaPlayer.release(); localTestMediaPlayer = null; }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        loadFragmentSettings();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (localTestMediaPlayer != null) {
            if (localTestMediaPlayer.isPlaying()) {
                localTestMediaPlayer.stop();
            }
            localTestMediaPlayer.release();
            localTestMediaPlayer = null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (localTestMediaPlayer != null) {
            localTestMediaPlayer.release();
            localTestMediaPlayer = null;
        }
    }
}