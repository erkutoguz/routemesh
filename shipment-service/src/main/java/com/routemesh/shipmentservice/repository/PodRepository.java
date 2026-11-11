package com.routemesh.shipmentservice.repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.routemesh.shipmentservice.domain.PodFile;
import com.routemesh.shipmentservice.domain.ProofOfDelivery;

@Repository
public class PodRepository {

    public record OtpRow(String codeHash, int attempts, boolean verified, boolean expired) {
    }

    private static final String POINT = "ST_SetSRID(ST_MakePoint(%s, %s), 4326)::geography";
    private static final String REPORTED = POINT.formatted(":lon", ":lat");
    private static final String DELIVERY = POINT.formatted("s.delivery_longitude", "s.delivery_latitude");

    private static final String FILE_COLUMNS =
            "id, shipment_id, kind, object_key, sha256, size_bytes, content_type, uploaded_by, uploaded_at";
    private static final String PROOF_COLUMNS = "shipment_id, courier_id, delivered_latitude, delivered_longitude, "
            + "distance_meters, accuracy_meters, recipient_name, photo_file_id, signature_file_id, otp_verified, "
            + "recorded_at";

    private final JdbcClient jdbc;

    public PodRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- dosyalar ---

    public void insertFile(PodFile file) {
        jdbc.sql("INSERT INTO pod_files (id, shipment_id, kind, object_key, sha256, size_bytes, content_type, "
                        + "uploaded_by) VALUES (:id, :shipment, :kind, :key, :sha, :size, :type, :by)")
                .param("id", file.id())
                .param("shipment", file.shipmentId())
                .param("kind", file.kind().name())
                .param("key", file.objectKey())
                .param("sha", file.sha256())
                .param("size", file.sizeBytes())
                .param("type", file.contentType())
                .param("by", file.uploadedBy())
                .update();
    }

    public Optional<PodFile> findFile(UUID id) {
        return jdbc.sql("SELECT " + FILE_COLUMNS + " FROM pod_files WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new PodFile(
                        rs.getObject("id", UUID.class),
                        rs.getObject("shipment_id", UUID.class),
                        PodFile.Kind.valueOf(rs.getString("kind")),
                        rs.getString("object_key"),
                        rs.getString("sha256"),
                        rs.getLong("size_bytes"),
                        rs.getString("content_type"),
                        rs.getObject("uploaded_by", UUID.class),
                        rs.getObject("uploaded_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    public int countFiles(UUID shipmentId) {
        return jdbc.sql("SELECT count(*) FROM pod_files WHERE shipment_id = :id")
                .param("id", shipmentId)
                .query(Integer.class)
                .single();
    }

    // --- teslimat kanıtı ---

    /** @return bildirilen noktanın teslimat konumuna uzaklığı (metre) */
    public Optional<Double> distanceToDelivery(UUID shipmentId, double latitude, double longitude) {
        return jdbc.sql("SELECT ST_Distance(" + REPORTED + ", " + DELIVERY + ") FROM shipments s WHERE s.id = :id")
                .param("id", shipmentId)
                .param("lat", latitude)
                .param("lon", longitude)
                .query(Double.class)
                .optional();
    }

    /**
     * 50 m kuralı eklemenin kendi koşuludur: bildirilen nokta teslimat konumuna {@code maxMeters} içinde değilse
     * satır eklenmez.
     *
     * @return eklenen satır sayısı (0: konum çok uzak)
     */
    public int insertProofWithinDistance(UUID shipmentId, UUID courierId, double latitude, double longitude,
            Double accuracyMeters, String recipientName, UUID photoFileId, UUID signatureFileId,
            boolean otpVerified, double maxMeters) {
        return jdbc.sql("INSERT INTO proof_of_delivery (shipment_id, courier_id, delivered_latitude, "
                        + "delivered_longitude, distance_meters, accuracy_meters, recipient_name, photo_file_id, "
                        + "signature_file_id, otp_verified) "
                        + "SELECT s.id, :courier, :lat, :lon, ST_Distance(" + REPORTED + ", " + DELIVERY + "), "
                        + ":accuracy, :name, :photo, :signature, :otp FROM shipments s "
                        + "WHERE s.id = :id AND ST_DWithin(" + REPORTED + ", " + DELIVERY + ", :max)")
                .param("id", shipmentId)
                .param("courier", courierId)
                .param("lat", latitude)
                .param("lon", longitude)
                .param("accuracy", accuracyMeters, Types.DOUBLE)
                .param("name", recipientName)
                .param("photo", photoFileId)
                .param("signature", signatureFileId, Types.OTHER)
                .param("otp", otpVerified)
                .param("max", maxMeters)
                .update();
    }

    public Optional<ProofOfDelivery> findProof(UUID shipmentId) {
        return jdbc.sql("SELECT " + PROOF_COLUMNS + " FROM proof_of_delivery WHERE shipment_id = :id")
                .param("id", shipmentId)
                .query((rs, n) -> {
                    double accuracy = rs.getDouble("accuracy_meters");
                    Double accuracyMeters = rs.wasNull() ? null : accuracy;
                    return new ProofOfDelivery(
                            rs.getObject("shipment_id", UUID.class),
                            rs.getObject("courier_id", UUID.class),
                            rs.getDouble("delivered_latitude"),
                            rs.getDouble("delivered_longitude"),
                            rs.getDouble("distance_meters"),
                            accuracyMeters,
                            rs.getString("recipient_name"),
                            rs.getObject("photo_file_id", UUID.class),
                            rs.getObject("signature_file_id", UUID.class),
                            rs.getBoolean("otp_verified"),
                            rs.getObject("recorded_at", OffsetDateTime.class).toInstant());
                })
                .optional();
    }

    public List<UUID> findProofFileIds(UUID shipmentId) {
        return findProof(shipmentId)
                .map(proof -> proof.signatureFileId() == null
                        ? List.of(proof.photoFileId()) : List.of(proof.photoFileId(), proof.signatureFileId()))
                .orElse(List.of());
    }

    // --- OTP ---

    public OffsetDateTime upsertOtp(UUID shipmentId, String codeHash, long ttlSeconds) {
        return jdbc.sql("INSERT INTO delivery_otps (shipment_id, code_hash, issued_at, expires_at) "
                        + "VALUES (:id, :hash, now(), now() + make_interval(secs => :ttl)) "
                        + "ON CONFLICT (shipment_id) DO UPDATE SET code_hash = EXCLUDED.code_hash, "
                        + "issued_at = EXCLUDED.issued_at, expires_at = EXCLUDED.expires_at, attempts = 0, "
                        + "verified_at = NULL RETURNING expires_at")
                .param("id", shipmentId)
                .param("hash", codeHash)
                .param("ttl", (double) ttlSeconds)
                .query((rs, n) -> rs.getObject("expires_at", OffsetDateTime.class))
                .single();
    }

    public Optional<OtpRow> findOtpForUpdate(UUID shipmentId) {
        return jdbc.sql("SELECT code_hash, attempts, verified_at IS NOT NULL AS verified, expires_at <= now() AS expired "
                        + "FROM delivery_otps WHERE shipment_id = :id FOR UPDATE")
                .param("id", shipmentId)
                .query((rs, n) -> new OtpRow(rs.getString("code_hash"), rs.getInt("attempts"),
                        rs.getBoolean("verified"), rs.getBoolean("expired")))
                .optional();
    }

    public void incrementOtpAttempts(UUID shipmentId) {
        jdbc.sql("UPDATE delivery_otps SET attempts = attempts + 1 WHERE shipment_id = :id")
                .param("id", shipmentId)
                .update();
    }

    public void markOtpVerified(UUID shipmentId) {
        jdbc.sql("UPDATE delivery_otps SET verified_at = now() WHERE shipment_id = :id")
                .param("id", shipmentId)
                .update();
    }
}
