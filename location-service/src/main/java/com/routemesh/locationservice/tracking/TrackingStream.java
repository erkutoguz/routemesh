package com.routemesh.locationservice.tracking;

import java.time.Instant;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.routemesh.locationservice.store.WatchView;

/** Açık bir canlı takip akışının durumu. Alanları Redis dinleyicisi ve zamanlayıcı iş parçacığı paylaşır. */
final class TrackingStream {

    record Position(String epoch, String sequence, double latitude, double longitude, Instant receivedAt) {
    }

    record Approach(String kind, double distanceMeters) {
    }

    final String shipmentId;
    final String courierId;
    final String subject;
    final SseEmitter emitter = new SseEmitter(0L); // süreyi biz yönetiriz (token süresi, en uzun ömür)
    final long deadlineMillis;
    final String deadlineReason;

    volatile WatchView target;
    volatile boolean closed;

    final AtomicReference<Position> pending = new AtomicReference<>();
    final Queue<Approach> approaches = new ConcurrentLinkedQueue<>();
    final AtomicReference<String> endReason = new AtomicReference<>();
    /** Aynı akış için zamanlayıcı işleri üst üste binmesin (yavaş istemci diğerlerini bekletmez). */
    final AtomicBoolean busy = new AtomicBoolean();

    // Yalnızca busy bayrağını tutan iş parçacığı dokunur
    long lastWriteMillis;
    long lastLocationMillis;
    long lastHealthMillis;
    final Set<String> sentApproaches = new HashSet<>();

    TrackingStream(WatchView target, String subject, long deadlineMillis, String deadlineReason, long nowMillis) {
        this.shipmentId = target.shipmentId();
        this.courierId = target.courierId();
        this.subject = subject;
        this.target = target;
        this.deadlineMillis = deadlineMillis;
        this.deadlineReason = deadlineReason;
        this.lastWriteMillis = nowMillis;
        this.lastHealthMillis = nowMillis;
    }
}
