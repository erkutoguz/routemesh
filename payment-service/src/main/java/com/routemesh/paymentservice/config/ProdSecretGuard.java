package com.routemesh.paymentservice.config;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * {@code prod}/{@code production} profilinde PSP webhook sırrı geliştirme varsayılanında kalmışsa uygulama
 * başlamaz: sır repoda açık olduğundan, bırakılırsa herkes imzalı sahte "captured" webhook'u üretebilir.
 */
@Component
public class ProdSecretGuard {

    static final String DEV_WEBHOOK_SECRET = "whsec_dev_routemesh";
    private static final Profiles PRODUCTION = Profiles.of("prod", "production");

    public ProdSecretGuard(PaymentProperties properties, Environment environment) {
        if (!environment.acceptsProfiles(PRODUCTION)) {
            return;
        }
        if (properties.webhookSecrets() == null || properties.webhookSecrets().isEmpty()
                || properties.webhookSecrets().contains(DEV_WEBHOOK_SECRET)) {
            throw new IllegalStateException("routemesh.payment.webhook-secrets must be set to non-default values in "
                    + "the prod profile: the development webhook secret is public");
        }
    }
}
