package com.routemesh.shipmentservice.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProdSecretGuardTest {

    private static final String DEV_OTP = "dev-only-otp-secret-change-me-0123456789";
    private static final String DEV_STORAGE = "shipment_pod_dev_secret";

    private static PodProperties properties(String otpSecret, String storageSecret) {
        return new PodProperties(5, new PodProperties.Otp(Duration.ofHours(24), otpSecret),
                new PodProperties.Storage("http://minio", "bucket", "us-east-1", "access", storageSecret));
    }

    private static MockEnvironment profile(String name) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(name);
        return environment;
    }

    @Test
    void theDevelopmentOtpSecretIsRefusedInProd() {
        assertThatThrownBy(() -> new ProdSecretGuard(properties(DEV_OTP, "real-storage-secret"), profile("prod")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("otp.secret");
    }

    @Test
    void theDevelopmentStorageSecretIsRefusedInProduction() {
        assertThatThrownBy(() -> new ProdSecretGuard(properties("a-real-otp-secret-0123456789", DEV_STORAGE),
                profile("production"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("storage.secret-key");
    }

    @Test
    void realSecretsStartInProd() {
        assertThatCode(() -> new ProdSecretGuard(properties("a-real-otp-secret-0123456789", "real-storage-secret"),
                profile("prod"))).doesNotThrowAnyException();
    }

    @Test
    void developmentDefaultsAreFineOutsideProd() {
        assertThatCode(() -> new ProdSecretGuard(properties(DEV_OTP, DEV_STORAGE), new MockEnvironment()))
                .doesNotThrowAnyException();
    }
}
