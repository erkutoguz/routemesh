package com.routemesh.workflowservice.workflow;

/** Teklifin durumu: OFFERED, ACCEPTED, REJECTED, EXPIRED, RELEASED ya da kurye uygun değilse REFUSED. */
public record OfferInfo(String status) {

    public static final String REFUSED = "REFUSED";
    public static final String OFFERED = "OFFERED";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String REJECTED = "REJECTED";
}
