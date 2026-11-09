package com.routemesh.paymentservice.webhook;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.routemesh.paymentservice.webhook.WebhookService.Result;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class WebhookController {

    static final String SIGNATURE_HEADER = "X-PSP-Signature";

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookSignatureVerifier verifier;
    private final WebhookService service;
    private final JsonMapper jsonMapper;

    public WebhookController(WebhookSignatureVerifier verifier, WebhookService service, JsonMapper jsonMapper) {
        this.verifier = verifier;
        this.service = service;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping(value = "/api/v1/webhooks/psp", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> receive(
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            @RequestBody byte[] body) {
        if (!verifier.verify(body, signature)) {
            log.warn("Rejected a webhook with an invalid or missing signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        JsonNode event;
        try {
            event = jsonMapper.readTree(body);
        } catch (JacksonException e) {
            return ResponseEntity.badRequest().build();
        }
        try {
            Result result = service.process(event, new String(body, StandardCharsets.UTF_8));
            return result == Result.RETRY_LATER
                    ? ResponseEntity.status(HttpStatus.CONFLICT).build()
                    : ResponseEntity.ok().build();
        } catch (MalformedWebhookException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
