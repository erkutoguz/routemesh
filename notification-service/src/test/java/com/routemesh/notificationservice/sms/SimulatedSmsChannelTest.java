package com.routemesh.notificationservice.sms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.routemesh.notificationservice.config.NotificationProperties;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class SimulatedSmsChannelTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(SimulatedSmsChannel.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    @Test
    void bodyAndFullNumberStayOutOfTheLogByDefault() {
        channel(false).send("+905551112233", "kod 123456");

        assertThat(messages()).contains("+905***33", "10 chars")
                .doesNotContain("123456").doesNotContain("+905551112233");
    }

    @Test
    void bodyIsLoggedOnlyWhenExplicitlyEnabledButTheNumberIsStillMasked() {
        channel(true).send("+905551112233", "kod 123456");

        assertThat(messages()).contains("kod 123456", "+905***33").doesNotContain("+905551112233");
    }

    @Test
    void shortOrMissingNumbersAreFullyMasked() {
        assertThat(PhoneMasker.mask(null)).isEqualTo("***");
        assertThat(PhoneMasker.mask("12345")).isEqualTo("***");
    }

    private String messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
    }

    private static SimulatedSmsChannel channel(boolean logBody) {
        return new SimulatedSmsChannel(new NotificationProperties(true, ".dlt", 5, Duration.ofSeconds(1),
                Duration.ofSeconds(16), Duration.ofDays(30), "0 30 3 * * *", new NotificationProperties.Sms(logBody)));
    }
}
