package com.routemesh.locationservice.tracking;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** İzleme yok ya da çağıranın değil; ikisi de aynı yanıtı verir ki başkasının gönderisi sızmasın. */
@ResponseStatus(HttpStatus.NOT_FOUND)
class TrackingNotFoundException extends RuntimeException {

    TrackingNotFoundException() {
        super("tracking not found");
    }
}
