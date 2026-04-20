package com.example.mybasicapp.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.mybasicapp.R;
import java.util.List;

public class EspDeviceAdapter extends RecyclerView.Adapter<EspDeviceAdapter.ViewHolder> {

    private final List<EspDevice> devices;
    private final OnDeviceClickListener listener;
    private final OnDeviceLongClickListener longClickListener;

    public interface OnDeviceClickListener {
        void onDeviceClick(EspDevice device);
    }

    public interface OnDeviceLongClickListener {
        void onDeviceLongClick(EspDevice device);
    }

    public static class EspDevice {
        private final String displayName;
        private final String originalName;
        private final String ipAddress;
        private final int port;

        public EspDevice(String displayName, String originalName, String ipAddress, int port) {
            this.displayName = displayName;
            this.originalName = originalName;
            this.ipAddress = ipAddress;
            this.port = port;
        }

        public String getName() { return displayName; }
        public String getOriginalName() { return originalName; }
        public String getIpAddress() { return ipAddress; }
        public String getUrl() { return "http://" + ipAddress + ":" + port; }
    }

    public EspDeviceAdapter(List<EspDevice> devices, OnDeviceClickListener listener, OnDeviceLongClickListener longClickListener) {
        this.devices = devices;
        this.listener = listener;
        this.longClickListener = longClickListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_esp_device, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        EspDevice device = devices.get(position);
        holder.tvName.setText(device.getName());
        holder.tvIp.setText(device.getIpAddress());

        holder.itemView.setOnClickListener(v -> listener.onDeviceClick(device));

        holder.itemView.setOnLongClickListener(v -> {
            longClickListener.onDeviceLongClick(device);
            return true; // Consume the long-click
        });
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName, tvIp;
        public ViewHolder(View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvDeviceName);
            tvIp = itemView.findViewById(R.id.tvDeviceIp);
        }
    }
}