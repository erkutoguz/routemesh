package com.routemesh.locationservice.store;

/** {@code hit}: paket izlenen hedefe yaklaşmayı tetiklediyse dolu, aksi halde null. */
public record IngestResult(boolean accepted, ProximityHit hit) {

    public static final IngestResult STALE = new IngestResult(false, null);
    public static final IngestResult ACCEPTED = new IngestResult(true, null);
}
