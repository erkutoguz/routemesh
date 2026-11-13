package com.routemesh.gateway;

import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.net.UnknownHostException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

/**
 * Upstream'e ulaşılamadığında gateway'in 500 yerine anlamlı bir Problem Details dönmesini sağlar:
 * bağlantı reddedildi/çözülemedi → 503, zaman aşımı → 504, diğer G/Ç hataları → 502.
 */
@RestControllerAdvice
class UpstreamFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(UpstreamFailureHandler.class);

    @ExceptionHandler({ResourceAccessException.class, UncheckedIOException.class})
    ProblemDetail upstreamFailure(RuntimeException e) {
        HttpStatus status = statusFor(e);
        log.warn("Upstream call failed ({}): {}", status.value(), e.toString());
        String detail = switch (status) {
            case SERVICE_UNAVAILABLE -> "The upstream service is unavailable.";
            case GATEWAY_TIMEOUT -> "The upstream service did not respond in time.";
            default -> "The upstream service returned an invalid response.";
        };
        return ProblemDetail.forStatusAndDetail(status, detail);
    }

    static HttpStatus statusFor(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return HttpStatus.GATEWAY_TIMEOUT;
            }
            if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
                return HttpStatus.SERVICE_UNAVAILABLE;
            }
        }
        return HttpStatus.BAD_GATEWAY;
    }
}
