package com.routemesh.ledgerservice.integrity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Zincir hash'i: {@code sha256(önceki_hash ‖ kayıt_1 ‖ kayıt_2 ...)}. Kayıtlar id sırasıyla beslenir; böylece
 * eski bir kaydın sonradan değişmesi, o noktadan sonraki tüm checkpoint hash'lerini bozar.
 */
final class ChainHasher {

    static final String GENESIS = HexFormat.of().formatHex(
            newDigest().digest("ledger-genesis".getBytes(StandardCharsets.UTF_8)));

    private final MessageDigest digest = newDigest();

    ChainHasher(String previousHash) {
        digest.update(previousHash.getBytes(StandardCharsets.US_ASCII));
    }

    void add(long entryId, UUID transactionId, UUID accountId, String direction, long amountMinor) {
        digest.update((entryId + "|" + transactionId + "|" + accountId + "|" + direction + "|" + amountMinor + "\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    String finish() {
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
