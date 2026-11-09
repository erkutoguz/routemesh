package com.routemesh.ledgerservice.domain;

public enum AccountType {
    ASSET(false),
    LIABILITY(true),
    REVENUE(true),
    EXPENSE(false);

    private final boolean creditNormal;

    AccountType(boolean creditNormal) {
        this.creditNormal = creditNormal;
    }

    public boolean isCreditNormal() {
        return creditNormal;
    }
}
