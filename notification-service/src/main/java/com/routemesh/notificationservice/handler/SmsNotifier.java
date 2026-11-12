package com.routemesh.notificationservice.handler;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.routemesh.notificationservice.sms.SmsChannel;

import io.micrometer.core.instrument.MeterRegistry;

@Component
public class SmsNotifier {

    private final SmsChannel channel;
    private final NotificationRepository notifications;
    private final MeterRegistry meters;

    public SmsNotifier(SmsChannel channel, NotificationRepository notifications, MeterRegistry meters) {
        this.channel = channel;
        this.notifications = notifications;
        this.meters = meters;
    }

    public void toRecipient(UUID eventId, String template, UUID shipmentId, String phone, String text) {
        try {
            channel.send(phone, text);
        } catch (RuntimeException e) {
            // template şablon adlarının sabit kümesidir; hata yeniden denenmesi için yukarı fırlatılır
            meters.counter("routemesh.notification.sms.failures", "template", template).increment();
            throw e;
        }
        notifications.insert(new Notification(eventId, template, RecipientType.RECIPIENT, shipmentId, Channel.SMS,
                shipmentId, Map.of()));
    }
}
