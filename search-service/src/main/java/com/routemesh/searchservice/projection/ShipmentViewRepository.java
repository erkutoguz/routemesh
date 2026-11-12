package com.routemesh.searchservice.projection;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Repository
public class ShipmentViewRepository {

    private final JdbcClient jdbc;

    public ShipmentViewRepository(JdbcClient jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        // Durum olayı oluşturma olayından önce gelirse açılan taslak satırlar; sayı sürekli büyüyorsa oluşturma
        // olayları ulaşmıyor demektir
        Gauge.builder("routemesh.search.view.skeleton.rows", this, repository -> {
            try {
                return repository.countSkeletons();
            } catch (RuntimeException e) {
                return Double.NaN;
            }
        }).register(meters);
    }

    long countSkeletons() {
        return jdbc.sql("SELECT count(*) FROM shipment_view WHERE merchant_id IS NULL").query(Long.class).single();
    }

    /**
     * Gönderi oluştu. Satır taslak olarak zaten varsa (durum olayı önce geldi) yalnızca eksik alanları
     * (satıcı, tutar, para birimi, oluşturulma zamanı) tamamlar; durum, sürüm ve kurye ezilmez.
     */
    public void applyCreated(UUID id, UUID merchantId, String status, long amountMinor, String currency,
                             Instant occurredAt) {
        jdbc.sql("INSERT INTO shipment_view (id, merchant_id, status, version, amount_minor, currency, created_at, "
                        + "updated_at) VALUES (:id, :merchant, :status, 0, :amount, :currency, :at, :at) "
                        + "ON CONFLICT (id) DO UPDATE SET merchant_id = EXCLUDED.merchant_id, "
                        + "amount_minor = EXCLUDED.amount_minor, currency = EXCLUDED.currency, "
                        + "created_at = EXCLUDED.created_at")
                .param("id", id)
                .param("merchant", merchantId)
                .param("status", status)
                .param("amount", amountMinor)
                .param("currency", currency)
                .param("at", Timestamp.from(occurredAt))
                .update();
    }

    /**
     * Durum değişti. Satır yoksa taslak açar. Durum, kurye ve güncelleme zamanı yalnızca olay sürümü kayıtlıdan
     * büyükse uygulanır; sürüm hiçbir zaman geri gitmez. {@code delivered_at} ilk {@code DELIVERED} olayından
     * alınır ve sonradan gelen eski sürüm de olsa korunur.
     */
    public void applyStatusChanged(UUID id, String toStatus, UUID courierId, long version, Instant occurredAt) {
        Timestamp at = Timestamp.from(occurredAt);
        jdbc.sql("INSERT INTO shipment_view (id, status, courier_id, version, created_at, updated_at, delivered_at) "
                        + "VALUES (:id, :status, :courier, :version, :at, :at, :delivered) "
                        + "ON CONFLICT (id) DO UPDATE SET "
                        + "status = CASE WHEN shipment_view.version < EXCLUDED.version THEN EXCLUDED.status "
                        + "ELSE shipment_view.status END, "
                        + "courier_id = CASE WHEN shipment_view.version < EXCLUDED.version THEN EXCLUDED.courier_id "
                        + "ELSE shipment_view.courier_id END, "
                        + "updated_at = CASE WHEN shipment_view.version < EXCLUDED.version THEN EXCLUDED.updated_at "
                        + "ELSE shipment_view.updated_at END, "
                        + "delivered_at = COALESCE(shipment_view.delivered_at, EXCLUDED.delivered_at), "
                        + "version = GREATEST(shipment_view.version, EXCLUDED.version)")
                .param("id", id)
                .param("status", toStatus)
                .param("courier", courierId)
                .param("version", version)
                .param("at", at)
                .param("delivered", "DELIVERED".equals(toStatus) ? at : null)
                .update();
    }
}
