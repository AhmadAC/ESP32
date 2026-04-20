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

    // Queue for Android 13 and below to prevent "Already resolving" crashes
    private final Queue<NsdServiceInfo> resolveQueue = new LinkedList<>();
    private boolean isResolving = false;

    // List to hold API 34+ callbacks for proper cleanup
    private final List<NsdManager.ServiceInfoCallback> activeCallbacks = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Initialize ViewModel
        appViewModel = new ViewModelProvider(requireActivity()).get(AppViewModel.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_device_management, container, false);

        mainThreadHandler = new Handler(Looper.getMainLooper());
        nsdManager = (NsdManager) requireContext().getSystemService(Context.NSD_SERVICE);
        executorService = Executors.newSingleThreadExecutor();

        // On Samsung & other devices, a multicast lock is required for mDNS to work reliably
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

        buttonClearFilters.setOnClickListener(v -> {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Clear All Filters?")
                    .setMessage("This will unhide all devices and keywords. Are you sure?")
                    .setPositiveButton("Yes, Clear", (dialog, which) -> {
                        appViewModel.clearAllFilters();
                        Toast.makeText(getContext(), "Filters cleared. Re-scanning...", Toast.LENGTH_SHORT).show();
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
                // OnClickListener
                device -> {
                    String address = device.getIpAddress();
                    appViewModel.setActiveEspAddress(address);
                    Toast.makeText(getContext(), "\"" + device.getName() + "\" set as active device.", Toast.LENGTH_SHORT).show();

                    appViewModel.addEspDevice(new com.example.mybasicapp.model.EspDevice(device.getName(), device.getIpAddress()));

                    Intent intent = new Intent(requireContext(), WebViewActivity.class);
                    intent.putExtra("URL", device.getUrl());
                    intent.putExtra("NAME", device.getName());
                    startActivity(intent);
                },
                // OnDeviceLongClickListener
                this::showDeviceOptionsDialog
        );
        recyclerView.setAdapter(adapter);
    }

    private void showDeviceOptionsDialog(EspDeviceAdapter.EspDevice device) {
        final CharSequence[] items = {
                "Rename Device",
                "Hide This IP (" + device.getIpAddress() + ")",
                "Hide by Keyword..."
        };

        new AlertDialog.Builder(requireContext())
                .setTitle("Options for \"" + device.getName() + "\"")
                .setItems(items, (dialog, item) -> {
                    if (item == 0) { // Rename
                        showRenameDialog(device);
                    } else if (item == 1) { // Hide IP
                        appViewModel.addBannedIp(device.getIpAddress());
                        Toast.makeText(getContext(), "IP " + device.getIpAddress() + " will be hidden on next scan.", Toast.LENGTH_SHORT).show();
                        restartDiscovery();
                    } else if (item == 2) { // Hide Keyword
                        showKeywordDialog(device);
                    }
                })
                .show();
    }

    private void showRenameDialog(EspDeviceAdapter.EspDevice device) {
        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(device.getName());
        input.setHint("Enter a friendly name");
        FrameLayout container = new FrameLayout(requireContext());
        FrameLayout.LayoutParams params = new  FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = getResources().getDimensionPixelSize(R.dimen.dialog_margin);
        params.rightMargin = getResources().getDimensionPixelSize(R.dimen.dialog_margin);
        input.setLayoutParams(params);
        container.addView(input);

        new AlertDialog.Builder(requireContext())
                .setTitle("Rename Device")
                .setView(container)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    appViewModel.setCustomName(device.getIpAddress(), newName);
                    Toast.makeText(getContext(), "Device renamed. Re-scanning...", Toast.LENGTH_SHORT).show();
                    restartDiscovery();
                })
                .setNegativeButton("Cancel", (dialog, which) -> dialog.cancel())
                .show();
    }

    private void showKeywordDialog(EspDeviceAdapter.EspDevice device) {
        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        String originalName = device.getOriginalName();
        String suggestedKeyword = originalName.split("[-\\s]")[0];
        input.setText(suggestedKeyword);
        input.setHint("e.g., HP, ApeosPort");
        FrameLayout container = new FrameLayout(requireContext());
        FrameLayout.LayoutParams params = new  FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = getResources().getDimensionPixelSize(R.dimen.dialog_margin);
        params.rightMargin = getResources().getDimensionPixelSize(R.dimen.dialog_margin);
        input.setLayoutParams(params);
        container.addView(input);

        new AlertDialog.Builder(requireContext())
                .setTitle("Hide by Keyword")
                .setView(container)
                .setPositiveButton("Hide", (dialog, which) -> {
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
        mainThreadHandler.post(() -> swipeRefresh.setRefreshing(true));
        stopDiscovery();
        deviceList.clear();
        updateUI();
        startDiscovery();

        mainThreadHandler.postDelayed(() -> {
            if (swipeRefresh.isRefreshing()) {
                swipeRefresh.setRefreshing(false);
            }
        }, 8000); // 8-second discovery window
    }

    private void startDiscovery() {
        if (discoveryListener != null) {
             stopDiscovery();
        }
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
                Log.d(TAG, "mDNS Service Discovery Started");
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                Log.d(TAG, "Service Found: " + service.getServiceName());
                if (service.getServiceType().contains("_http._tcp")) {
                    handleServiceResolution(service);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
                Log.w(TAG, "Service Lost: " + service.getServiceName());
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i(TAG, "mDNS Service Discovery Stopped");
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery start failed: Error code:" + errorCode);
                if (nsdManager != null) {
                    try {
                        nsdManager.stopServiceDiscovery(this);
                    } catch (Exception e) { Log.e(TAG, "Error stopping discovery on failure.", e); }
                }
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery stop failed: Error code:" + errorCode);
            }
        };

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    private void handleServiceResolution(NsdServiceInfo service) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            NsdManager.ServiceInfoCallback callback = new NsdManager.ServiceInfoCallback() {
                @Override
                public void onServiceInfoCallbackRegistrationFailed(int errorCode) {
                    Log.e(TAG, "API 34+ Callback Registration Failed: " + errorCode);
                }

                @Override
                public void onServiceUpdated(@NonNull NsdServiceInfo serviceInfo) {
                    Log.d(TAG, "API 34+ Service Resolved: " + serviceInfo.getServiceName());
                    addDeviceToList(serviceInfo);
                }

                @Override
                public void onServiceLost() {
                    Log.d(TAG, "API 34+ Service Lost via callback");
                }

                @Override
                public void onServiceInfoCallbackUnregistered() {
                    Log.d(TAG, "API 34+ Callback Unregistered");
                }
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
            if (resolveQueue.isEmpty() || isResolving) {
                return;
            }
            isResolving = true;
            NsdServiceInfo serviceToResolve = resolveQueue.poll();
            if (serviceToResolve == null) {
                isResolving = false;
                return;
            }

            nsdManager.resolveService(serviceToResolve, new NsdManager.ResolveListener() {
                @Override
                public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    Log.d(TAG, "Legacy Service Resolved: " + serviceInfo.getServiceName());
                    addDeviceToList(serviceInfo);
                    finishResolving();
                }

                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.e(TAG, "Legacy Resolve Failed for " + serviceInfo.getServiceName() + " with error: " + errorCode);
                    finishResolving();
                }
            });
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

            // APPLY FILTERS
            if (appViewModel.isIpBanned(ip) || appViewModel.matchesBannedKeyword(originalName)) {
                Log.d(TAG, "Device filtered out: " + originalName + " (" + ip + ")");
                return;
            }

            int port = serviceInfo.getPort();

            // APPLY CUSTOM NAME
            String customName = appViewModel.getCustomName(ip);
            String displayName = (customName != null && !customName.isEmpty()) ? customName : originalName;

            EspDeviceAdapter.EspDevice newDevice = new EspDeviceAdapter.EspDevice(displayName, originalName, ip, port);

            mainThreadHandler.post(() -> {
                boolean deviceExists = false;
                for (EspDeviceAdapter.EspDevice existingDevice : deviceList) {
                    if (existingDevice.getIpAddress().equals(ip)) {
                        deviceExists = true;
                        break;
                    }
                }

                if (!deviceExists) {
                    deviceList.add(newDevice);
                    // Sort list alphabetically by display name
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

            // Show/hide clear filters button
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
        
        // Clear the resolve queue
        syn