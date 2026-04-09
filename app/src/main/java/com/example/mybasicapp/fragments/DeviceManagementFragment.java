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
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.example.mybasicapp.R;
import com.example.mybasicapp.WebViewActivity;
import com.example.mybasicapp.adapters.EspDeviceAdapter;
import java.util.ArrayList;
import java.util.Collections;
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
    private EspDeviceAdapter adapter;
    private List<EspDeviceAdapter.EspDevice> deviceList = new ArrayList<>();
    private WifiManager.MulticastLock multicastLock;
    private ExecutorService executorService;
    private Handler mainThreadHandler;

    // Queue for Android 13 and below to prevent "Already resolving" crashes
    private final Queue<NsdServiceInfo> resolveQueue = new LinkedList<>();
    private boolean isResolving = false;

    // List to hold API 34+ callbacks for proper cleanup
    private final List<NsdManager.ServiceInfoCallback> activeCallbacks = Collections.synchronizedList(new ArrayList<>());

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

        setupRecyclerView();

        swipeRefresh.setOnRefreshListener(this::restartDiscovery);
        return view;
    }

    private void setupRecyclerView() {
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new EspDeviceAdapter(deviceList, device -> {
            Intent intent = new Intent(requireContext(), WebViewActivity.class);
            intent.putExtra("URL", device.getUrl());
            intent.putExtra("NAME", device.getName());
            startActivity(intent);
        });
        recyclerView.setAdapter(adapter);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Start fresh scan when the user returns to this screen
        restartDiscovery();
    }

    @Override
    public void onPause() {
        super.onPause();
        // Stop scanning when the screen is not visible to save battery
        stopDiscovery();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Release resources
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        if (executorService != null) {
            executorService.shutdown();
        }
        mainThreadHandler.removeCallbacksAndMessages(null);
    }

    private void restartDiscovery() {
        mainThreadHandler.post(() -> swipeRefresh.setRefreshing(true));
        stopDiscovery(); // Ensure any old listeners are cleared
        deviceList.clear();
        updateUI();
        startDiscovery();

        // Add a timeout to stop the refreshing indicator after a while
        mainThreadHandler.postDelayed(() -> {
            if (swipeRefresh.isRefreshing()) {
                swipeRefresh.setRefreshing(false);
            }
        }, 5000); // 5-second discovery window
    }

    private void startDiscovery() {
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
                Log.d(TAG, "mDNS Service Discovery Started");
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                Log.d(TAG, "Service Found: " + service.getServiceName());
                // Filter for ESP32 devices that are advertising a web server
                if (service.getServiceType().contains("_http._tcp")) {
                    // You can add more specific filters, e.g., service.getServiceName().toLowerCase().contains("esp")
                    handleServiceResolution(service);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
                Log.w(TAG, "Service Lost: " + service.getServiceName());
                // Future enhancement: remove lost devices from the list
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i(TAG, "mDNS Service Discovery Stopped");
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery start failed: Error code:" + errorCode);
                nsdManager.stopServiceDiscovery(this);
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
            // Modern Android 14+ (API 34) approach
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
            };
            activeCallbacks.add(callback);
            nsdManager.registerServiceInfoCallback(service, executorService, callback);
        } else {
            // Legacy approach with a queue to prevent "Already Resolving" exceptions
            synchronized (resolveQueue) {
                resolveQueue.add(service);
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
            // Check if there are more items to process
            resolveNextInQueue();
        }
    }

    private void addDeviceToList(NsdServiceInfo serviceInfo) {
        if (serviceInfo.getHost() != null) {
            String name = serviceInfo.getServiceName();
            String ip = serviceInfo.getHost().getHostAddress();
            int port = serviceInfo.getPort();
            
            EspDeviceAdapter.EspDevice newDevice = new EspDeviceAdapter.EspDevice(name, ip, port);

            mainThreadHandler.post(() -> {
                // Prevent duplicate entries by checking IP address
                boolean deviceExists = false;
                for (EspDeviceAdapter.EspDevice existingDevice : deviceList) {
                    if (existingDevice.getIpAddress().equals(ip)) {
                        deviceExists = true;
                        break;
                    }
                }

                if (!deviceExists) {
                    deviceList.add(newDevice);
                    updateUI();
                }
            });
        }
    }

    private void updateUI() {
        // This method must be called from the main thread
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
    }

    private void stopDiscovery() {
        if (discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception e) {
                Log.e(TAG, "Error stopping discovery listener.", e);
            }
            discoveryListener = null;
        }

        // Unregister modern callbacks
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            synchronized (activeCallbacks) {
                for (NsdManager.ServiceInfoCallback callback : activeCallbacks) {
                    try {
                        nsdManager.unregisterServiceInfoCallback(callback);
                    } catch (Exception e) {
                        Log.e(TAG, "Error unregistering service info callback.", e);
                    }
                }
                activeCallbacks.clear();
            }
        }
    }
}