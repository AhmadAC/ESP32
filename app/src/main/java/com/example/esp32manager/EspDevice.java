package com.example.esp32manager;

public class EspDevice {
    private final String name;
    private final String ipAddress;
    private final int port;

    public EspDevice(String name, String ipAddress, int port) {
        this.name = name;
        this.ipAddress = ipAddress;
        this.port = port;
    }

    public String getName() { 
        return name; 
    }
    
    public String getIpAddress() { 
        return ipAddress; 
    }
    
    public String getUrl() { 
        return "http://" + ipAddress + ":" + port; 
    }
}
