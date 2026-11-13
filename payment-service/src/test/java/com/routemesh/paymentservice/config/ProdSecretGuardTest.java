package com.routemesh.paymentservice.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProdSecretGuardTest {

    private static PaymentProperties properties(List<String> secrets) {
        return new PaymentProperties("http://psp", "http://hook", secrets, Duration.ofMinutes(5),
                Duration.ofMinutes(15), Duration.ofMinutes(1), Duration.ofSeconds(30), 10);
    }

    private static MockEnvironment profile(String name) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(name);
        return environment;
    }

    @Test
    void theDevelopmentSecretIsRefusedInProd() {
        assertThatThrownBy(() -> new ProdSecretGuard(properties(List.of("whsec_dev_routemesh")), profile("prod")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("webhook-secrets");
        assertThatThrownBy(() -> new ProdSecretGuard(properties(List.of("rotated", "whsec_dev_routemesh")),
                profile("production"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingSecretsAreRefusedInProd() {
        assertThatThrownBy(() -> new ProdSecretGuard(properties(List.of()), profile("prod")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ProdSecretGuard(properties(null), profile("prod")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRealSecretStartsInProd() {
        assertThatCode(() -> new ProdSecretGuard(properties(List.of("a-real-rotated-secret")), profile("prod")))
                .doesNotThrowAnyException();
    }

    @Test
    void theDevelopmentSecretIsFineOutsideProd() {
        assertThatCode(() -> new ProdSecretGuard(properties(List.of("whsec_dev_routemesh")), new MockEnvironment()))
                .doesNotThrowAnyException();
        assertThatCode(() -> new ProdSecretGuard(properties(List.of("whsec_dev_routemesh")), profile("dev")))
                .doesNotThrowAnyException();
    }
}
