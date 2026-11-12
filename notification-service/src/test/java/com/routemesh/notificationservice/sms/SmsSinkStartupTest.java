package com.routemesh.notificationservice.sms;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.routemesh.notificationservice.config.NotificationProperties;

import tools.jackson.databind.json.JsonMapper;

/** Gerçek Spring bağlamında: ayar prod profilinde uygulamanın açılmasını engeller (fail-fast). */
class SmsSinkStartupTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationProperties.class)
    static class Config {
        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        SimulatedSmsChannel simulatedSmsChannel(NotificationProperties properties,
                org.springframework.core.env.Environment environment, JsonMapper mapper) {
            return new SimulatedSmsChannel(properties, environment, mapper);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void theContextFailsToStartWhenTheSinkFileIsSetInTheProdProfile(@TempDir Path dir) {
        runner.withPropertyValues("spring.profiles.active=prod",
                        "routemesh.notification.sms.sink-file=" + dir.resolve("sms.jsonl"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("sink-file")
                            .hasMessageContaining("prod");
                });
        assertThat(dir.resolve("sms.jsonl")).doesNotExist();
    }

    @Test
    void theContextStartsWithTheSinkFileOutsideProdAndWithoutItInProd(@TempDir Path dir) {
        Path sink = dir.resolve("sms.jsonl");
        runner.withPropertyValues("spring.profiles.active=dev", "routemesh.notification.sms.sink-file=" + sink)
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(Files.exists(sink)).isTrue();

        runner.withPropertyValues("spring.profiles.active=prod", "routemesh.notification.sms.log-body=false")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
