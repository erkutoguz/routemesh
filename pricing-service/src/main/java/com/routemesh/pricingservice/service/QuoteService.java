package com.routemesh.pricingservice.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.routemesh.pricingservice.cache.QuoteCache;
import com.routemesh.pricingservice.cache.SurgeProvider;
import com.routemesh.pricingservice.config.PricingProperties;
import com.routemesh.pricingservice.domain.CreateQuoteCommand;
import com.routemesh.pricingservice.domain.Quote;
import com.routemesh.pricingservice.domain.QuoteResult;
import com.routemesh.pricingservice.domain.QuoteStatus;
import com.routemesh.pricingservice.domain.RateCard;
import com.routemesh.pricingservice.domain.RedeemQuoteCommand;
import com.routemesh.pricingservice.domain.ServiceLevelConfig;
import com.routemesh.pricingservice.repository.QuoteRepository;
import com.routemesh.pricingservice.repository.QuoteRepository.StoredQuote;
import com.routemesh.pricingservice.service.PricingException.Reason;

@Service
public class QuoteService {

    private static final int MAX_KEY_LENGTH = 255;
    private static final int MIN_WEIGHT_GRAMS = 1;
    private static final int MAX_WEIGHT_GRAMS = 30_000;

    private final QuoteRepository repository;
    private final QuoteCache cache;
    private final SurgeProvider surgeProvider;
    private final PricingProperties properties;

    public QuoteService(QuoteRepository repository, QuoteCache cache, SurgeProvider surgeProvider,
                        PricingProperties properties) {
        this.repository = repository;
        this.cache = cache;
        this.surgeProvider = surgeProvider;
        this.properties = properties;
    }

    @Transactional
    public QuoteResult create(CreateQuoteCommand command) {
        validate(command);
        String requestHash = hash(command);

        StoredQuote existing = repository.findByIdempotencyKey(command.idempotencyKey()).orElse(null);
        if (existing != null) {
            return replayOrConflict(existing, requestHash);
        }

        RateCard rate = repository.findRateCard(command.destinationLatitude(), command.destinationLongitude())
                .orElseThrow(() -> new PricingException(Reason.OUT_OF_SERVICE_AREA,
                        "destination is outside the service area"));
        ServiceLevelConfig level = repository.findServiceLevel(command.serviceLevel())
                .orElseThrow(() -> new PricingException(Reason.INVALID_REQUEST, "unknown service_level"));
        long distance = repository.distanceMeters(command.originLatitude(), command.originLongitude(),
                command.destinationLatitude(), command.destinationLongitude());
        int surgeBps = surgeProvider.surgeBps(rate.zoneCode());
        PriceCalculator.Price price = PriceCalculator.calculate(rate, distance, command.weightGrams(),
                level.multiplierBps(), surgeBps);

        UUID id = repository.insert(command, requestHash, rate, distance, price.basePriceMinor(), surgeBps,
                price.finalAmountMinor(), level.slaMinutes(), properties.quoteTtl().toSeconds()).orElse(null);
        if (id == null) {
            return replayOrConflict(repository.findByIdempotencyKey(command.idempotencyKey()).orElseThrow(),
                    requestHash);
        }

        Quote quote = repository.findById(id).orElseThrow();
        afterCommit(() -> cache.put(quote));
        return new QuoteResult(quote, false);
    }

    @Transactional(readOnly = true)
    public Quote get(UUID quoteId) {
        Quote cached = cache.get(quoteId).orElse(null);
        if (cached != null) {
            return cached;
        }
        Quote quote = repository.findById(quoteId).orElseThrow(() -> notFound(quoteId));
        cache.put(quote);
        return quote;
    }

    @Transactional
    public QuoteResult redeem(RedeemQuoteCommand command) {
        validate(command);
        Quote quote = repository.findByIdForUpdate(command.quoteId()).orElseThrow(() -> notFound(command.quoteId()));

        if (quote.status() == QuoteStatus.REDEEMED) {
            boolean sameRedemption = Objects.equals(quote.redemptionKey(), command.redemptionKey())
                    && Objects.equals(quote.redeemedShipmentId(), command.shipmentId());
            if (sameRedemption) {
                return new QuoteResult(quote, true);
            }
            throw new PricingException(Reason.ALREADY_REDEEMED, "quote has already been redeemed");
        }
        if (quote.status() == QuoteStatus.EXPIRED) {
            throw new PricingException(Reason.QUOTE_EXPIRED, "quote has expired");
        }

        int updated;
        try {
            updated = repository.redeem(quote.id(), command.redemptionKey(), command.shipmentId());
        } catch (DuplicateKeyException e) {
            throw new PricingException(Reason.ALREADY_REDEEMED,
                    "redemption_key or shipment_id was already used for another quote");
        }
        if (updated != 1) {
            throw new PricingException(Reason.QUOTE_EXPIRED, "quote has expired");
        }

        afterCommit(() -> cache.evict(quote.id()));
        return new QuoteResult(repository.findById(quote.id()).orElseThrow(), false);
    }

    private QuoteResult replayOrConflict(StoredQuote existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new PricingException(Reason.IDEMPOTENCY_CONFLICT,
                    "idempotency_key was already used with a different request");
        }
        return new QuoteResult(existing.quote(), true);
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private static void validate(CreateQuoteCommand command) {
        requireKey(command.idempotencyKey(), "idempotency_key");
        validatePoint(command.originLatitude(), command.originLongitude(), "origin");
        validatePoint(command.destinationLatitude(), command.destinationLongitude(), "destination");
        if (command.serviceLevel() == null) {
            throw invalid("service_level is required");
        }
        if (command.weightGrams() < MIN_WEIGHT_GRAMS || command.weightGrams() > MAX_WEIGHT_GRAMS) {
            throw invalid("weight_grams must be between " + MIN_WEIGHT_GRAMS + " and " + MAX_WEIGHT_GRAMS);
        }
    }

    private static void validate(RedeemQuoteCommand command) {
        requireKey(command.redemptionKey(), "redemption_key");
    }

    /**
     * Noktanın fiyat bölgesi; teklifin teslimat noktası için seçtiği kuralla aynıdır (aktif bölge, en yüksek öncelik,
     * en küçük alan). Hizmet bölgesi dışında boş döner.
     */
    public Optional<String> locateZone(double latitude, double longitude) {
        validatePoint(latitude, longitude, "location");
        return repository.findRateCard(latitude, longitude).map(RateCard::zoneCode);
    }

    private static void validatePoint(double latitude, double longitude, String name) {
        if (!(latitude >= -90 && latitude <= 90)) {
            throw invalid(name + " latitude must be between -90 and 90");
        }
        if (!(longitude >= -180 && longitude <= 180)) {
            throw invalid(name + " longitude must be between -180 and 180");
        }
    }

    private static void requireKey(String value, String field) {
        if (value == null || value.isBlank() || value.length() > MAX_KEY_LENGTH) {
            throw invalid(field + " must be 1 to " + MAX_KEY_LENGTH + " characters");
        }
    }

    private static String hash(CreateQuoteCommand c) {
        String canonical = String.join("|", String.valueOf(c.merchantId()),
                String.valueOf(c.originLatitude()), String.valueOf(c.originLongitude()),
                String.valueOf(c.destinationLatitude()), String.valueOf(c.destinationLongitude()),
                String.valueOf(c.serviceLevel()), String.valueOf(c.weightGrams()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PricingException notFound(UUID quoteId) {
        return new PricingException(Reason.NOT_FOUND, "quote " + quoteId + " does not exist");
    }

    private static PricingException invalid(String message) {
        return new PricingException(Reason.INVALID_REQUEST, message);
    }
}
