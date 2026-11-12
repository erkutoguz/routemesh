package com.routemesh.notificationservice.sms;

public final class PhoneMasker {

    private PhoneMasker() {
    }

    public static String mask(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 4) + "***" + phone.substring(phone.length() - 2);
    }
}
