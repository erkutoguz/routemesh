package com.routemesh.notificationservice.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import com.routemesh.notificationservice.config.NotificationProperties;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
        return channel(logBody, null, new MockEnvironment());
    }

    private static SimulatedSmsChannel channel(boolean logBody, String sinkFile, MockEnvironment environment) {
        return new SimulatedSmsChannel(new NotificationProperties(true, ".dlt", 5, Duration.ofSeconds(1),
                Duration.ofSeconds(16), Duration.ofDays(30), "0 30 3 * * *",
                new NotificationProperties.Sms(logBody, sinkFile)), environment, JsonMapper.builder().build());
    }

    @Test
    void withoutASinkFileNothingIsWrittenToDisk(@TempDir Path dir) throws Exception {
        channel(false, "", new MockEnvironment()).send("+905551112233", "kod 123456");
        channel(false, "   ", new MockEnvironment()).send("+905551112233", "kod 123456");

        try (var files = Files.list(dir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void theSinkFileGetsOneJsonLinePerMessageWithTheFullNumberAndText(@TempDir Path dir) throws Exception {
        Path sink = dir.resolve("nested").resolve("sms.jsonl");
        SimulatedSmsChannel channel = channel(false, sink.toString(), new MockEnvironment());

        channel.send("+905551112233", "kod 123456");
        channel.send("+905559998877", "ikinci \"mesaj\"");

        List<String> lines = Files.readAllLines(sink);
        assertThat(lines).hasSize(2);
        JsonNode first = JsonMapper.builder().build().readTree(lines.get(0));
        assertThat(first.path("phone").asString()).isEqualTo("+905551112233");
        assertThat(first.path("text").asString()).isEqualTo("kod 123456");
        assertThat(first.path("at").asString()).isNotBlank();
        assertThat(JsonMapper.builder().build().readTree(lines.get(1)).path("text").asString()).isEqualTo("ikinci \"mesaj\"");
        // günlük yine maskeli: dosya ek bir çıktıdır, günlüğe sızıntı yoktur
        assertThat(messages()).doesNotContain("kod 123456").doesNotContain("+905551112233");
    }

    @Test
    void theSinkFileIsCreatedReadableOnlyByItsOwnerWherePosixAllowsIt(@TempDir Path dir) throws Exception {
        Path sink = dir.resolve("sms.jsonl");

        channel(false, sink.toString(), new MockEnvironment());

        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(sink))).isEqualTo("rw-------");
        }
    }

    @Test
    void theApplicationRefusesToStartWithASinkFileInTheProdProfile(@TempDir Path dir) {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        assertThatThrownBy(() -> channel(false, dir.resolve("sms.jsonl").toString(), prod))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sink-file").hasMessageContaining("prod");
        assertThat(dir.resolve("sms.jsonl")).doesNotExist();
        // ayar yoksa prod profili normal çalışır
        channel(false, null, prod).send("+905551112233", "kod");
    }
}
