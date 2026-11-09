package com.routemesh.ledgerservice.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.routemesh.ledgerservice.domain.AccountCode;
import com.routemesh.ledgerservice.domain.AccountType;
import com.routemesh.ledgerservice.domain.Direction;
import com.routemesh.ledgerservice.domain.EntryCommand;
import com.routemesh.ledgerservice.domain.PostTransactionCommand;
import com.routemesh.ledgerservice.domain.PostedTransaction;
import com.routemesh.ledgerservice.repository.LedgerRepository;
import com.routemesh.ledgerservice.repository.LedgerRepository.Account;
import com.routemesh.ledgerservice.repository.LedgerRepository.StoredEntry;
import com.routemesh.ledgerservice.repository.LedgerRepository.StoredTransaction;
import com.routemesh.ledgerservice.service.LedgerException.Reason;

@Service
public class LedgerService {

    private static final String CURRENCY = "TRY";
    private static final int MAX_ENTRIES = 20;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;
    private static final Pattern TRANSACTION_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");

    private record ParsedEntry(AccountCode code, Direction direction, long amountMinor) {

        String canonical() {
            return code.value() + ":" + direction + ":" + amountMinor;
        }
    }

    private final LedgerRepository repository;

    public LedgerService(LedgerRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public PostedTransaction post(PostTransactionCommand command) {
        List<ParsedEntry> entries = validate(command);
        String requestHash = hash(command, entries);
        Map<String, Long> netCreditByAccount = netCreditByAccount(entries);

        acquireLocks(command, entries, netCreditByAccount);

        StoredTransaction existing = repository.findTransactionByKey(command.idempotencyKey()).orElse(null);
        if (existing != null) {
            return replayOrConflict(existing, requestHash);
        }

        if (command.reversalOf() != null) {
            validateReversal(command, entries);
        }

        Map<String, Account> accounts = resolveAccounts(entries);
        guardAgainstOverdraft(command.shipmentId(), entries, netCreditByAccount, accounts);

        StoredTransaction stored;
        try {
            stored = repository.insertTransaction(command.idempotencyKey(), requestHash,
                    command.transactionType(), command.shipmentId(), command.reversalOf()).orElse(null);
        } catch (DuplicateKeyException e) {
            throw new LedgerException(Reason.INVALID_REVERSAL, "transaction has already been reversed");
        }
        if (stored == null) {
            StoredTransaction concurrent = repository.findTransactionByKey(command.idempotencyKey()).orElseThrow();
            return replayOrConflict(concurrent, requestHash);
        }

        for (ParsedEntry entry : entries) {
            repository.insertEntry(stored.id(), accounts.get(entry.code().value()).id(),
                    entry.direction(), entry.amountMinor());
        }
        return new PostedTransaction(stored.id(), false, stored.createdAt());
    }

    @Transactional(readOnly = true)
    public long balance(String accountCode, UUID shipmentId) {
        AccountCode code = AccountCode.parse(accountCode)
                .orElseThrow(() -> new LedgerException(Reason.INVALID_REQUEST, "account_code is not valid"));
        Account account = repository.findAccount(code.value())
                .orElseThrow(() -> new LedgerException(Reason.ACCOUNT_NOT_FOUND,
                        "account " + code.value() + " does not exist"));
        long netDebit = repository.netDebit(account.id(), shipmentId);
        return account.type().isCreditNormal() ? -netDebit : netDebit;
    }

    private List<ParsedEntry> validate(PostTransactionCommand command) {
        String key = command.idempotencyKey();
        if (key == null || key.isBlank() || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw invalid("idempotency_key must be 1 to " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        if (command.transactionType() == null || !TRANSACTION_TYPE.matcher(command.transactionType()).matches()) {
            throw invalid("transaction_type must match " + TRANSACTION_TYPE.pattern());
        }
        List<EntryCommand> raw = command.entries();
        if (raw == null || raw.size() < 2 || raw.size() > MAX_ENTRIES) {
            throw invalid("entries must contain between 2 and " + MAX_ENTRIES + " items");
        }

        List<ParsedEntry> entries = new ArrayList<>();
        long debit = 0;
        long credit = 0;
        try {
            for (EntryCommand entry : raw) {
                AccountCode code = AccountCode.parse(entry.accountCode())
                        .orElseThrow(() -> invalid("account_code is not valid: " + entry.accountCode()));
                if (entry.direction() == null) {
                    throw invalid("direction is required");
                }
                if (!CURRENCY.equals(entry.currency())) {
                    throw invalid("currency must be " + CURRENCY);
                }
                if (entry.amountMinor() <= 0) {
                    throw invalid("amount must be greater than 0");
                }
                if (code.isEscrow() && command.shipmentId() == null) {
                    throw invalid("shipment_id is required for entries on " + AccountCode.ESCROW);
                }
                if (entry.direction() == Direction.DEBIT) {
                    debit = Math.addExact(debit, entry.amountMinor());
                } else {
                    credit = Math.addExact(credit, entry.amountMinor());
                }
                entries.add(new ParsedEntry(code, entry.direction(), entry.amountMinor()));
            }
        } catch (ArithmeticException e) {
            throw invalid("amounts are too large");
        }
        if (debit != credit) {
            throw invalid("transaction is not balanced: debit " + debit + ", credit " + credit);
        }
        return entries;
    }

    private Map<String, Long> netCreditByAccount(List<ParsedEntry> entries) {
        Map<String, Long> net = new LinkedHashMap<>();
        for (ParsedEntry entry : entries) {
            long signed = entry.direction() == Direction.CREDIT ? entry.amountMinor() : -entry.amountMinor();
            net.merge(entry.code().value(), signed, Long::sum);
        }
        return net;
    }

    private void acquireLocks(PostTransactionCommand command, List<ParsedEntry> entries,
                              Map<String, Long> netCreditByAccount) {
        TreeSet<String> keys = new TreeSet<>();
        if (command.shipmentId() != null) {
            keys.add("shipment:" + command.shipmentId());
        }
        for (ParsedEntry entry : entries) {
            AccountCode code = entry.code();
            if (code.type() == AccountType.LIABILITY && !code.isEscrow()
                    && netCreditByAccount.get(code.value()) < 0) {
                keys.add("account:" + code.value());
            }
        }
        keys.forEach(repository::lock);
    }

    private PostedTransaction replayOrConflict(StoredTransaction existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new LedgerException(Reason.IDEMPOTENCY_CONFLICT,
                    "idempotency_key was already used with a different request");
        }
        return new PostedTransaction(existing.id(), true, existing.createdAt());
    }

    private void validateReversal(PostTransactionCommand command, List<ParsedEntry> entries) {
        StoredTransaction original = repository.findTransactionById(command.reversalOf())
                .orElseThrow(() -> new LedgerException(Reason.INVALID_REVERSAL,
                        "transaction to reverse does not exist"));
        if (repository.isReversed(original.id())) {
            throw new LedgerException(Reason.INVALID_REVERSAL, "transaction has already been reversed");
        }
        if (!java.util.Objects.equals(original.shipmentId(), command.shipmentId())) {
            throw new LedgerException(Reason.INVALID_REVERSAL, "shipment_id must match the reversed transaction");
        }

        List<String> expected = repository.findEntries(original.id()).stream()
                .map((StoredEntry e) -> e.accountCode() + ":" + e.direction().opposite() + ":" + e.amountMinor())
                .sorted()
                .toList();
        List<String> actual = entries.stream().map(ParsedEntry::canonical).sorted().toList();
        if (!expected.equals(actual)) {
            throw new LedgerException(Reason.INVALID_REVERSAL,
                    "entries must exactly mirror the reversed transaction");
        }
    }

    private Map<String, Account> resolveAccounts(List<ParsedEntry> entries) {
        Map<String, Account> accounts = new LinkedHashMap<>();
        for (ParsedEntry entry : entries) {
            AccountCode code = entry.code();
            if (accounts.containsKey(code.value())) {
                continue;
            }
            if (code.wallet()) {
                repository.createAccountIfAbsent(code.value(), code.type());
            }
            Account account = repository.findAccount(code.value())
                    .orElseThrow(() -> new LedgerException(Reason.ACCOUNT_NOT_FOUND,
                            "account " + code.value() + " does not exist"));
            accounts.put(code.value(), account);
        }
        return accounts;
    }

    private void guardAgainstOverdraft(UUID shipmentId, List<ParsedEntry> entries,
                                       Map<String, Long> netCreditByAccount, Map<String, Account> accounts) {
        for (ParsedEntry entry : entries) {
            AccountCode code = entry.code();
            long netCredit = netCreditByAccount.get(code.value());
            if (code.type() != AccountType.LIABILITY || netCredit >= 0) {
                continue;
            }
            UUID scope = code.isEscrow() ? shipmentId : null;
            long available = -repository.netDebit(accounts.get(code.value()).id(), scope);
            if (available + netCredit < 0) {
                throw new LedgerException(Reason.INSUFFICIENT_FUNDS,
                        "insufficient funds on " + code.value() + ": available " + available
                                + ", required " + (-netCredit));
            }
        }
    }

    private static String hash(PostTransactionCommand command, List<ParsedEntry> entries) {
        String sortedEntries = entries.stream()
                .map(ParsedEntry::canonical)
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(";"));
        String canonical = command.transactionType() + "|" + command.shipmentId() + "|"
                + command.reversalOf() + "|" + sortedEntries;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static LedgerException invalid(String message) {
        return new LedgerException(Reason.INVALID_REQUEST, message);
    }
}
