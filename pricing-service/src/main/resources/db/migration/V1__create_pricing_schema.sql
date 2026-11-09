CREATE TABLE service_levels (
    code           text    PRIMARY KEY,
    multiplier_bps integer NOT NULL,
    sla_minutes    integer NOT NULL,
    CONSTRAINT service_levels_multiplier_check CHECK (multiplier_bps > 0),
    CONSTRAINT service_levels_sla_check CHECK (sla_minutes > 0)
);

CREATE TABLE pricing_zones (
    id       uuid                         PRIMARY KEY DEFAULT gen_random_uuid(),
    code     text                         NOT NULL UNIQUE,
    name     text                         NOT NULL,
    geom     geography(MultiPolygon, 4326) NOT NULL,
    priority integer                      NOT NULL DEFAULT 0,
    active   boolean                      NOT NULL DEFAULT true,
    CONSTRAINT pricing_zones_geom_valid_check CHECK (ST_IsValid(geom::geometry))
);

CREATE INDEX pricing_zones_geom_idx ON pricing_zones USING GIST (geom);

CREATE TABLE zone_rates (
    zone_id               uuid    PRIMARY KEY REFERENCES pricing_zones (id),
    base_fee_minor        bigint  NOT NULL,
    per_km_minor          bigint  NOT NULL,
    included_weight_grams integer NOT NULL,
    per_extra_kg_minor    bigint  NOT NULL,
    min_fee_minor         bigint  NOT NULL,
    CONSTRAINT zone_rates_amounts_check CHECK (base_fee_minor >= 0 AND per_km_minor >= 0
        AND per_extra_kg_minor >= 0 AND min_fee_minor > 0),
    CONSTRAINT zone_rates_weight_check CHECK (included_weight_grams > 0)
);

CREATE TABLE quotes (
    id                   uuid             PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key      text             NOT NULL UNIQUE,
    request_hash         text             NOT NULL,
    merchant_id          uuid             NOT NULL,
    origin_latitude      double precision NOT NULL,
    origin_longitude     double precision NOT NULL,
    destination_latitude double precision NOT NULL,
    destination_longitude double precision NOT NULL,
    zone_id              uuid             NOT NULL REFERENCES pricing_zones (id),
    service_level        text             NOT NULL REFERENCES service_levels (code),
    weight_grams         integer          NOT NULL,
    distance_meters      bigint           NOT NULL,
    base_price_minor     bigint           NOT NULL,
    surge_bps            integer          NOT NULL,
    final_amount_minor   bigint           NOT NULL,
    currency             char(3)          NOT NULL,
    sla_minutes          integer          NOT NULL,
    created_at           timestamptz      NOT NULL DEFAULT now(),
    expires_at           timestamptz      NOT NULL,
    redeemed_at          timestamptz,
    redemption_key       text,
    redeemed_shipment_id uuid,
    CONSTRAINT quotes_weight_check CHECK (weight_grams BETWEEN 1 AND 30000),
    CONSTRAINT quotes_distance_check CHECK (distance_meters >= 0),
    CONSTRAINT quotes_amounts_check CHECK (base_price_minor > 0 AND final_amount_minor > 0),
    CONSTRAINT quotes_surge_check CHECK (surge_bps >= 10000),
    CONSTRAINT quotes_currency_check CHECK (currency = 'TRY'),
    CONSTRAINT quotes_expiry_check CHECK (expires_at > created_at),
    CONSTRAINT quotes_redemption_check CHECK (
        (redeemed_at IS NULL AND redemption_key IS NULL AND redeemed_shipment_id IS NULL)
        OR (redeemed_at IS NOT NULL AND redemption_key IS NOT NULL AND redeemed_shipment_id IS NOT NULL))
);

CREATE UNIQUE INDEX quotes_redemption_key_idx ON quotes (redemption_key) WHERE redemption_key IS NOT NULL;
CREATE UNIQUE INDEX quotes_redeemed_shipment_idx ON quotes (redeemed_shipment_id) WHERE redeemed_shipment_id IS NOT NULL;
CREATE INDEX quotes_merchant_idx ON quotes (merchant_id, created_at DESC);

CREATE FUNCTION forbid_modification() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% on % is not allowed', TG_OP, TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER quotes_no_delete BEFORE DELETE ON quotes
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER quotes_no_truncate BEFORE TRUNCATE ON quotes
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_modification();

CREATE FUNCTION forbid_quote_rewrite() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.redeemed_at IS NOT NULL THEN
        RAISE EXCEPTION 'quote % has already been redeemed', OLD.id
            USING ERRCODE = '55000';
    END IF;
    IF (NEW.id, NEW.idempotency_key, NEW.request_hash, NEW.merchant_id, NEW.zone_id, NEW.service_level,
        NEW.weight_grams, NEW.distance_meters, NEW.base_price_minor, NEW.surge_bps, NEW.final_amount_minor,
        NEW.created_at, NEW.expires_at)
        IS DISTINCT FROM
       (OLD.id, OLD.idempotency_key, OLD.request_hash, OLD.merchant_id, OLD.zone_id, OLD.service_level,
        OLD.weight_grams, OLD.distance_meters, OLD.base_price_minor, OLD.surge_bps, OLD.final_amount_minor,
        OLD.created_at, OLD.expires_at) THEN
        RAISE EXCEPTION 'only redemption columns of quote % may change', OLD.id
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER quotes_redeem_only BEFORE UPDATE ON quotes
    FOR EACH ROW EXECUTE FUNCTION forbid_quote_rewrite();

GRANT USAGE ON SCHEMA public TO pricing_app;
GRANT SELECT ON service_levels, pricing_zones, zone_rates TO pricing_app;
GRANT SELECT, INSERT ON quotes TO pricing_app;
GRANT UPDATE (redeemed_at, redemption_key, redeemed_shipment_id) ON quotes TO pricing_app;
