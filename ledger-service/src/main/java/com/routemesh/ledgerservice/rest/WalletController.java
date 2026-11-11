package com.routemesh.ledgerservice.rest;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.routemesh.ledgerservice.repository.LedgerRepository.WalletEntry;
import com.routemesh.ledgerservice.service.WalletService;
import com.routemesh.ledgerservice.service.WalletService.HistoryPage;

/**
 * Çağıranın kendi cüzdanı: {@code MERCHANT} için {@code LIABILITY:MERCHANT_WALLET:<sub>}, {@code COURIER} için
 * {@code LIABILITY:COURIER_WALLET:<sub>}. Hesap kodu yalnızca token'dan türetilir, istemci hesap seçemez.
 */
@RestController
@RequestMapping("/api/v1/wallets/me")
@PreAuthorize("hasAnyRole('MERCHANT', 'COURIER')")
class WalletController {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;
    private static final String CURRENCY = "TRY";

    record Money(long amountMinor, String currency) {
    }

    record WalletResponse(String accountCode, Money balance) {
    }

    record TransactionItem(long entryId, UUID transactionId, String type, String direction, long amountMinor,
                           UUID shipmentId, Instant createdAt) {

        static TransactionItem from(WalletEntry entry) {
            return new TransactionItem(entry.entryId(), entry.transactionId(), entry.transactionType(),
                    entry.direction().name(), entry.amountMinor(), entry.shipmentId(), entry.createdAt());
        }
    }

    record TransactionsResponse(List<TransactionItem> items, Long nextCursor) {
    }

    private final WalletService wallets;

    WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    @GetMapping
    WalletResponse wallet(Authentication authentication) {
        String accountCode = accountCode(authentication);
        return new WalletResponse(accountCode, new Money(wallets.balance(accountCode), CURRENCY));
    }

    @GetMapping("/transactions")
    TransactionsResponse transactions(Authentication authentication,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(required = false) Long cursor) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_LIMIT);
        }
        if (cursor != null && cursor < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cursor must be a positive entry id");
        }
        HistoryPage page = wallets.history(accountCode(authentication), cursor, limit);
        return new TransactionsResponse(page.items().stream().map(TransactionItem::from).toList(), page.nextCursor());
    }

    private static String accountCode(Authentication authentication) {
        Set<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.equals("ROLE_MERCHANT") || authority.equals("ROLE_COURIER"))
                .collect(Collectors.toSet());
        if (roles.size() != 1) {
            throw new AccessDeniedException("token must carry exactly one of MERCHANT or COURIER");
        }
        UUID owner;
        try {
            owner = UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new AccessDeniedException("token subject is not a valid id");
        }
        String prefix = roles.contains("ROLE_MERCHANT") ? "LIABILITY:MERCHANT_WALLET:" : "LIABILITY:COURIER_WALLET:";
        return prefix + owner;
    }
}
