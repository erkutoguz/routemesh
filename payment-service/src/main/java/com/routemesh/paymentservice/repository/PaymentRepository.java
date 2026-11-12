package com.routemesh.paymentservice.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.routemesh.paymentservice.domain.CreatePaymentCommand;
import com.routemesh.paymentservice.domain.PaymentIntent;
import com.routemesh.paymentservice.domain.PaymentStatus;
import com.routemesh.paymentservice.domain.PaymentTransaction;
import com.routemesh.paymentservice.domain.TxnStatus;
import com.routemesh.paymentservice.domain.TxnType;

@Repository
public class PaymentRepository {

    public record StoredIntent(PaymentIntent intent, String requestHash) {
    }

    private static final String INTENT_COLUMNS = "id, shipment_id, amount_minor, currency, status, psp_payment_id, "
            + "checkout_url, captured_minor, refunded_minor, failure_reason, version, expires_at, created_at, "
            + "updated_at, request_hash";

    private static final String TXN_COLUMNS = "id, intent_id, type, command_key, request_hash, amount_minor, status, "
            + "reason, psp_reference, last_error, attempts";

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoredIntent> findByIdempotencyKey(String key) {
        return jdbc.sql("SELECT " + INTENT_COLUMNS + " FROM payment_intents WHERE idempotency_key = :key")
                .param("key", key)
                .query((rs, rowNum) -> mapStoredIntent(rs))
                .optional();
    }

    public Optional<PaymentIntent> findIntentById(UUID id) {
        return jdbc.sql("SELECT " + INTENT_COLUMNS + " FROM payment_intents WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> mapStoredIntent(rs).intent())
                .optional();
    }

    /** Gönderinin yakalanmış (ya da sonradan iade edilmiş) en son ödemesi. */
    public Optional<PaymentIntent> findCapturedIntentByShipment(UUID shipmentId) {
        return jdbc.sql("SELECT " + INTENT_COLUMNS + " FROM payment_intents WHERE shipment_id = :shipment "
                        + "AND status IN ('CAPTURED', 'PARTIALLY_REFUNDED', 'REFUNDED') "
                        + "ORDER BY created_at DESC LIMIT 1")
                .param("shipment", shipmentId)
                .query((rs, rowNum) -> mapStoredIntent(rs).intent())
                .optional();
    }

    public Optional<PaymentIntent> findIntentByIdForUpdate(UUID id) {
        return jdbc.sql("SELECT " + INTENT_COLUMNS + " FROM payment_intents WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query((rs, rowNum) -> mapStoredIntent(rs).intent())
                .optional();
    }

    public Optional<PaymentIntent> findIntentByPspIdForUpdate(String pspPaymentId) {
        return jdbc.sql("SELECT " + INTENT_COLUMNS + " FROM payment_intents WHERE psp_payment_id = :psp FOR UPDATE")
                .param("psp", pspPaymentId)
                .query((rs, rowNum) -> mapStoredIntent(rs).intent())
                .optional();
    }

    public Optional<PaymentIntent> insertIntent(UUID id, CreatePaymentCommand command, String requestHash,
                                                String pspPaymentId, String checkoutUrl, long ttlSeconds) {
        return jdbc.sql("INSERT INTO payment_intents (id, idempotency_key, request_hash, shipment_id, amount_minor, "
                        + "currency, status, psp_payment_id, checkout_url, expires_at) "
                        + "VALUES (:id, :key, :hash, :shipment, :amount, :currency, 'CREATED', :psp, :checkout, "
                        + "now() + (:ttl * interval '1 second')) "
                        + "ON CONFLICT (idempotency_key) DO NOTHING RETURNING " + INTENT_COLUMNS)
                .param("id", id)
                .param("key", command.idempotencyKey())
                .param("hash", requestHash)
                .param("shipment", command.shipmentId())
                .param("amount", command.amountMinor())
                .param("currency", command.currency())
                .param("psp", pspPaymentId)
                .param("checkout", checkoutUrl)
                .param("ttl", ttlSeconds)
                .query((rs, rowNum) -> mapStoredIntent(rs).intent())
                .optional();
    }

    public void updateIntent(UUID id, PaymentStatus status, long capturedMinor, long refundedMinor,
                             String failureReason) {
        jdbc.sql("UPDATE payment_intents SET status = :status, captured_minor = :captured, "
                        + "refunded_minor = :refunded, failure_reason = :reason, version = version + 1, "
                        + "updated_at = now() WHERE id = :id")
                .param("id", id)
                .param("status", status.name())
                .param("captured", capturedMinor)
                .param("refunded", refundedMinor)
                .param("reason", failureReason, Types.VARCHAR)
                .update();
    }

    public List<UUID> findExpirableIntentIds(long graceSeconds, int limit) {
        return jdbc.sql("SELECT id FROM payment_intents WHERE status = 'CREATED' "
                        + "AND expires_at < now() - (:grace * interval '1 second') ORDER BY expires_at LIMIT :limit")
                .param("grace", graceSeconds)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    public Optional<PaymentTransaction> insertTransaction(UUID intentId, TxnType type, String commandKey,
                                                          String requestHash, long amountMinor, String reason) {
        return jdbc.sql("INSERT INTO payment_transactions (intent_id, type, command_key, request_hash, "
                        + "amount_minor, status, reason) VALUES (:intent, :type, :key, :hash, :amount, 'PENDING', "
                        + ":reason) ON CONFLICT (intent_id, type, command_key) DO NOTHING RETURNING " + TXN_COLUMNS)
                .param("intent", intentId)
                .param("type", type.name())
                .param("key", commandKey)
                .param("hash", requestHash)
                .param("amount", amountMinor)
                .param("reason", reason, Types.VARCHAR)
                .query((rs, rowNum) -> mapTransaction(rs))
                .optional();
    }

    public Optional<PaymentTransaction> findTransaction(UUID intentId, TxnType type, String commandKey) {
        return jdbc.sql("SELECT " + TXN_COLUMNS + " FROM payment_transactions "
                        + "WHERE intent_id = :intent AND type = :type AND command_key = :key")
                .param("intent", intentId)
                .param("type", type.name())
                .param("key", commandKey)
                .query((rs, rowNum) -> mapTransaction(rs))
                .optional();
    }

    public Optional<PaymentTransaction> findTransactionById(UUID id) {
        return jdbc.sql("SELECT " + TXN_COLUMNS + " FROM payment_transactions WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> mapTransaction(rs))
                .optional();
    }

    public List<PaymentTransaction> findOpenTransactions(UUID intentId, TxnType type) {
        return jdbc.sql("SELECT " + TXN_COLUMNS + " FROM payment_transactions WHERE intent_id = :intent "
                        + "AND type = :type AND status IN ('PENDING', 'IN_PROGRESS', 'UNKNOWN') ORDER BY created_at")
                .param("intent", intentId)
                .param("type", type.name())
                .query((rs, rowNum) -> mapTransaction(rs))
                .list();
    }

    public boolean hasLiveTransaction(UUID intentId, TxnType type) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM payment_transactions WHERE intent_id = :intent "
                        + "AND type = :type AND status IN ('PENDING', 'IN_PROGRESS', 'UNKNOWN', 'SUCCEEDED'))")
                .param("intent", intentId)
                .param("type", type.name())
                .query(Boolean.class)
                .single();
    }

    public long refundsCommitted(UUID intentId) {
        return jdbc.sql("SELECT COALESCE(SUM(amount_minor), 0) FROM payment_transactions WHERE intent_id = :intent "
                        + "AND type = 'REFUND' AND status IN ('PENDING', 'IN_PROGRESS', 'UNKNOWN', 'SUCCEEDED')")
                .param("intent", intentId)
                .query(Long.class)
                .single();
    }

    public Optional<PaymentTransaction> claimTransaction(UUID id) {
        return jdbc.sql("UPDATE payment_transactions SET status = 'IN_PROGRESS', attempts = attempts + 1, "
                        + "claimed_at = now(), updated_at = now() WHERE id = :id AND (status IN ('PENDING', 'UNKNOWN') "
                        + "OR (status = 'IN_PROGRESS' AND claimed_at < now() - interval '60 seconds')) "
                        + "RETURNING " + TXN_COLUMNS)
                .param("id", id)
                .query((rs, rowNum) -> mapTransaction(rs))
                .optional();
    }

    public void finishTransaction(UUID id, TxnStatus status, String pspReference, String lastError) {
        jdbc.sql("UPDATE payment_transactions SET status = :status, psp_reference = COALESCE(:psp, psp_reference), "
                        + "last_error = :error, claimed_at = NULL, updated_at = now() WHERE id = :id")
                .param("id", id)
                .param("status", status.name())
                .param("psp", pspReference, Types.VARCHAR)
                .param("error", lastError, Types.VARCHAR)
                .update();
    }

    public List<UUID> findRetryableTransactionIds(long retryAfterSeconds, int maxAttempts, int limit) {
        return jdbc.sql("SELECT id FROM payment_transactions WHERE attempts < :max AND "
                        + "((status IN ('PENDING', 'UNKNOWN') AND updated_at < now() - (:after * interval '1 second')) "
                        + "OR (status = 'IN_PROGRESS' AND claimed_at < now() - interval '60 seconds')) "
                        + "ORDER BY updated_at LIMIT :limit")
                .param("max", maxAttempts)
                .param("after", retryAfterSeconds)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    public Optional<UUID> insertWebhookEvent(String pspEventId, String eventType, String pspPaymentId,
                                             String payload) {
        return jdbc.sql("INSERT INTO webhook_events (psp_event_id, event_type, psp_payment_id, payload) "
                        + "VALUES (:event, :type, :payment, :payload) ON CONFLICT (psp_event_id) DO NOTHING "
                        + "RETURNING id")
                .param("event", pspEventId)
                .param("type", eventType)
                .param("payment", pspPaymentId, Types.VARCHAR)
                .param("payload", payload)
                .query(UUID.class)
                .optional();
    }

    public void setWebhookOutcome(UUID id, String outcome) {
        jdbc.sql("UPDATE webhook_events SET outcome = :outcome WHERE id = :id")
                .param("id", id)
                .param("outcome", outcome)
                .update();
    }

    public void insertOutbox(UUID eventId, String aggregateType, String aggregateId, String type, String payload) {
        jdbc.sql("INSERT INTO outbox_events (id, aggregate_type, aggregate_id, type, payload) "
                        + "VALUES (:id, :aggregateType, :aggregateId, :type, CAST(:payload AS jsonb))")
                .param("id", eventId)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("type", type)
                .param("payload", payload)
                .update();
    }

    public int deleteOutboxOlderThan(Instant cutoff, int batchSize) {
        return jdbc.sql("DELETE FROM outbox_events WHERE id IN "
                        + "(SELECT id FROM outbox_events WHERE created_at < :cutoff ORDER BY created_at LIMIT :batch)")
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .param("batch", batchSize)
                .update();
    }

    /** Mutabakat penceresi: {@code updated_at} artan sırada, {@code from} dahil ve {@code to} hariç, anahtar kümeli. */
    public List<PaymentIntent> findIntentsUpdatedBetween(Instant from, Instant to, Instant afterUpdatedAt,
                                                         UUID afterId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + INTENT_COLUMNS + " FROM payment_intents "
                + "WHERE updated_at >= :from AND updated_at < :to");
        if (afterUpdatedAt != null) {
            sql.append(" AND (updated_at, id) > (:afterAt, :afterId)");
        }
        sql.append(" ORDER BY updated_at, id LIMIT :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString())
                .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .param("limit", limit);
        if (afterUpdatedAt != null) {
            spec = spec.param("afterAt", OffsetDateTime.ofInstant(afterUpdatedAt, ZoneOffset.UTC))
                    .param("afterId", afterId);
        }
        return spec.query((rs, n) -> mapStoredIntent(rs).intent()).list();
    }

    /** Gönderilerin tüm ödeme niyetleri (başarısız ve süresi dolmuş denemeler dahil). */
    public List<PaymentIntent> findIntentsByShipmentIds(java.util.Collection<UUID> shipmentIds) {
        if (shipmentIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + INTENT_COLUMNS + " FROM payment_intents WHERE shipment_id IN (:ids)")
                .param("ids", shipmentIds)
                .query((rs, n) -> mapStoredIntent(rs).intent())
                .list();
    }

    public List<PaymentIntent> findIntentsByPspIds(java.util.Collection<String> pspPaymentIds) {
        if (pspPaymentIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + INTENT_COLUMNS + " FROM payment_intents WHERE psp_payment_id IN (:ids)")
                .param("ids", pspPaymentIds)
                .query((rs, n) -> mapStoredIntent(rs).intent())
                .list();
    }

    private static StoredIntent mapStoredIntent(ResultSet rs) throws SQLException {
        PaymentIntent intent = new PaymentIntent(
                rs.getObject("id", UUID.class),
                rs.getObject("shipment_id", UUID.class),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                PaymentStatus.valueOf(rs.getString("status")),
                rs.getString("psp_payment_id"),
                rs.getString("checkout_url"),
                rs.getLong("captured_minor"),
                rs.getLong("refunded_minor"),
                rs.getString("failure_reason"),
                rs.getLong("version"),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
        return new StoredIntent(intent, rs.getString("request_hash"));
    }

    private static PaymentTransaction mapTransaction(ResultSet rs) throws SQLException {
        return new PaymentTransaction(
                rs.getObject("id", UUID.class),
                rs.getObject("intent_id", UUID.class),
                TxnType.valueOf(rs.getString("type")),
                rs.getString("command_key"),
                rs.getString("request_hash"),
                rs.getLong("amount_minor"),
                TxnStatus.valueOf(rs.getString("status")),
                rs.getString("reason"),
                rs.getString("psp_reference"),
                rs.getString("last_error"),
                rs.getInt("attempts"));
    }
}
