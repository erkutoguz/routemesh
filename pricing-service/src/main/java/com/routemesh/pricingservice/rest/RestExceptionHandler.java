package com.routemesh.pricingservice.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.routemesh.pricingservice.service.PricingException;

@RestControllerAdvice
class RestExceptionHandler {

    @ExceptionHandler(PricingException.class)
    ProblemDetail handle(PricingException e) {
        HttpStatus status = switch (e.getReason()) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case OUT_OF_SERVICE_AREA -> HttpStatus.UNPROCESSABLE_ENTITY;
            case QUOTE_EXPIRED, IDEMPOTENCY_CONFLICT, ALREADY_REDEEMED -> HttpStatus.CONFLICT;
        };
        return ProblemDetail.forStatusAndDetail(status, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "request body is not valid JSON");
    }
}
