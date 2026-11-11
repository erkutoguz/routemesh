package com.routemesh.shipmentservice.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.routemesh.shipmentservice.service.PodTooFarException;
import com.routemesh.shipmentservice.service.ProofOfDeliveryService;
import com.routemesh.shipmentservice.service.ShipmentException;

@RestControllerAdvice
class RestExceptionHandler {

    @ExceptionHandler(ShipmentException.class)
    ProblemDetail handle(ShipmentException e) {
        HttpStatus status = switch (e.getReason()) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ILLEGAL_TRANSITION, IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
            case POD_TOO_FAR -> HttpStatus.UNPROCESSABLE_ENTITY;
            case OTP_INVALID -> HttpStatus.FORBIDDEN;
            case OTP_LOCKED -> HttpStatus.LOCKED;
            case PAYLOAD_TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            case UNSUPPORTED_MEDIA -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case STORAGE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        if (e instanceof PodTooFarException tooFar) {
            problem.setProperty("distanceMeters", tooFar.getDistanceMeters());
            problem.setProperty("maxDistanceMeters", ProofOfDeliveryService.MAX_DISTANCE_METERS);
        }
        return problem;
    }
}
