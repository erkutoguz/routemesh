package com.routemesh.pricingservice.grpc;

import org.springframework.grpc.server.service.GrpcService;

import com.routemesh.contracts.pricing.CreateQuoteRequest;
import com.routemesh.contracts.pricing.CreateQuoteResponse;
import com.routemesh.contracts.pricing.GetQuoteRequest;
import com.routemesh.contracts.pricing.LocateZoneRequest;
import com.routemesh.contracts.pricing.LocateZoneResponse;
import com.routemesh.contracts.pricing.PricingServiceGrpc;
import com.routemesh.contracts.pricing.Quote;
import com.routemesh.contracts.pricing.RedeemQuoteRequest;
import com.routemesh.contracts.pricing.RedeemQuoteResponse;
import com.routemesh.pricingservice.domain.QuoteResult;
import com.routemesh.pricingservice.service.PricingException;
import com.routemesh.pricingservice.service.QuoteService;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

@GrpcService
public class PricingGrpcService extends PricingServiceGrpc.PricingServiceImplBase {

    private final QuoteService quoteService;

    public PricingGrpcService(QuoteService quoteService) {
        this.quoteService = quoteService;
    }

    @Override
    public void createQuote(CreateQuoteRequest request, StreamObserver<CreateQuoteResponse> observer) {
        try {
            QuoteResult result = quoteService.create(QuoteMapper.toCommand(request));
            observer.onNext(CreateQuoteResponse.newBuilder()
                    .setQuote(QuoteMapper.toProto(result.quote()))
                    .setReplayed(result.replayed())
                    .build());
            observer.onCompleted();
        } catch (PricingException e) {
            observer.onError(toStatus(e));
        }
    }

    @Override
    public void getQuote(GetQuoteRequest request, StreamObserver<Quote> observer) {
        try {
            observer.onNext(QuoteMapper.toProto(
                    quoteService.get(QuoteMapper.parseUuid(request.getQuoteId(), "quote_id"))));
            observer.onCompleted();
        } catch (PricingException e) {
            observer.onError(toStatus(e));
        }
    }

    @Override
    public void redeemQuote(RedeemQuoteRequest request, StreamObserver<RedeemQuoteResponse> observer) {
        try {
            QuoteResult result = quoteService.redeem(QuoteMapper.toCommand(request));
            observer.onNext(RedeemQuoteResponse.newBuilder()
                    .setQuote(QuoteMapper.toProto(result.quote()))
                    .setReplayed(result.replayed())
                    .build());
            observer.onCompleted();
        } catch (PricingException e) {
            observer.onError(toStatus(e));
        }
    }

    @Override
    public void locateZone(LocateZoneRequest request, StreamObserver<LocateZoneResponse> observer) {
        try {
            if (!request.hasLocation()) {
                throw new PricingException(PricingException.Reason.INVALID_REQUEST, "location is required");
            }
            LocateZoneResponse.Builder response = LocateZoneResponse.newBuilder();
            quoteService.locateZone(request.getLocation().getLatitude(), request.getLocation().getLongitude())
                    .ifPresent(response::setZoneCode);
            observer.onNext(response.build());
            observer.onCompleted();
        } catch (PricingException e) {
            observer.onError(toStatus(e));
        }
    }

    private static StatusRuntimeException toStatus(PricingException e) {
        Status status = switch (e.getReason()) {
            case INVALID_REQUEST -> Status.INVALID_ARGUMENT;
            case NOT_FOUND -> Status.NOT_FOUND;
            case OUT_OF_SERVICE_AREA, QUOTE_EXPIRED -> Status.FAILED_PRECONDITION;
            case IDEMPOTENCY_CONFLICT, ALREADY_REDEEMED -> Status.ALREADY_EXISTS;
        };
        return status.withDescription(e.getMessage()).asRuntimeException();
    }
}
