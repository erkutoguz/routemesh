package com.routemesh.shipmentservice.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.routemesh.shipmentservice.service.ShipmentException;

@RestControllerAdvice
class RestExceptionHandler {

    @ExceptionHandler(ShipmentException.class)
    ProblemDetail handle(ShipmentException e) {
        HttpStatus status = switch (e.getReason()) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ILLEGAL_TRANSITION, IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
        };
        return ProblemDetail.forStatusAndDetail(status, e.getMessage());
    }
}
