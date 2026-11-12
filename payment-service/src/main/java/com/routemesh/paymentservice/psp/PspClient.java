package com.routemesh.paymentservice.psp;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PspClient {

    record CreatedPayment(String id, String checkoutUrl) {
    }

    /** PSP'nin kendi kaydı (mutabakat raporu satırı). */
    record PspPayment(String id, String reference, String status, long amountMinor, long capturedMinor,
                      long refundedMinor, Instant createdAt, Instant updatedAt) {
    }

    record PspReportPage(List<PspPayment> items, String nextCursor) {
    }

    CreatedPayment createPayment(String idempotencyKey, long amountMinor, String currency, String callbackUrl,
                                 String reference);

    String capture(String pspPaymentId, String idempotencyKey);

    String voidPayment(String pspPaymentId, String idempotencyKey);

    String refund(String pspPaymentId, String idempotencyKey, long amountMinor);

    /** PSP'nin {@code updatedFrom} (dahil) ile {@code updatedTo} (hariç) arasında güncellenen ödemeleri, sayfa sayfa. */
    PspReportPage listPayments(Instant updatedFrom, Instant updatedTo, int limit, String cursor);

    /** Tek ödemeyi PSP'den okur; PSP bilmiyorsa boş döner. */
    Optional<PspPayment> findPayment(String pspPaymentId);
}
