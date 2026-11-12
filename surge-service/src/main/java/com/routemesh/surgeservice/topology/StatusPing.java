package com.routemesh.surgeservice.topology;

/** Konum paketi + o andaki kurye durumu (IDLE/BUSY/OFFLINE). */
public record StatusPing(double latitude, double longitude, long timestamp, String status) {
}
