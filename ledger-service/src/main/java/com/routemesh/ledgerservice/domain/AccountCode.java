package com.routemesh.ledgerservice.domain;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record AccountCode(String value, AccountType type, String name, boolean wallet) {

    public static final String ESCROW = "LIABILITY:ESCROW";

    private static final Pattern PATTERN = Pattern.compile(
            "^(ASSET|LIABILITY|REVENUE|EXPENSE):([A-Z][A-Z0-9_]{1,63})"
                    + "(:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})?$");

    private static final Set<String> WALLET_NAMES = Set.of("MERCHANT_WALLET", "COURIER_WALLET");

    public static Optional<AccountCode> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher matcher = PATTERN.matcher(value);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        AccountType type = AccountType.valueOf(matcher.group(1));
        String name = matcher.group(2);
        boolean wallet = type == AccountType.LIABILITY && WALLET_NAMES.contains(name) && matcher.group(3) != null;
        return Optional.of(new AccountCode(value, type, name, wallet));
    }

    public boolean isEscrow() {
        return ESCROW.equals(value);
    }
}
