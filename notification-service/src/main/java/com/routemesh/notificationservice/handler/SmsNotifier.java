package com.routemesh.notificationservice.handler;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.routemesh.notificationservice.sms.SmsChannel;

@Component
public class SmsNotifier {

    private final SmsChannel channel;
    private final NotificationRepository notifications;

    public SmsNotifier(SmsChannel channel, NotificationRepository notifications) {
        this.channel = channel;
        this.notifications = notifications;
    }

    public void toRecipient(UUID eventId, String template, UUID shipmentId, String phone, String text) {
        channel.send(phone, text);
        notifications.insert(new Notification(eventId, template, RecipientType.RECIPIENT, shipmentId, Channel.SMS,
                shipmentId, Map.of()));
    }
}
