package com.routemesh.notificationservice.rest;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.routemesh.notificationservice.handler.RecipientType;
import com.routemesh.notificationservice.rest.NotificationInboxRepository.Row;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Çağıranın kendi uygulama içi bildirim kutusu. Alıcı yalnızca token'dan türetilir ({@code sub} + rol), istemci
 * başka bir alıcıyı seçemez. Başkasına ait bildirim, olmayan bildirimle aynı şekilde {@code 404} döner.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@PreAuthorize("hasAnyRole('MERCHANT', 'COURIER')")
class NotificationController {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    record NotificationItem(UUID id, String template, UUID shipmentId, JsonNode params, Instant createdAt,
                            Instant readAt) {
    }

    record NotificationsResponse(List<NotificationItem> items, String nextCursor) {
    }

    record UnreadCountResponse(long unread) {
    }

    private record Caller(RecipientType type, UUID id) {
    }

    private final NotificationInboxRepository inbox;
    private final JsonMapper jsonMapper;

    NotificationController(NotificationInboxRepository inbox, JsonMapper jsonMapper) {
        this.inbox = inbox;
        this.jsonMapper = jsonMapper;
    }

    @GetMapping
    NotificationsResponse list(Authentication authentication,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "false") boolean unread) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_LIMIT);
        }
        InboxCursor after = null;
        if (cursor != null) {
            try {
                after = InboxCursor.decode(cursor);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cursor is not valid");
            }
        }
        Caller caller = caller(authentication);
        List<Row> rows = inbox.page(caller.type(), caller.id(), unread, after, limit + 1);
        boolean more = rows.size() > limit;
        List<Row> pageRows = more ? rows.subList(0, limit) : rows;
        String next = more ? new InboxCursor(pageRows.getLast().createdAt(), pageRows.getLast().id()).encode() : null;
        return new NotificationsResponse(pageRows.stream().map(this::item).toList(), next);
    }

    @GetMapping("/unread-count")
    UnreadCountResponse unreadCount(Authentication authentication) {
        Caller caller = caller(authentication);
        return new UnreadCountResponse(inbox.unreadCount(caller.type(), caller.id()));
    }

    @PostMapping("/{id}/read")
    NotificationItem markRead(Authentication authentication, @PathVariable UUID id) {
        Caller caller = caller(authentication);
        return inbox.markRead(caller.type(), caller.id(), id).map(this::item)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "notification not found"));
    }

    private NotificationItem item(Row row) {
        return new NotificationItem(row.id(), row.template(), row.shipmentId(), jsonMapper.readTree(row.params()),
                row.createdAt(), row.readAt());
    }

    private static Caller caller(Authentication authentication) {
        Set<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.equals("ROLE_MERCHANT") || authority.equals("ROLE_COURIER"))
                .collect(Collectors.toSet());
        if (roles.size() != 1) {
            throw new AccessDeniedException("token must carry exactly one of MERCHANT or COURIER");
        }
        UUID id;
        try {
            id = UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new AccessDeniedException("token subject is not a valid id");
        }
        return new Caller(roles.contains("ROLE_MERCHANT") ? RecipientType.MERCHANT : RecipientType.COURIER, id);
    }
}
