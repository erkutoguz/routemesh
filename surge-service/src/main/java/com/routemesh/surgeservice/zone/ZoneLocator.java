package com.routemesh.surgeservice.zone;

import java.util.Optional;

public interface ZoneLocator {

    /** @return noktanın fiyat bölgesi; hizmet bölgesi dışında ya da bölge bilgisi alınamıyorsa boş */
    Optional<String> locate(double latitude, double longitude);
}
