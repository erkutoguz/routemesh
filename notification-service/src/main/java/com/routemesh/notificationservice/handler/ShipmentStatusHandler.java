package com.routemesh.notificationservice.handler;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.routemesh.contracts.shipment.IssueDeliveryOtpRequest;
import com.routemesh.contracts.shipment.IssueDeliveryOtpResponse;
import com.routemesh.contracts.shipment.Shipment;
import com.routemesh.contracts.shipment.ShipmentServiceGrpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import tools.jackson.databind.JsonNode;

@Component
public class ShipmentStatusHandler implements EventHandler {

    static final String OUT_FOR_DELIVERY = "OUT_FOR_DELIVERY";
    static final String DELIVERED = "DELIVERED";
    static final Set<String> MERCHANT_ALERTS = Set.of("DELIVERY_FAILED", "CANCELLED", "LOST", "ON_HOLD");

    private static final Logger log = LoggerFactory.getLogger(ShipmentStatusHandler.class);

    private final ShipmentDirectory directory;
    private final ShipmentServiceGrpc.ShipmentServiceBlockingStub shipments;
    private final SmsNotifier sms;
    private final NotificationRepository notifications;

    public ShipmentStatusHandler(ShipmentDirectory directory,
                                 ShipmentServiceGrpc.ShipmentServiceBlockingStub shipments, SmsNotifier sms,
                                 NotificationRepository notifications) {
        this.directory = directory;
        this.shipments = shipments;
        this.sms = sms;
        this.notifications = notifications;
    }

    @Override
    public String eventType() {
        return "ShipmentStatusChanged";
    }

    @Override
    public boolean relevant(JsonNode data) {
        String to = data.path("toStatus").asString();
        return OUT_FOR_DELIVERY.equals(to) || DELIVERED.equals(to) || MERCHANT_ALERTS.contains(to);
    }

    @Override
    public void handle(UUID eventId, JsonNode data) {
        String to = data.path("toStatus").asString();
        UUID shipmentId = UUID.fromString(data.path("shipmentId").asString());
        Shipment shipment = directory.get(shipmentId);
        if (OUT_FOR_DELIVERY.equals(to)) {
            sendOtp(eventId, shipmentId, shipment);
            return;
        }
        notifications.insert(new Notification(eventId, "SHIPMENT_" + to, RecipientType.MERCHANT,
                UUID.fromString(shipment.getMerchantId()), Channel.IN_APP, shipmentId, Map.of("status", to)));
        if (DELIVERED.equals(to)) {
            sms.toRecipient(eventId, "SHIPMENT_DELIVERED_RECIPIENT", shipmentId, shipment.getRecipientPhone(),
                    MessageTemplates.delivered());
        }
    }

    private void sendOtp(UUID eventId, UUID shipmentId, Shipment shipment) {
        IssueDeliveryOtpResponse otp;
        try {
            otp = shipments.issueDeliveryOtp(IssueDeliveryOtpRequest.newBuilder()
                    .setShipmentId(shipmentId.toString()).build());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) {
                log.info("Shipment {} already ended, delivery OTP skipped", shipmentId);
                return;
            }
            throw ShipmentDirectory.classify(e, "delivery OTP for shipment " + shipmentId);
        }
        sms.toRecipient(eventId, "DELIVERY_OTP", shipmentId, shipment.getRecipientPhone(),
                MessageTemplates.deliveryOtp(otp.getCode()));
    }
}
