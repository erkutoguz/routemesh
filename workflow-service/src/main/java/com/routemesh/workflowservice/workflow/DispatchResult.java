package com.routemesh.workflowservice.workflow;

import java.util.UUID;

public record DispatchResult(String outcome, UUID courierId, String reason) {

    public static final String ASSIGNED = "ASSIGNED";
    public static final String ON_HOLD = "ON_HOLD";
    public static final String SKIPPED = "SKIPPED";
    public static final String ABORTED = "ABORTED";
}
