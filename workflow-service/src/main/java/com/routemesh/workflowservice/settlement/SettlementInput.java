package com.routemesh.workflowservice.settlement;

import java.util.UUID;

public record SettlementInput(UUID shipmentId, SettlementKind kind) {
}
