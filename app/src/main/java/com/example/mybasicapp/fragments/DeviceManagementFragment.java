// app\src\main\java\com\example\mybasicapp\fragments\DeviceManagementFragment.java
package com.example.mybasicapp.fragments;

import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.mybasicapp.R;
import com.example.mybasicapp.WebViewActivity;
import com.example.mybasicapp.adapters.EspDeviceAdapter;
import com.example.mybasicapp.viewmodels.AppViewModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DeviceManagementFragment extends Fragment {

    private static final String TAG = "DeviceManagementFrag";
    private static final String SERVICE_TYPE = "_http._tcp.";

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private SwipeRefreshLayout swipeRefresh;
    private RecyclerView recyclerView;
    private TextView textViewEmptyState;
    private Button buttonClearFilters;
    private EspDeviceAdapter adapter;
    private final List<EspDeviceAdapter.EspDevice> deviceList = new ArrayList<>();
    private WifiManager.MulticastLock multicastLock;
    private ExecutorService executorService;
    private Handler mainThreadHandler;
    private AppViewModel appViewModel;

    private final Queue<NsdServiceInfo> resolveQueue = new LinkedList<>();
    private boolean isResolving = false;

    private final List<NsdManager.ServiceInfoCallback> activeCallbacks = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appViewModel = new ViewModelProvider(requireActivity()).get(AppViewModel.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_device_management, container, false);

        mainThreadHandler = new Handler(Looper.getMainLooper());
        nsdManager = (NsdManager) requireContext().getSystemService(Context.NSD_SERVICE);
        executorService = Executors.newSingleThreadExecutor();

        WifiManager wifi = (WifiManager) requireContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("esp32_multicast_lock");
            multicastLock.setReferenceCounted(true);
            multicastLock.acquire();
        }

        swipeRefresh = view.findViewById(R.id.swipeRefresh);
        recyclerView = view.findViewById(R.id.recyclerViewDevices);
        textViewEmptyState = view.findViewById(R.id.textViewEmptyState);
        buttonClearFilters = view.findViewById(R.id.buttonClearFilters);
        
        EditText editTextManualIp = view.findViewById(R.id.editTextManualIp);
        Button buttonAddManualIp = view.findViewById(R.id.buttonAddManualIp);

        buttonAddManualIp.setOnClickListener(v -> {
            String ip = editTextManualIp.getText().toString().trim();
            if (!ip.isEmpty()) {
                String cleanIp = ip.replaceFirst("^(http://|https://)", "").replaceAll("/.*$", "").trim();
                String customName = appViewModel.getCustomName(cleanIp);
                String displayName = (customName != null && !customName.isEmpty()) ? customName : "Manual ESP (" + cleanIp + ")";
                
                com.example.mybasicapp.model.EspDevice newModelDevice = new com.example.mybasicapp.model.EspDevice(displayName, cleanIp);
                appViewModel.addEspDevice(newModelDevice);

                EspDeviceAdapter.EspDevice newDevice = new EspDeviceAdapter.EspDevice(displayName, "Manual Device", cleanIp, 80);
                
                mainThreadHandler.post(() -> {
                    if (!isAdded() || getContext() == null) return; // Null safety checking
                    boolean exists = false;
                    for (EspDeviceAdapter.EspDevice existingDevice : deviceList) {
                        if (existingDevice.getIpAddress().equals(cleanIp)) {
                            exists = true;
                            break;
                        }
                    }

                    if (!exists) {
                        deviceList.add(newDevice);
                        updateUI();
                        Toast.makeText(getContext(), "Manual IP permanently saved and added", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(getContext(), "IP is already in the list", Toast.LENGTH_SHORT).show();
                    }
                    editTextManualIp.setText(""); 
                });
            }
        });

        buttonClearFilters.setOnClickListener(v -> {
            if (!isAdded() || getContext() == null) return;
            new AlertDialog.Builder(requireContext())
                    .setTitle("Clear All Filters?")
                    .setMessage("This will unhide all devices and keywords. Are you sure?")
                    .setPositiveButton("Yes, Clear", (dialog, which) -> {
                        appViewModel.clearAllFilters();
                        if (isAdded() && getContext() != null) Toast.makeText(getContext(), "Filters cleared. Re-scanning...", Toast.LENGTH_SHORT).show();
                        restartDiscovery();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        setupRecyclerView();

        swipeRefresh.setOnRefreshListener(this::restartDiscovery);
        return view;
    }

    private void setupRecyclerView() {
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new EspDeviceAdapter(deviceList,
                device -> {
                    if (!isAdded() || getContext() == null) return;
                    String address = device.getIpAddress();
                    appViewModel.setActiveEspAddress(address);
                    Toast.makeText(getContext(), "\"" + device.getName() + "\" set as active device.", Toast.LENGTH_SHORT).show();

                    appViewModel.addEspDevice(new com.example.mybasicapp.model.EspDevice(device.getName(), device.getIpAddress()));

                    Intent intent = new Intent(requireContext(), WebViewActivity.class);
                    intent.putExtra("URL", device.getUrl());
                    intent.putExtra("NAME", device.getName());
                    startActivity(intent);
                },
                this::showDeviceOptionsDialog
        );
        recyclerView.setAdapter(adapter);
    }

    private void showDeviceOptionsDialog(EspDeviceAdapter.EspDevice device) {
        if (!isAdded() || getContext() == null) return;
        final CharSequence[] items = {
                "Rename Device",
                "Remove / Delete Saved Device",
                "Hide by Keyword..."
        };

        new AlertDialog.Builder(requireContext())
                .setTitle("Options for \"" + device.getName() + "\"")
                .setItems(items, (dialog, item) -> {
                    if (!isAdded() || getContext() == null) return;
                    if (item == 0) { 
                        showRenameDialog(device);
                    } else if (item == 1) { 
                        appViewModel.removeEspDevice(new com.example.mybasicapp.model.EspDevice(device.getName(), device.getIpAddress()));
                        appViewModel.addBannedIp(device.getIpAddress()); 
                        Toast.makeText(getContext(), "Device removed.", Toast.LENGTH_SHORT).show();
                        restartDiscovery();
                    } else if (item == 2) { 
                        showKeywordDialog(device);
                    }
                })
                .show();
    }

    private void showRenameDialog(EspDeviceAdapter.EspDevice device) {
        if (!isAdded() || getContext() == null) return;
        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(device.getName());
        input.setHint("Enter a friendly name");
        FrameLayout container = new FrameLayout(requireContext());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        
        int marginPx = (int) (16 * getResources().getDisplayMetrics().density);
        params.leftMargin = marginPx;
        params.rightMargin = marginPx;
        
        input.setLayoutParams(params);
        container.addView(input);

        new AlertDialog.Builder(requireContext())
                .setTitle("Rename Device")
                .setView(container)
                .setPositiveButton("Save", (dialog, which) -> {
                    if (!isAdded() || getContext() == null) return;
                    String newName = input.getText().toString().trim();
                    appViewModel.setCustomName(device.getIpAddress(), newName);
                    Toast.makeText(getContext(), "Device renamed. Re-scanning...", Toast.LENGTH_SHORT).show();
                    restartDiscovery();
                })
                .setNegativeButton("Cancel", (dialog, which) -> dialog.cancel())
                .show();
    }

    private void showKeywordDialog(EspDeviceAdapter.EspDevice device) {
        if (!isAdded() || getContext() == null) return;
        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        String originalName = device.getOriginalName();
        String suggestedKeyword = originalName.split("[-\\s]")[0];
        input.setText(suggestedKeyword);
        input.setHint("e.g., HP, ApeosPort");
        FrameLayout container = new FrameLayout(requireContext());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        
        int marginPx = (int) (16 * getResources().getDisplayMetrics().density);
        params.leftMargin = marginPx;
        params.rightMargin = marginPx;
        
        input.setLayoutParams(params);
        container.addView(input);

        new AlertDialog.Builder(requireContext())
                .setTitle("Hide by Keyword")
                .setView(container)
                .setPositiveButton("Hide", (dialog, which) -> {
                    if (!isAdded() || getContext() == null) return;
                    String keyword = input.getText().toString().trim();
                    if (!keyword.isEmpty()) {
                        appViewModel.addBannedKeyword(keyword);
                        Toast.makeText(getContext(), "Devices with '" + keyword + "' will be hidden on next scan.", Toast.LENGTH_LONG).show();
                        restartDiscovery();
                    } else {
                        Toast.makeText(getContext(), "Keyword cannot be empty.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", (dialog, which) -> dialog.cancel())
                .show();
    }

    @Override
    public void onResume() {
        super.onResume();
        restartDiscovery();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopDiscovery();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
        mainThreadHandler.removeCallbacksAndMessages(null);
    }

    private void restartDiscovery() {
        if (!isAdded()) return;
        mainThreadHandler.post(() -> {
            if (isAdded() && swipeRefresh != null) swipeRefresh.setRefreshing(true);
        });
        stopDiscovery();
        deviceList.clear();

        List<com.example.mybasicapp.model.EspDevice> savedDevices = appViewModel.getEspDevicesLiveData().getValue();
        if (savedDevices != null) {
            for (com.example.mybasicapp.model.EspDevice saved : savedDevices) {
                String customName = appViewModel.getCustomName(saved.getAddress());
                String displayName = (customName != null && !customName.isEmpty()) ? customName : saved.getName();
                
                if (!appViewModel.isIpBanned(saved.getAddress())) {
                    deviceList.add(new EspDeviceAdapter.EspDevice(displayName, "Saved Device", saved.getAddress(), 80));
                }
            }
        }

        updateUI();
        startDiscovery();

        mainThreadHandler.postDelayed(() -> {
            if (isAdded() && swipeRefresh != null && swipeRefresh.isRefreshing()) {
                swipeRefresh.setRefreshing(false);
            }
        }, 8000); 
    }

    private void startDiscovery() {
        if (discoveryListener != null) {
             stopDiscovery();
        }
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {}

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                if (service.getServiceType().contains("_http._tcp")) {
                    handleServiceResolution(service);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {}

            @Override
            public void onDiscoveryStopped(String serviceType) {}

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                if (nsdManager != null) {
                    try {
                        nsdManager.stopServiceDiscovery(this);
                    } catch (Exception e) { Log.e(TAG, "Error stopping discovery on failure.", e); }
                }
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
        };

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start discovery", e);
        }
    }

    private void handleServiceResolution(NsdServiceInfo service) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            NsdManager.ServiceInfoCallback callback = new NsdManager.ServiceInfoCallback() {
                @Override
                public void onServiceInfoCallbackRegistrationFailed(int errorCode) {}

                @Override
                public void onServiceUpdated(@NonNull NsdServiceInfo serviceInfo) {
                    addDeviceToList(serviceInfo);
                }

                @Override
                public void onServiceLost() {}

                @Override
                public void onServiceInfoCallbackUnregistered() {}
            };
            activeCallbacks.add(callback);
            nsdManager.registerServiceInfoCallback(service, executorService, callback);
        } else {
            synchronized (resolveQueue) {
                if (!resolveQueue.contains(service)) {
                    resolveQueue.add(service);
                }
                if (!isResolving) {
                    resolveNextInQueue();
                }
            }
        }
    }

    private void resolveNextInQueue() {
        synchronized (resolveQueue) {
            if (resolveQueue.isEmpty() || isResolving) return;
            isResolving = true;
            NsdServiceInfo serviceToResolve = resolveQueue.poll();
            if (serviceToResolve == null) {
                isResolving = false;
                return;
            }

            try {
                nsdManager.resolveService(serviceToResolve, new NsdManager.ResolveListener() {
                    @Override
                    public void onServiceResolved(NsdServiceInfo serviceInfo) {
                        addDeviceToList(serviceInfo);
                        finishResolving();
                    }

                    @Override
                    public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                        finishResolving();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Exception during resolve", e);
                finishResolving();
            }
        }
    }

    private void finishResolving() {
        synchronized (resolveQueue) {
            isResolving = false;
            resolveNextInQueue();
        }
    }

    private void addDeviceToList(NsdServiceInfo serviceInfo) {
        if (serviceInfo.getHost() != null) {
            String originalName = serviceInfo.getServiceName();
            String ip = serviceInfo.getHost().getHostAddress();

            if (appViewModel.isIpBanned(ip) || appViewModel.matchesBannedKeyword(originalName)) {
                return;
            }

            int port = serviceInfo.getPort();
            String customName = appViewModel.getCustomName(ip);
            String displayName = (customName != null && !customName.isEmpty()) ? customName : originalName;

            EspDeviceAdapter.EspDevice newDevice = new EspDeviceAdapter.EspDevice(displayName, originalName, ip, port);

            mainThreadHandler.post(() -> {
                if (!isAdded()) return; // UI Safety check
                boolean deviceExists = false;
                for (EspDeviceAdapter.EspDevice existingDevice : deviceList) {
                    if (existingDevice.getIpAddress().equals(ip)) {
                        deviceExists = true;
                        break;
                    }
                }

                if (!deviceExists) {
                    deviceList.add(newDevice);
                    Collections.sort(deviceList, Comparator.comparing(EspDeviceAdapter.EspDevice::getName, String.CASE_INSENSITIVE_ORDER));
                    updateUI();
                }
            });
        }
    }

    private void updateUI() {
        if (isAdded() && adapter != null && recyclerView != null && textViewEmptyState != null) {
            adapter.notifyDataSetChanged();
            if (deviceList.isEmpty()) {
                recyclerView.setVisibility(View.GONE);
                textViewEmptyState.setVisibility(View.VISIBLE);
            } else {
                recyclerView.setVisibility(View.VISIBLE);
                textViewEmptyState.setVisibility(View.GONE);
            }

            if (swipeRefresh.isRefreshing() && !deviceList.isEmpty()) {
                swipeRefresh.setRefreshing(false);
            }

            if (buttonClearFilters != null) {
                 buttonClearFilters.setVisibility(appViewModel.hasFilters() ? View.VISIBLE : View.GONE);
            }
        }
    }

    private void stopDiscovery() {
        if (discoveryListener != null) {
            try {
                if (nsdManager != null) {
                    nsdManager.stopServiceDiscovery(discoveryListener);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping discovery listener.", e);
            }
            discoveryListener = null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            synchronized (activeCallbacks) {
                for (NsdManager.ServiceInfoCallback callback : activeCallbacks) {
                    try {
                        if (nsdManager != null) {
                            nsdManager.unregisterServiceInfoCallback(callback);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error unregistering service info callback.", e);
                    }
                }
                activeCallbacks.clear();
            }
        }
        
        synchronized (resolveQueue) {
            resolveQueue.clear();
            isResolving = false;
        }
    }
}