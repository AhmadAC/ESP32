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

    public interface OnDeviceClickListener {
        void onDeviceClick(EspDevice device);
    }

    public static class EspDevice {
        private final String name, ip;
        private final int port;
        public EspDevice(String name, String ip, int port) { this.name = name; this.ip = ip; this.port = port; }
        public String getName() { return name; }
        public String getIpAddress() { return ip; }
        public String getUrl() { return "http://" + ip + ":" + port; }
    }

    public EspDeviceAdapter(List<EspDevice> devices, OnDeviceClickListener listener) {
        this.devices = devices;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_esp_device, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        EspDevice device = devices.get(position);
        holder.name.setText(device.getName());
        holder.ip.setText(device.getIpAddress());
        holder.itemView.setOnClickListener(v -> listener.onDeviceClick(device));
    }

    @Override
    public int getItemCount() { return devices.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name, ip;
        ViewHolder(View v) { super(v); name = v.findViewById(R.id.tvDeviceName); ip = v.findViewById(R.id.tvDeviceIp); }
    }
}
