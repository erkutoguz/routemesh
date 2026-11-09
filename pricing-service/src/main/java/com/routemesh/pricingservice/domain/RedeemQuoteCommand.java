package com.routemesh.pricingservice.domain;

import java.util.UUID;

public record RedeemQuoteCommand(UUID quoteId, String redemptionKey, UUID shipmentId) {
}
