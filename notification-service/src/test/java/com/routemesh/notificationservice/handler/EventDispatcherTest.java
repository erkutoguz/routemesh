package com.routemesh.notificationservice.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.routemesh.contracts.shipment.GetShipmentRequest;
import com.routemesh.contracts.shipment.IssueDeliveryOtpRequest;
import com.routemesh.contracts.shipment.IssueDeliveryOtpResponse;
import com.routemesh.contracts.shipment.Shipment;
import com.routemesh.contracts.shipment.ShipmentServiceGrpc;
import com.routemesh.notificationservice.Events;
import com.routemesh.notificationservice.TestcontainersConfiguration;
import com.routemesh.notificationservice.sms.SmsChannel;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "routemesh.notification.consumer-enabled=false")
class EventDispatcherTest {

    private static final String PHONE = "+905551112233";
    private static final String OTP = "482916";

    @Autowired
    private EventDispatcher dispatcher;

    @Autowired
    private JdbcClient jdbc;

    @MockitoBean
    private ShipmentServiceGrpc.ShipmentServiceBlockingStub shipments;

    @MockitoSpyBean
    private SmsChannel sms;

    private final UUID shipmentId = UUID.randomUUID();
    private final UUID merchantId = UUID.randomUUID();
    private final UUID courierId = UUID.randomUUID();
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM notifications").update();
        jdbc.sql("DELETE FROM processed_events").update();
        reset(sms);
        when(shipments.getShipment(any(GetShipmentRequest.class))).thenReturn(Shipment.newBuilder()
                .setId(shipmentId.toString()).setMerchantId(merchantId.toString()).setRecipientPhone(PHONE)
                .setRecipientName("Ada").setDeliveryAddress("Test Mah. 1").build());
        when(shipments.issueDeliveryOtp(any(IssueDeliveryOtpRequest.class)))
                .thenReturn(IssueDeliveryOtpResponse.newBuilder().setCode(OTP).build());
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
    }

    @Test
    void outForDeliveryIssuesAnOtpAndSendsItToTheRecipientOnly() {
        dispatcher.dispatch("shipment.events", Events.statusChanged(UUID.randomUUID(), shipmentId, "ASSIGNED",
                "OUT_FOR_DELIVERY"));

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sms).send(org.mockito.ArgumentMatchers.eq(PHONE), text.capture());
        assertThat(text.getValue()).contains(OTP);
        assertThat(rows("SELECT template || ':' || recipient_type || ':' || channel FROM notifications"))
                .containsExactly("DELIVERY_OTP:RECIPIENT:SMS");
    }

    @Test
    void theOtpAndThePhoneNumberNeverReachTheDatabaseOrTheLogs() {
        dispatcher.dispatch("shipment.events", Events.statusChanged(UUID.randomUUID(), shipmentId, "ASSIGNED",
                "OUT_FOR_DELIVERY"));
        dispatcher.dispatch("location.events", Events.approaching(UUID.randomUUID(), shipmentId, courierId,
                "DELIVERY"));

        String database = String.join("\n", rows("SELECT t::text FROM notifications t"))
                + String.join("\n", rows("SELECT p::text FROM processed_events p"));
        assertThat(database).doesNotContain(OTP).doesNotContain(PHONE).doesNotContain("5551112233");
        String logged = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(logged).doesNotContain(OTP).doesNotContain(PHONE).doesNotContain("5551112233");
        assertThat(logged).contains("+905***33");
    }

    @Test
    void aRedeliveredEventDoesNotIssueOrSendASecondOtp() {
        String event = Events.statusChanged(UUID.randomUUID(), shipmentId, "ASSIGNED", "OUT_FOR_DELIVERY");

        dispatcher.dispatch("shipment.events", event);
        dispatcher.dispatch("shipment.events", event);

        verify(shipments, times(1)).issueDeliveryOtp(any());
        verify(sms, times(1)).send(anyString(), anyString());
        assertThat(count("notifications")).isEqualTo(1);
        assertThat(count("processed_events")).isEqualTo(1);
    }

    @Test
    void aFailedSendRollsBackTheInboxSoTheRetryProcessesTheEventAgain() {
        String event = Events.statusChanged(UUID.randomUUID(), shipmentId, "ASSIGNED", "OUT_FOR_DELIVERY");
        doThrow(new IllegalStateException("sms gateway down")).doNothing().when(sms).send(anyString(), anyString());

        assertThatThrownBy(() -> dispatcher.dispatch("shipment.events", event))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("processed_events")).isZero();
        assertThat(count("notifications")).isZero();

        dispatcher.dispatch("shipment.events", event);

        assertThat(count("processed_events")).isEqualTo(1);
        assertThat(count("notifications")).isEqualTo(1);
        verify(shipments, times(2)).issueDeliveryOtp(any());
    }

    @Test
    void otpForAnAlreadyEndedShipmentIsSkippedQuietly() {
        when(shipments.issueDeliveryOtp(any(IssueDeliveryOtpRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.FAILED_PRECONDITION));

        dispatcher.dispatch("shipment.events", Events.statusChanged(UUID.randomUUID(), shipmentId, "ASSIGNED",
                "OUT_FOR_DELIVERY"));

        verify(sms, never()).send(anyString(), anyString());
        assertThat(count("notifications")).isZero();
    }

    @Test
    void deliveredNotifiesTheMerchantInAppAndTheRecipientBySms() {
        dispatcher.dispatch("shipment.events", Events.statusChanged(UUID.randomUUID(), shipmentId,
                "OUT_FOR_DELIVERY", "DELIVERED"));

        assertThat(rows("SELECT template || ':' || recipient_type || ':' || recipient_id || ':' || channel "
                + "FROM notifications ORDER BY channel")).containsExactly(
                "SHIPMENT_DELIVERED:MERCHANT:" + merchantId + ":IN_APP",
                "SHIPMENT_DELIVERED_RECIPIENT:RECIPIENT:" + shipmentId + ":SMS");
        verify(sms).send(org.mockito.ArgumentMatchers.eq(PHONE), anyString());
    }

    @Test
    void problemStatusesNotifyOnlyTheMerchant() {
        for (String status : List.of("DELIVERY_FAILED", "CANCELLED", "LOST", "ON_HOLD")) {
            dispatcher.dispatch("shipment.events", Events.statusChanged(UUID.randomUUID(), shipmentId, "PAID",
                    status));
        }

        assertThat(rows("SELECT template FROM notifications ORDER BY template")).containsExactly(
                "SHIPMENT_CANCELLED", "SHIPMENT_DELIVERY_FAILED", "SHIPMENT_LOST", "SHIPMENT_ON_HOLD");
        assertThat(rows("SELECT DISTINCT recipient_id::text FROM notifications")).containsExactly(merchantId.toString());
        verify(sms, never()).send(anyString(), anyString());
    }

    @Test
    void aNewOfferNotifiesTheCourierButOtherOfferTransitionsDoNot() {
        UUID offerId = UUID.randomUUID();

        dispatcher.dispatch("fleet.events", Events.offerChanged(UUID.randomUUID(), offerId, shipmentId, courierId,
                null, "OFFERED"));
        dispatcher.dispatch("fleet.events", Events.offerChanged(UUID.randomUUID(), offerId, shipmentId, courierId,
                "OFFERED", "ACCEPTED"));

        assertThat(rows("SELECT template || ':' || recipient_type || ':' || recipient_id || ':' || (params ->> 'offerId') "
                + "FROM notifications")).containsExactly("NEW_OFFER:COURIER:" + courierId + ":" + offerId);
        assertThat(count("processed_events")).isEqualTo(1);
    }

    @Test
    void approachingTheDeliveryPointNotifiesMerchantAndRecipientButPickupDoesNot() {
        dispatcher.dispatch("location.events", Events.approaching(UUID.randomUUID(), shipmentId, courierId, "PICKUP"));
        assertThat(count("notifications")).isZero();

        dispatcher.dispatch("location.events", Events.approaching(UUID.randomUUID(), shipmentId, courierId,
                "DELIVERY"));

        assertThat(rows("SELECT template || ':' || channel || ':' || coalesce(params ->> 'distanceMeters', '') "
                + "FROM notifications ORDER BY channel")).containsExactly(
                "COURIER_APPROACHING:IN_APP:87", "COURIER_APPROACHING_RECIPIENT:SMS:");
    }

    @Test
    void irrelevantEventsLeaveNoTraceInTheInbox() {
        dispatcher.dispatch("shipment.events", Events.statusChanged(UUID.randomUUID(), shipmentId, "PAID",
                "AT_WAREHOUSE"));
        dispatcher.dispatch("shipment.events", Events.envelope(UUID.randomUUID(), "ShipmentCreated",
                "{\"shipmentId\":\"" + shipmentId + "\"}"));
        dispatcher.dispatch("fleet.events", Events.envelope(UUID.randomUUID(), "CourierStatusChanged",
                "{\"courierId\":\"" + courierId + "\",\"toStatus\":\"IDLE\"}"));

        assertThat(count("processed_events")).isZero();
        verify(shipments, never()).getShipment(any());
    }

    @Test
    void aMissingShipmentIsAPermanentFailure() {
        when(shipments.getShipment(any(GetShipmentRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.NOT_FOUND));

        assertThatThrownBy(() -> dispatcher.dispatch("shipment.events",
                Events.statusChanged(UUID.randomUUID(), shipmentId, "OUT_FOR_DELIVERY", "DELIVERED")))
                .isInstanceOf(NonRetryableEventException.class);
        assertThat(count("processed_events")).isZero();
    }

    @Test
    void anUnavailableShipmentServiceStaysRetryable() {
        when(shipments.getShipment(any(GetShipmentRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        assertThatThrownBy(() -> dispatcher.dispatch("shipment.events",
                Events.statusChanged(UUID.randomUUID(), shipmentId, "OUT_FOR_DELIVERY", "DELIVERED")))
                .isInstanceOf(StatusRuntimeException.class);
    }

    @Test
    void malformedMessagesAndIdsAreRejected() {
        assertThatThrownBy(() -> dispatcher.dispatch("shipment.events", "not json"))
                .isInstanceOf(tools.jackson.core.JacksonException.class);
        assertThatThrownBy(() -> dispatcher.dispatch("shipment.events",
                Events.statusChanged(UUID.randomUUID(), shipmentId, "A", "DELIVERED").replace(shipmentId.toString(),
                        "nope")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<String> rows(String sql) {
        return jdbc.sql(sql).query(String.class).list();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
