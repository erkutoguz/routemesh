package com.routemesh.paymentservice.ledger;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

public class ServiceTokenSupplier implements Supplier<String> {

    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(30);

    private final AuthProperties properties;
    private final RestClient restClient;
    private String token;
    private Instant refreshAt = Instant.EPOCH;

    public ServiceTokenSupplier(AuthProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    @Override
    public synchronized String get() {
        if (token == null || !Instant.now().isBefore(refreshAt)) {
            fetch();
        }
        return token;
    }

    @SuppressWarnings("unchecked")
    private void fetch() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        Map<String, Object> response = restClient.post()
                .uri(properties.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        if (response == null || !(response.get("access_token") instanceof String accessToken)) {
            throw new IllegalStateException("token endpoint returned no access_token");
        }
        long expiresIn = response.get("expires_in") instanceof Number n ? n.longValue() : 60;
        token = accessToken;
        refreshAt = Instant.now().plusSeconds(expiresIn).minus(REFRESH_MARGIN);
    }
}
