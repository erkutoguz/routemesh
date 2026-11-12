package com.routemesh.notificationservice.handler;

public final class MessageTemplates {

    private MessageTemplates() {
    }

    public static String deliveryOtp(String code) {
        return "RouteMesh teslimat kodunuz: " + code + ". Kodu yalnızca teslimat anında kuryeye söyleyin.";
    }

    public static String delivered() {
        return "RouteMesh: gönderiniz teslim edildi.";
    }

    public static String courierApproaching() {
        return "RouteMesh: kuryeniz yaklaşıyor, teslimat için hazır olun.";
    }
}
