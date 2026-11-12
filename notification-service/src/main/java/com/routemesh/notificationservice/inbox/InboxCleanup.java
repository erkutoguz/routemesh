package com.routemesh.notificationservice.inbox;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.routemesh.notificationservice.config.NotificationProperties;

@Component
public class InboxCleanup {

    private static final Logger log = LoggerFactory.getLogger(InboxCleanup.class);

    private final InboxRepository inbox;
    private final NotificationProperties properties;

    public InboxCleanup(InboxRepository inbox, NotificationProperties properties) {
        this.inbox = inbox;
        this.properties = properties;
    }

    @Scheduled(cron = "${routemesh.notification.inbox-cleanup-cron}")
    public void purge() {
        int deleted = inbox.deleteOlderThan(Instant.now().minus(properties.inboxRetention()));
        if (deleted > 0) {
            log.info("Purged {} processed events older than {}", deleted, properties.inboxRetention());
        }
    }
}
