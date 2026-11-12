package com.routemesh.ledgerservice.integrity;

import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.routemesh.ledgerservice.domain.Direction;
import com.routemesh.ledgerservice.domain.EntryCommand;
import com.routemesh.ledgerservice.domain.PostTransactionCommand;
import com.routemesh.ledgerservice.service.LedgerService;

/** Testlerin ortak yardımcıları: defteri sıfırlar, normal ve (tetikleyiciler kapatılarak) bozuk kayıt yazar. */
final class IntegrityTestSupport {

    static final String PSP = "ASSET:PSP_CLEARING";
    static final String ESCROW = "LIABILITY:ESCROW";
    static final String FEE = "REVENUE:PLATFORM_FEE";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final LedgerService ledger;

    IntegrityTestSupport(JdbcTemplate jdbc, TransactionTemplate tx, LedgerService ledger) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.ledger = ledger;
    }

    /** Değişmezlik tetikleyicileri tek bağlantıda kapatılarak defter ve denetim tabloları boşaltılır. */
    void reset() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET session_replication_role = replica");
                statement.execute("TRUNCATE integrity_findings, ledger_checkpoints, ledger_balance_cache, "
                        + "integrity_runs, ledger_entries, journal_transactions, outbox_events RESTART IDENTITY CASCADE");
                statement.execute("SET session_replication_role = DEFAULT");
            }
            return null;
        });
    }

    String wallet(String name) {
        return "LIABILITY:" + name + ":" + UUID.randomUUID();
    }

    UUID capture(UUID shipment, long amount) {
        return ledger.post(command("ESCROW_FUNDED", shipment, debit(PSP, amount), credit(ESCROW, amount))).id();
    }

    void settle(UUID shipment, long amount, String merchantWallet, String courierWallet) {
        long merchant = amount * 75 / 100;
        long courier = amount * 18 / 100;
        ledger.post(command("DELIVERY_SETTLEMENT", shipment, debit(ESCROW, amount), credit(merchantWallet, merchant),
                credit(courierWallet, courier), credit(FEE, amount - merchant - courier)));
    }

    PostTransactionCommand command(String type, UUID shipment, EntryCommand... entries) {
        return new PostTransactionCommand(UUID.randomUUID().toString(), type, shipment, null, List.of(entries));
    }

    static EntryCommand debit(String account, long amount) {
        return new EntryCommand(account, Direction.DEBIT, amount, "TRY");
    }

    static EntryCommand credit(String account, long amount) {
        return new EntryCommand(account, Direction.CREDIT, amount, "TRY");
    }

    /** Servis kurallarını ve veritabanı tetikleyicilerini atlayarak doğrudan SQL ile kayıt yazar. */
    UUID rawTransaction(String type, UUID shipment, UUID reversalOf, String[]... entries) {
        UUID id = UUID.randomUUID();
        jdbc.execute("ALTER TABLE ledger_entries DISABLE TRIGGER ledger_entries_balanced");
        try {
            tx.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO journal_transactions (id, idempotency_key, request_hash, transaction_type, "
                        + "shipment_id, reversal_of) VALUES (?, ?, 'raw', ?, ?, ?)", id, UUID.randomUUID().toString(),
                        type, shipment, reversalOf);
                for (String[] entry : entries) {
                    jdbc.update("INSERT INTO accounts (code, type, currency) SELECT ?, split_part(?, ':', 1), 'TRY' "
                            + "WHERE NOT EXISTS (SELECT 1 FROM accounts WHERE code = ?)", entry[0], entry[0], entry[0]);
                    jdbc.update("INSERT INTO ledger_entries (transaction_id, account_id, direction, amount_minor) "
                            + "SELECT ?, id, ?, ? FROM accounts WHERE code = ?", id, entry[1],
                            Long.parseLong(entry[2]), entry[0]);
                }
            });
        } finally {
            jdbc.execute("ALTER TABLE ledger_entries ENABLE TRIGGER ledger_entries_balanced");
        }
        return id;
    }

    static String[] raw(String account, String direction, long amount) {
        return new String[] {account, direction, String.valueOf(amount)};
    }
}
