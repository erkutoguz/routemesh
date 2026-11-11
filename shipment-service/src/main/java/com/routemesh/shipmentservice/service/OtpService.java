package com.routemesh.shipmentservice.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.routemesh.shipmentservice.config.PodProperties;
import com.routemesh.shipmentservice.domain.Shipment;
import com.routemesh.shipmentservice.domain.ShipmentStatus;
import com.routemesh.shipmentservice.repository.PodRepository;
import com.routemesh.shipmentservice.repository.PodRepository.OtpRow;
import com.routemesh.shipmentservice.repository.ShipmentRepository;
import com.routemesh.shipmentservice.service.ShipmentException.Reason;

/**
 * Alıcının teslimat OTP'si. Kod yalnızca üretildiği anda döner; veritabanında sunucu sırrıyla HMAC-SHA256 olarak
 * saklanır. 5 hatalı denemede kod kilitlenir, yenisi üretilmelidir.
 */
@Service
public class OtpService {

    public enum Outcome {
        /** Bu gönderi için OTP üretilmemiş, istenmez. */
        NOT_REQUIRED,
        VERIFIED,
        /** OTP gerekli ama çağıran kod göndermedi (deneme hakkı harcanmaz). */
        MISSING,
        INVALID,
        LOCKED,
        EXPIRED
    }

    public record Issued(String code, Instant expiresAt) {
    }

    static final int MAX_ATTEMPTS = 5;
    private static final Set<ShipmentStatus> FINISHED =
            Set.of(ShipmentStatus.DELIVERED, ShipmentStatus.CANCELLED, ShipmentStatus.LOST);
    private static final int MIN_SECRET_LENGTH = 16;

    private final PodRepository pods;
    private final ShipmentRepository shipments;
    private final PodProperties properties;
    private final SecureRandom random = new SecureRandom();
    private final byte[] secret;

    public OtpService(PodRepository pods, ShipmentRepository shipments, PodProperties properties) {
        this.pods = pods;
        this.shipments = shipments;
        this.properties = properties;
        String configured = properties.otp().secret();
        if (configured == null || configured.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "routemesh.pod.otp.secret must be at least " + MIN_SECRET_LENGTH + " characters");
        }
        this.secret = configured.getBytes(StandardCharsets.UTF_8);
    }

    /** Yeni kod üretir; varsa öncekini geçersiz kılar ve deneme sayacını sıfırlar. */
    @Transactional
    public Issued issue(UUID shipmentId) {
        Shipment shipment = shipments.findById(shipmentId)
                .orElseThrow(() -> new ShipmentException(Reason.NOT_FOUND, "shipment " + shipmentId + " does not exist"));
        if (FINISHED.contains(shipment.status())) {
            throw new ShipmentException(Reason.ILLEGAL_TRANSITION,
                    "no delivery OTP can be issued for a " + shipment.status() + " shipment");
        }
        String code = "%06d".formatted(random.nextInt(1_000_000));
        var expiresAt = pods.upsertOtp(shipmentId, hash(shipmentId, code), properties.otp().ttl().toSeconds());
        return new Issued(code, expiresAt.toInstant());
    }

    /**
     * Ayrı işlemde çalışır: hatalı deneme sayacı, çağıran işlem geri alınsa bile kalıcı olmalı.
     * Sonuç döner, hata fırlatmak çağıranın işidir (fırlatılırsa bu işlem de geri alınırdı).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome verify(UUID shipmentId, String code) {
        OtpRow otp = pods.findOtpForUpdate(shipmentId).orElse(null);
        if (otp == null) {
            return Outcome.NOT_REQUIRED;
        }
        if (otp.verified()) {
            return matches(shipmentId, code, otp) ? Outcome.VERIFIED : Outcome.INVALID;
        }
        if (otp.expired()) {
            return Outcome.EXPIRED;
        }
        if (otp.attempts() >= MAX_ATTEMPTS) {
            return Outcome.LOCKED;
        }
        if (code == null || code.isBlank()) {
            return Outcome.MISSING;
        }
        if (matches(shipmentId, code, otp)) {
            pods.markOtpVerified(shipmentId);
            return Outcome.VERIFIED;
        }
        pods.incrementOtpAttempts(shipmentId);
        return Outcome.INVALID;
    }

    private boolean matches(UUID shipmentId, String code, OtpRow otp) {
        if (code == null) {
            return false;
        }
        return MessageDigest.isEqual(hash(shipmentId, code).getBytes(StandardCharsets.UTF_8),
                otp.codeHash().getBytes(StandardCharsets.UTF_8));
    }

    private String hash(UUID shipmentId, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((shipmentId + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
