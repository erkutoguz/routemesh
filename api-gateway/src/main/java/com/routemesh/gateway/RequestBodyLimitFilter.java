package com.routemesh.gateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Bildirilen {@code Content-Length} sınırı aşıyorsa gövde okunmadan 413 döner: genel uçlar için 1 MB, PoD dosya
 * yükleme yolu için 6 MB. Kimlik doğrulamadan önce çalışır. Sınırlama: {@code Content-Length} göndermeyen (chunked)
 * istekler kapsanmaz.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestBodyLimitFilter extends OncePerRequestFilter {

    private static final Pattern POD_UPLOAD = Pattern.compile("^/api/v1/shipments/[^/]+/pod/files$");

    private final long maxBody;
    private final long maxUploadBody;

    RequestBodyLimitFilter(@Value("${routemesh.gateway.max-body:1MB}") DataSize maxBody,
            @Value("${routemesh.gateway.max-upload-body:6MB}") DataSize maxUploadBody) {
        this.maxBody = maxBody.toBytes();
        this.maxUploadBody = maxUploadBody.toBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long limit = isPodUpload(request) ? maxUploadBody : maxBody;
        if (request.getContentLengthLong() > limit) {
            response.setStatus(413);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Payload Too Large\",\"status\":413,"
                    + "\"detail\":\"The request body must be at most " + limit + " bytes.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isPodUpload(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod()) && POD_UPLOAD.matcher(request.getRequestURI()).matches();
    }
}
