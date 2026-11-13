package com.routemesh.pricingservice.rest;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.routemesh.pricingservice.domain.CreateQuoteCommand;
import com.routemesh.pricingservice.domain.Quote;
import com.routemesh.pricingservice.domain.QuoteResult;
import com.routemesh.pricingservice.service.PricingException;
import com.routemesh.pricingservice.service.PricingException.Reason;
import com.routemesh.pricingservice.service.QuoteService;

@RestController
@RequestMapping("/api/v1/quotes")
@PreAuthorize("hasRole('MERCHANT')")
class QuoteController {

    private final QuoteService quoteService;

    QuoteController(QuoteService quoteService) {
        this.quoteService = quoteService;
    }

    @PostMapping
    QuoteResponse create(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody QuoteRequest request, Authentication authentication) {
        if (request.origin() == null || request.destination() == null || request.origin().latitude() == null
                || request.origin().longitude() == null || request.destination().latitude() == null
                || request.destination().longitude() == null) {
            throw new PricingException(Reason.INVALID_REQUEST, "origin and destination are required");
        }
        if (request.weightGrams() == null) {
            throw new PricingException(Reason.INVALID_REQUEST, "weightGrams is required");
        }
        QuoteResult result = quoteService.create(new CreateQuoteCommand(idempotencyKey, merchantId(authentication),
                request.origin().latitude(), request.origin().longitude(), request.destination().latitude(),
                request.destination().longitude(), request.serviceLevel(), request.weightGrams()));
        return QuoteResponse.from(result.quote(), result.replayed());
    }

    @GetMapping("/{id}")
    QuoteResponse get(@PathVariable UUID id, Authentication authentication) {
        Quote quote = quoteService.get(id);
        if (!quote.merchantId().equals(merchantId(authentication))) {
            throw new PricingException(Reason.NOT_FOUND, "quote " + id + " does not exist");
        }
        return QuoteResponse.from(quote, false);
    }

    private static UUID merchantId(Authentication authentication) {
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new PricingException(Reason.INVALID_REQUEST, "token subject must be a merchant UUID");
        }
    }
}
