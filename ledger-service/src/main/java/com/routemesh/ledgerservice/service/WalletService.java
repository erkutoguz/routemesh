package com.routemesh.ledgerservice.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.routemesh.ledgerservice.repository.LedgerRepository;
import com.routemesh.ledgerservice.repository.LedgerRepository.Account;
import com.routemesh.ledgerservice.repository.LedgerRepository.WalletEntry;

/** Satıcı ve kurye cüzdanlarının okuma tarafı. Cüzdan hesabı ilk hakedişte oluşur; öncesinde bakiye 0'dır. */
@Service
public class WalletService {

    /** @param nextCursor sonraki sayfanın imleci (bu sayfanın son kayıt kimliği), son sayfada null */
    public record HistoryPage(List<WalletEntry> items, Long nextCursor) {
    }

    private final LedgerService ledgerService;
    private final LedgerRepository repository;

    public WalletService(LedgerService ledgerService, LedgerRepository repository) {
        this.ledgerService = ledgerService;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public long balance(String walletAccountCode) {
        if (repository.findAccount(walletAccountCode).isEmpty()) {
            return 0;
        }
        return ledgerService.balance(walletAccountCode, null);
    }

    /** @param beforeEntryId verilirse yalnızca bu kayıttan eskiler gelir */
    @Transactional(readOnly = true)
    public HistoryPage history(String walletAccountCode, Long beforeEntryId, int limit) {
        Optional<Account> account = repository.findAccount(walletAccountCode);
        if (account.isEmpty()) {
            return new HistoryPage(List.of(), null);
        }
        List<WalletEntry> fetched = repository.findEntriesByAccount(account.get().id(), beforeEntryId, limit + 1);
        if (fetched.size() <= limit) {
            return new HistoryPage(fetched, null);
        }
        List<WalletEntry> items = fetched.subList(0, limit);
        return new HistoryPage(items, items.get(limit - 1).entryId());
    }
}
