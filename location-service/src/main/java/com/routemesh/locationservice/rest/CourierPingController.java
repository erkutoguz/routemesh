package com.routemesh.locationservice.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.routemesh.locationservice.service.CourierLocationService;
import com.routemesh.locationservice.service.InvalidRequestException;

@RestController
@RequestMapping("/api/v1/couriers/me/location")
@PreAuthorize("hasRole('COURIER')")
class CourierPingController {

    record PingRequest(Long sessionEpoch, Long sequenceNumber, Double latitude, Double longitude) {
    }

    record PingResponse(boolean accepted) {
    }

    private final CourierLocationService service;

    CourierPingController(CourierLocationService service) {
        this.service = service;
    }

    @PostMapping
    PingResponse ping(@RequestBody PingRequest request, Authentication authentication) {
        if (request.sessionEpoch() == null || request.sequenceNumber() == null || request.latitude() == null
                || request.longitude() == null) {
            throw new InvalidRequestException("sessionEpoch, sequenceNumber, latitude and longitude are required");
        }
        boolean accepted = service.ingest(authentication.getName(), request.sessionEpoch(),
                request.sequenceNumber(), request.latitude(), request.longitude());
        return new PingResponse(accepted);
    }

    @RestControllerAdvice(assignableTypes = CourierPingController.class)
    static class Errors {

        @ExceptionHandler(InvalidRequestException.class)
        ProblemDetail invalid(InvalidRequestException e) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
        ProblemDetail unreadable() {
            return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "request body is not valid JSON");
        }
    }
}
