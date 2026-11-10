package com.routemesh.fleetservice.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.routemesh.fleetservice.service.FleetException;

@RestControllerAdvice
class RestExceptionHandler {

    @ExceptionHandler(FleetException.class)
    ProblemDetail handle(FleetException e) {
        HttpStatus status = switch (e.getReason()) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case PRECONDITION_FAILED, CONFLICT -> HttpStatus.CONFLICT;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ProblemDetail.forStatusAndDetail(status, e.getMessage());
    }
}
