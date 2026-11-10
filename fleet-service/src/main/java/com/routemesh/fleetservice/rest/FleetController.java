package com.routemesh.fleetservice.rest;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.routemesh.fleetservice.domain.Courier;
import com.routemesh.fleetservice.domain.CourierStatus;
import com.routemesh.fleetservice.service.FleetException;
import com.routemesh.fleetservice.service.FleetException.Reason;
import com.routemesh.fleetservice.service.FleetService;

@RestController
@RequestMapping("/api/v1/fleet")
@PreAuthorize("hasRole('COURIER')")
class FleetController {

    private final FleetService fleetService;

    FleetController(FleetService fleetService) {
        this.fleetService = fleetService;
    }

    @PutMapping("/me/status")
    CourierResponse setStatus(@RequestBody StatusRequest request, Authentication authentication) {
        if (request.status() == null) {
            throw new FleetException(Reason.INVALID_REQUEST, "status is required");
        }
        return CourierResponse.from(fleetService.setStatus(courierId(authentication), request.status()));
    }

    @GetMapping("/me")
    CourierResponse me(Authentication authentication) {
        UUID courierId = courierId(authentication);
        return fleetService.findCourier(courierId)
                .map(CourierResponse::from)
                .orElseGet(() -> CourierResponse.from(
                        new Courier(courierId, CourierStatus.OFFLINE, 0, null, 0, null)));
    }

    @GetMapping("/me/offers")
    List<OfferResponse> offers(Authentication authentication) {
        return fleetService.openOffers(courierId(authentication)).stream().map(OfferResponse::from).toList();
    }

    @PostMapping("/offers/{id}/accept")
    OfferResponse accept(@PathVariable UUID id, Authentication authentication) {
        return OfferResponse.from(fleetService.accept(courierId(authentication), id));
    }

    @PostMapping("/offers/{id}/reject")
    OfferResponse reject(@PathVariable UUID id, Authentication authentication) {
        return OfferResponse.from(fleetService.reject(courierId(authentication), id));
    }

    private static UUID courierId(Authentication authentication) {
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new FleetException(Reason.INVALID_REQUEST, "token subject must be a courier UUID");
        }
    }
}
