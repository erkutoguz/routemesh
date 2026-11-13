package com.routemesh.pricingservice.rest;

import com.routemesh.pricingservice.domain.ServiceLevel;

record QuoteRequest(Point origin, Point destination, ServiceLevel serviceLevel, Integer weightGrams) {

    record Point(Double latitude, Double longitude) {
    }
}
