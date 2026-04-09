package com.example.esp32manager;

import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ESP_Scanner";
    private static final String SERVICE_TYPE = "_http._tcp.";

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private SwipeRefreshLayout swipeRefresh;
    private DeviceAdapter adapter;
    private List<EspDevice> deviceList = new ArrayList<>();
    private WifiManager.MulticastLock multicastLock;
    private ExecutorService executorService;

    // Queue for Android 13 and below to prevent "Already resolving" crashes
    private final Queue<NsdServiceInfo> resolveQueue = new LinkedList<>();
    private boolean isResolving = false;
    
    // For API 34+
    private final List<NsdManager.ServiceInfoCallback> activeCallbacks = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        executorService = Executors.newSingleThreadExecutor();

        // Required on Samsung S-series to receive mDNS packets reliably
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("esp32_multicast_lock");
            multicastLock.setReferenceCounted(true);
            multicastLock.acquire();
        }

        swipeRefresh = findViewById(R.id.swipeRefresh);
        RecyclerView recyclerView = findViewById(R.id.recyclerView);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DeviceAdapter(deviceList, new DeviceAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(EspDevice device) {
                Intent intent = new Intent(MainActivity.this, WebViewActivity.class);
                intent.putExtra("URL", device.getUrl());
                intent.putExtra("NAME", device.getName());
                startActivity(intent);
            }
        });
        recyclerView.setAdapter(adapter);

        swipeRefresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                restartDiscovery();
            }
        });

        startDiscovery();
    }

    private void restartDiscovery() {
        stopDiscovery();
        deviceList.clear();
        adapter.notifyDataSetChanged();
        startDiscovery();
        swipeRefresh.setRefreshing(false);
    }

    private void startDiscovery() {
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
                Log.d(TAG, "Service discovery started");
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                Log.d(TAG, "Service found: " + service.getServiceName());
                if (service.getServiceType().contains("_http._tcp")) {
                    handleServiceResolution(service);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
                Log.e(TAG, "service lost: " + service);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i(TAG, "Discovery stopped: " + serviceType);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery failed: Error code:" + errorCode);
                nsdManager.stopServiceDiscovery(this);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery failed: Error code:" + errorCode);
                nsdManager.stopServiceDiscovery(this);
            }
        };

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    private void handleServiceResolution(NsdServiceInfo service) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ (API 34)
            NsdManager.ServiceInfoCallback callback = new NsdManager.ServiceInfoCallback() {
                @Override
                public void onServiceUpdated(@NonNull NsdServiceInfo serviceInfo) {
                    addDeviceToList(serviceInfo);
                }
                @Override
                public void onServiceInfoCallbackRegistrationFailed(int errorCode) {}
                @Override
                public void onServiceInfoCallbackUnregistered() {}
                @Override
                public void onServiceLost() {}
            };
            activeCallbacks.add(callback);
            nsdManager.registerServiceInfoCallback(service, executorService, callback);
        } else {
            // Android 13 and below Queue Fallback
            synchronized (resolveQueue) {
                resolveQueue.add(service);
                resolveNextInQueue();
            }
        }
    }

    private void resolveNextInQueue() {
        synchronized (resolveQueue) {
            if (isResolving || resolveQueue.isEmpty()) {
                return;
            }
            isResolving = true;
            NsdServiceInfo nextService = resolveQueue.poll();
            
            try {
                nsdManager.resolveService(nextService, new NsdManager.ResolveListener() {
                    @Override
                    public void onServiceResolved(NsdServiceInfo serviceInfo) {
                        addDeviceToList(serviceInfo);
                        finishResolving();
                    }

                    @Override
                    public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                        Log.e(TAG, "Resolve failed: " + errorCode);
                        finishResolving();
                    }
                });
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Error resolving service", e);
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
            String ip = serviceInfo.getHost().getHostAddress();
            String name = serviceInfo.getServiceName();
            int port = serviceInfo.getPort();

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // Check for duplicates
                    for (int i = 0; i < deviceList.size(); i++) {
                        if (deviceList.get(i).getIpAddress().equals(ip)) {
                            // Update existing entry name if changed
                            deviceList.set(i, new EspDevice(name, ip, port));
                            adapter.notifyItemChanged(i);
                            return;
                        }
                    }
                    // Add new entry
                    deviceList.add(new EspDevice(name, ip, port));
                    adapter.notifyItemInserted(deviceList.size() - 1);
                }
            });
        }
    }

    private void stopDiscovery() {
        if (discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (IllegalArgumentException ignored) {
                // Listener was never registered or already stopped
            }
            discoveryListener = null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            for (NsdManager.ServiceInfoCallback callback : activeCallbacks) {
                try {
                    nsdManager.unregisterServiceInfoCallback(callback);
                } catch (Exception ignored) {}
            }
            activeCallbacks.clear();
        }
    }

    @Override
    protected void onDestroy() {
        stopDiscovery();
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        if (executorService != null) {
            executorService.shutdown();
        }
        super.onDestroy();
    }
}
