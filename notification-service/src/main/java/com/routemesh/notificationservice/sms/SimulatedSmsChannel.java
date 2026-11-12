package com.routemesh.notificationservice.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.routemesh.notificationservice.config.NotificationProperties;

@Component
public class SimulatedSmsChannel implements SmsChannel {

    private static final Logger log = LoggerFactory.getLogger(SimulatedSmsChannel.class);

    private final boolean logBody;

    public SimulatedSmsChannel(NotificationProperties properties) {
        this.logBody = properties.sms().logBody();
    }

    @Override
    public void send(String phoneNumber, String text) {
        if (logBody) {
            log.warn("SMS (simulated) to {} body='{}'", PhoneMasker.mask(phoneNumber), text);
        } else {
            log.info("SMS (simulated) to {} ({} chars)", PhoneMasker.mask(phoneNumber), text.length());
        }
    }
}
