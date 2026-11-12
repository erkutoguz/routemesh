package com.routemesh.notificationservice.sms;

public interface SmsChannel {

    void send(String phoneNumber, String text);
}
