package com.routemesh.locationservice.tracking;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
class TooManyStreamsException extends RuntimeException {

    TooManyStreamsException(String message) {
        super(message);
    }
}
