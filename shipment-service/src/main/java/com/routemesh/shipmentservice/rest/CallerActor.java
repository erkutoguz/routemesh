package com.routemesh.shipmentservice.rest;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import com.routemesh.shipmentservice.domain.Actor;
import com.routemesh.shipmentservice.domain.Shipment;

record CallerActor(Actor actor, String subject) {

    private static final Set<Actor> REST_ACTORS =
            EnumSet.of(Actor.MERCHANT, Actor.COURIER, Actor.WAREHOUSE, Actor.OPS);

    static CallerActor from(Authentication authentication) {
        Set<Actor> actors = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .flatMap(role -> REST_ACTORS.stream().filter(actor -> actor.name().equals(role)))
                .collect(Collectors.toSet());
        if (actors.size() != 1) {
            throw new AccessDeniedException("token must carry exactly one actor role");
        }
        return new CallerActor(actors.iterator().next(), authentication.getName());
    }

    Predicate<Shipment> canAccess() {
        return switch (actor) {
            case MERCHANT -> shipment -> shipment.merchantId().toString().equals(subject);
            case COURIER -> shipment -> shipment.courierId() != null && shipment.courierId().toString().equals(subject);
            default -> shipment -> true;
        };
    }
}
