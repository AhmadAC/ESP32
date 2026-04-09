package com.example.mybasicapp; // <-- CORRECTED

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.mybasicapp.model.EspDevice; // <-- CORRECTED (assuming you move the model)

import java.util.List;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.ViewHolder> {

    private final List<EspDevice> devices;
    private final OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(EspDevice device);
    }

    public DeviceAdapter(List<EspDevice> devices, OnItemClickListener listener) {
        this.devices = devices;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_esp_device, parent, false); // <-- Use correct layout name
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        EspDevice device = devices.get(position);
        holder.tvName.setText(device.getName());
        holder.tvIp.setText(device.getAddress()); // <-- Use getAddress() from the correct EspDevice model
        
        holder.itemView.setOnClickListener(v -> listener.onItemClick(device));
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