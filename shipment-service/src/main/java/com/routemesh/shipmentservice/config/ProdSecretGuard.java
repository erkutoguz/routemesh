package com.routemesh.shipmentservice.config;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * {@code prod}/{@code production} profilinde OTP HMAC sırrı ya da nesne deposu (MinIO/S3) sırrı geliştirme
 * varsayılanında kalmışsa uygulama başlamaz: bu değerler repoda açıktır.
 */
@Component
public class ProdSecretGuard {

    static final String DEV_OTP_SECRET = "dev-only-otp-secret-change-me-0123456789";
    static final String DEV_STORAGE_SECRET = "shipment_pod_dev_secret";
    private static final Profiles PRODUCTION = Profiles.of("prod", "production");

    public ProdSecretGuard(PodProperties properties, Environment environment) {
        if (!environment.acceptsProfiles(PRODUCTION)) {
            return;
        }
        if (DEV_OTP_SECRET.equals(properties.otp().secret())) {
            throw new IllegalStateException("routemesh.pod.otp.secret must not be the development default in the "
                    + "prod profile (set POD_OTP_SECRET)");
        }
        if (DEV_STORAGE_SECRET.equals(properties.storage().secretKey())) {
            throw new IllegalStateException("routemesh.pod.storage.secret-key must not be the development default "
                    + "in the prod profile (set POD_STORAGE_SECRET)");
        }
    }
}
