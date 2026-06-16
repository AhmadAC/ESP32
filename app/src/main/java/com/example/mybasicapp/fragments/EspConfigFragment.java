// app\src\main\java\com\example\mybasicapp\fragments\EspConfigFragment.java
package com.example.mybasicapp.fragments;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.example.mybasicapp.R;
import com.example.mybasicapp.network.EspConfigClient;
import com.example.mybasicapp.viewmodels.AppViewModel;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

public class EspConfigFragment extends Fragment {
    private static final String TAG = "EspConfigFragment_DBG";

    private AppViewModel appViewModel;
    private EspConfigClient espConfigClient;

    private TextInputEditText editTextCalibrationOffset, editTextWifiSsid, editTextWifiPassword;
    private TextInputLayout textInputLayoutCalibration, textInputLayoutSsid, textInputLayoutPassword;
    private Button buttonFetchEspConfig, buttonSetCalibration, buttonSetWifi;
    private TextView textViewEspConfigStatus;

    private String currentActiveEspIpForFragment = null;
    private boolean isOperating = false; 
    private JSONObject currentEspConfig = null;

    public EspConfigFragment() {
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appViewModel = new ViewModelProvider(requireActivity()).get(AppViewModel.class);
        espConfigClient = new EspConfigClient();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_esp_config, container, false);

        editTextCalibrationOffset = view.findViewById(R.id.editTextCalibrationOffset);
        editTextWifiSsid = view.findViewById(R.id.editTextWifiSsid);
        editTextWifiPassword = view.findViewById(R.id.editTextWifiPassword);

        textInputLayoutCalibration = view.findViewById(R.id.textInputLayoutCalibrationOffset);
        textInputLayoutSsid = view.findViewById(R.id.textInputLayoutWifiSsid);
        textInputLayoutPassword = view.findViewById(R.id.textInputLayoutWifiPassword);

        buttonFetchEspConfig = view.findViewById(R.id.buttonFetchEspConfig);
        buttonSetCalibration = view.findViewById(R.id.buttonSetCalibration);
        buttonSetWifi = view.findViewById(R.id.buttonSetWifi);
        textViewEspConfigStatus = view.findViewById(R.id.textViewEspConfigStatus);

        buttonFetchEspConfig.setOnClickListener(v -> {
            if (!isAdded() || getContext() == null) return;
            if (currentActiveEspIpForFragment != null && !currentActiveEspIpForFragment.isEmpty()) {
                fetchFullEspConfig(currentActiveEspIpForFragment);
            } else {
                Toast.makeText(getContext(), R.string.no_active_esp_for_action, Toast.LENGTH_SHORT).show();
            }
        });
        buttonSetCalibration.setOnClickListener(v -> setEspCalibrationOffset());
        buttonSetWifi.setOnClickListener(v -> setEspWifiConfig());

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        appViewModel.getActiveEspAddressLiveData().observe(getViewLifecycleOwner(), address -> {
            if (!isAdded() || getContext() == null) return;
            currentActiveEspIpForFragment = address;
            updateUiBasedOnActiveEsp();
            if (address != null && !address.isEmpty()) {
                fetchFullEspConfig(address); 
            } else {
                clearConfigFields();
                textViewEspConfigStatus.setText(R.string.select_active_esp_prompt);
            }
        });
    }

    private void updateUiBasedOnActiveEsp() {
        boolean isActiveEspSelected = (currentActiveEspIpForFragment != null && !currentActiveEspIpForFragment.isEmpty());
        buttonFetchEspConfig.setEnabled(isActiveEspSelected);

        textInputLayoutCalibration.setEnabled(isActiveEspSelected);
        editTextCalibrationOffset.setEnabled(isActiveEspSelected);
        buttonSetCalibration.setEnabled(isActiveEspSelected);

        textInputLayoutSsid.setEnabled(isActiveEspSelected);
        editTextWifiSsid.setEnabled(isActiveEspSelected);
        textInputLayoutPassword.setEnabled(isActiveEspSelected);
        editTextWifiPassword.setEnabled(isActiveEspSelected);
        buttonSetWifi.setEnabled(isActiveEspSelected);

        if (!isActiveEspSelected) {
            clearConfigFields();
            textViewEspConfigStatus.setText(R.string.select_active_esp_prompt);
            currentEspConfig = null; 
        }
    }

    private void clearConfigFields() {
        editTextCalibrationOffset.setText("");
        editTextWifiSsid.setText("");
        editTextWifiPassword.setText("");
    }

    private void populateFieldsFromConfig(JSONObject config) {
        if (!isAdded() || config == null || getContext() == null) {
            clearConfigFields();
            return;
        }
        currentEspConfig = config; 

        editTextCalibrationOffset.setText(String.format(Locale.US, "%.1f", config.optDouble("calibration_offset", 0.0)));
        editTextWifiSsid.setText(config.optString("wifi_ssid", ""));
        editTextWifiPassword.setText(""); 

        textViewEspConfigStatus.setText(getString(R.string.status_config_loaded_at, System.currentTimeMillis()));
    }

    private void fetchFullEspConfig(String espIp) {
        if (espIp == null || espIp.isEmpty() || isOperating) {
            return;
        }
        isOperating = true;
        textViewEspConfigStatus.setText(R.string.status_fetching_config);
        clearConfigFields(); 

        String baseUrl = "http://" + espIp; 
        espConfigClient.getConfig(baseUrl, new EspConfigClient.ConfigCallback() {
            @Override
            public void onSuccess(String responseBody) {
                if (!isAdded() || getActivity() == null) { isOperating = false; return; }
                getActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    isOperating = false;
                    try {
                        JSONObject config = new JSONObject(responseBody);
                        populateFieldsFromConfig(config);
                        textViewEspConfigStatus.setText(R.string.status_config_fetched_ok);
                    } catch (JSONException e) {
                        textViewEspConfigStatus.setText(R.string.status_config_parse_error);
                        currentEspConfig = null;
                    }
                });
            }
            @Override
            public void onFailure(IOException e) {
                handleOperationError(getString(R.string.status_fetch_failed_network, e.getMessage()));
            }
            @Override
            public void onError(String message, int code) {
                handleOperationError(getString(R.string.status_fetch_failed_server, code, message));
            }
        });
    }

    private void setEspCalibrationOffset() {
        if (!isAdded() || getContext() == null) return;
        if (currentActiveEspIpForFragment == null || currentActiveEspIpForFragment.isEmpty() || isOperating) {
            if (isOperating) Toast.makeText(getContext(), R.string.operation_in_progress, Toast.LENGTH_SHORT).show();
            else Toast.makeText(getContext(), R.string.no_active_esp_for_action, Toast.LENGTH_SHORT).show();
            return;
        }
        String offsetStr = editTextCalibrationOffset.getText() != null ? editTextCalibrationOffset.getText().toString().trim() : "";
        if (TextUtils.isEmpty(offsetStr)) {
            Toast.makeText(getContext(), R.string.enter_calibration_offset_prompt, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            float offsetValue = Float.parseFloat(offsetStr);
            isOperating = true;
            textViewEspConfigStatus.setText(R.string.status_setting_calibration);
            String baseUrl = "http://" + currentActiveEspIpForFragment;
            espConfigClient.setCalibration(baseUrl, offsetValue, new EspConfigClient.ConfigCallback() {
                @Override public void onSuccess(String responseBody) {
                    handleOperationError(getString(R.string.status_calibration_set_success));
                    fetchFullEspConfig(currentActiveEspIpForFragment); 
                }
                @Override public void onFailure(IOException e) { handleOperationError(getString(R.string.status_set_failed_network, e.getMessage()));}
                @Override public void onError(String message, int code) { handleOperationError(getString(R.string.status_set_failed_server, code, message));}
            });
        } catch (NumberFormatException e) {
            Toast.makeText(getContext(), R.string.invalid_offset_format_prompt, Toast.LENGTH_SHORT).show();
            textViewEspConfigStatus.setText(R.string.status_invalid_format);
        }
    }

    private void setEspWifiConfig() {
        if (!isAdded() || getContext() == null) return;
        if (currentActiveEspIpForFragment == null || currentActiveEspIpForFragment.isEmpty() || isOperating) {
             if (isOperating) Toast.makeText(getContext(), R.string.operation_in_progress, Toast.LENGTH_SHORT).show();
            else Toast.makeText(getContext(), R.string.no_active_esp_for_action, Toast.LENGTH_SHORT).show();
            return;
        }
        String ssid = editTextWifiSsid.getText() != null ? editTextWifiSsid.getText().toString().trim() : "";
        String password = editTextWifiPassword.getText() != null ? editTextWifiPassword.getText().toString() : ""; 

        if (TextUtils.isEmpty(ssid)) {
            Toast.makeText(getContext(), R.string.enter_wifi_ssid_prompt, Toast.LENGTH_SHORT).show();
            return;
        }
        isOperating = true;
        textViewEspConfigStatus.setText(R.string.status_setting_wifi);
        String baseUrl = "http://" + currentActiveEspIpForFragment;
        espConfigClient.setWifiConfig(baseUrl, ssid, password, new EspConfigClient.ConfigCallback() {
            @Override public void onSuccess(String responseBody) {
                handleOperationError(getString(R.string.status_wifi_set_success));
                if (isAdded() && getContext() != null) Toast.makeText(getContext(), R.string.esp_reboot_may_be_needed_wifi, Toast.LENGTH_LONG).show();
            }
            @Override public void onFailure(IOException e) { handleOperationError(getString(R.string.status_set_failed_network, e.getMessage()));}
            @Override public void onError(String message, int code) { handleOperationError(getString(R.string.status_set_failed_server, code, message));}
        });
    }

    private void handleOperationError(String message) {
        if (!isAdded() || getActivity() == null) { isOperating = false; return;}
        getActivity().runOnUiThread(() -> {
            if (!isAdded() || getContext() == null) return;
            isOperating = false;
            textViewEspConfigStatus.setText(message.substring(0, Math.min(message.length(),150)));
            Toast.makeText(getContext(), message.substring(0, Math.min(message.length(),100)), Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (currentActiveEspIpForFragment != null && !currentActiveEspIpForFragment.isEmpty()) {
            fetchFullEspConfig(currentActiveEspIpForFragment);
        }
        updateUiBasedOnActiveEsp();
    }
}