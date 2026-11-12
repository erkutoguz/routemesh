package com.routemesh.searchservice.inbox;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.routemesh.searchservice.config.SearchProperties;

@Component
public class InboxCleanup {

    private static final Logger log = LoggerFactory.getLogger(InboxCleanup.class);

    private final InboxRepository inbox;
    private final SearchProperties properties;

    public InboxCleanup(InboxRepository inbox, SearchProperties properties) {
        this.inbox = inbox;
        this.properties = properties;
    }

    @Scheduled(cron = "${routemesh.search.inbox-cleanup-cron}")
    public void purge() {
        int deleted = inbox.deleteOlderThan(Instant.now().minus(properties.inboxRetention()));
        if (deleted > 0) {
            log.info("Purged {} processed events older than {}", deleted, properties.inboxRetention());
        }
    }
}
