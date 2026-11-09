package com.routemesh.paymentservice.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.routemesh.paymentservice.config.PaymentProperties;

@Component
public class WebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";

    private final PaymentProperties properties;
    private final Clock clock;

    public WebhookSignatureVerifier(PaymentProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean verify(byte[] body, String header) {
        if (header == null || header.isBlank() || properties.webhookSecrets() == null) {
            return false;
        }
        Long timestamp = null;
        List<byte[]> candidates = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2) {
                continue;
            }
            try {
                if (pair[0].equals("t")) {
                    timestamp = Long.parseLong(pair[1]);
                } else if (pair[0].equals("v1")) {
                    candidates.add(HexFormat.of().parseHex(pair[1]));
                }
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        if (timestamp == null || candidates.isEmpty()) {
            return false;
        }
        long skew = Math.abs(clock.instant().getEpochSecond() - timestamp);
        if (skew > properties.webhookTolerance().toSeconds()) {
            return false;
        }

        byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
        byte[] signedPayload = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, signedPayload, 0, prefix.length);
        System.arraycopy(body, 0, signedPayload, prefix.length, body.length);

        boolean valid = false;
        for (String secret : properties.webhookSecrets()) {
            byte[] expected = hmac(secret, signedPayload);
            for (byte[] candidate : candidates) {
                valid |= MessageDigest.isEqual(expected, candidate);
            }
        }
        return valid;
    }

    static byte[] hmac(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(payload);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
