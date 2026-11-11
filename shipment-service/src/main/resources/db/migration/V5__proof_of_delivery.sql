-- Teslimat kanıtı (PoD): fotoğraf/imza dosyaları, 50 m doğrulamalı kayıt ve alıcı OTP'si.
-- proof_of_delivery bugüne kadar hiç satır almadı (uygulama rolünün INSERT yetkisi yoktu), NOT NULL kolonlar güvenli.

CREATE TABLE pod_files (
    id          uuid        PRIMARY KEY,
    shipment_id uuid        NOT NULL REFERENCES shipments (id),
    kind        text        NOT NULL,
    object_key  text        NOT NULL UNIQUE,
    sha256      text        NOT NULL,
    size_bytes  bigint      NOT NULL,
    content_type text       NOT NULL,
    uploaded_by uuid        NOT NULL,
    uploaded_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pod_files_kind_check CHECK (kind IN ('PHOTO', 'SIGNATURE')),
    CONSTRAINT pod_files_size_check CHECK (size_bytes > 0 AND size_bytes <= 5242880),
    CONSTRAINT pod_files_content_type_check CHECK (content_type IN ('image/jpeg', 'image/png')),
    CONSTRAINT pod_files_sha256_check CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    -- proof_of_delivery'nin bileşik yabancı anahtarı için
    CONSTRAINT pod_files_id_shipment_unique UNIQUE (id, shipment_id)
);

CREATE INDEX pod_files_shipment_idx ON pod_files (shipment_id);

ALTER TABLE proof_of_delivery
    ADD COLUMN courier_id          uuid             NOT NULL,
    ADD COLUMN delivered_latitude  double precision NOT NULL,
    ADD COLUMN delivered_longitude double precision NOT NULL,
    ADD COLUMN distance_meters     double precision NOT NULL,
    ADD COLUMN accuracy_meters     double precision,
    ADD COLUMN recipient_name      text             NOT NULL,
    ADD COLUMN photo_file_id       uuid             NOT NULL,
    ADD COLUMN signature_file_id   uuid,
    ADD COLUMN otp_verified        boolean          NOT NULL DEFAULT false,
    -- 50 m kuralının son güvencesi: servis kodu atlansa bile uzaktan kayıt girmez
    ADD CONSTRAINT proof_of_delivery_distance_check CHECK (distance_meters >= 0 AND distance_meters <= 50),
    ADD CONSTRAINT proof_of_delivery_latitude_check CHECK (delivered_latitude BETWEEN -90 AND 90),
    ADD CONSTRAINT proof_of_delivery_longitude_check CHECK (delivered_longitude BETWEEN -180 AND 180),
    ADD CONSTRAINT proof_of_delivery_accuracy_check CHECK (accuracy_meters IS NULL OR accuracy_meters >= 0),
    ADD CONSTRAINT proof_of_delivery_recipient_check CHECK (length(recipient_name) BETWEEN 1 AND 200),
    -- dosya aynı gönderiye ait olmalı
    ADD CONSTRAINT proof_of_delivery_photo_fk
        FOREIGN KEY (photo_file_id, shipment_id) REFERENCES pod_files (id, shipment_id),
    ADD CONSTRAINT proof_of_delivery_signature_fk
        FOREIGN KEY (signature_file_id, shipment_id) REFERENCES pod_files (id, shipment_id);

-- Dosya türü: fotoğraf alanı PHOTO, imza alanı SIGNATURE dosyasına işaret etmeli
CREATE FUNCTION validate_proof_of_delivery_files() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pod_files WHERE id = NEW.photo_file_id AND kind = 'PHOTO') THEN
        RAISE EXCEPTION 'photo_file_id must reference a PHOTO file' USING ERRCODE = '23514';
    END IF;
    IF NEW.signature_file_id IS NOT NULL
        AND NOT EXISTS (SELECT 1 FROM pod_files WHERE id = NEW.signature_file_id AND kind = 'SIGNATURE') THEN
        RAISE EXCEPTION 'signature_file_id must reference a SIGNATURE file' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER proof_of_delivery_validate_files BEFORE INSERT ON proof_of_delivery
    FOR EACH ROW EXECUTE FUNCTION validate_proof_of_delivery_files();

-- Kanıt ve dosya kayıtları değişmez
CREATE TRIGGER proof_of_delivery_immutable BEFORE UPDATE OR DELETE ON proof_of_delivery
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER proof_of_delivery_no_truncate BEFORE TRUNCATE ON proof_of_delivery
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER pod_files_immutable BEFORE UPDATE OR DELETE ON pod_files
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER pod_files_no_truncate BEFORE TRUNCATE ON pod_files
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_modification();

-- Alıcı OTP'si: kod yalnızca HMAC olarak saklanır
CREATE TABLE delivery_otps (
    shipment_id uuid        PRIMARY KEY REFERENCES shipments (id),
    code_hash   text        NOT NULL,
    issued_at   timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    attempts    integer     NOT NULL DEFAULT 0,
    verified_at timestamptz,
    CONSTRAINT delivery_otps_attempts_check CHECK (attempts BETWEEN 0 AND 5),
    CONSTRAINT delivery_otps_expiry_check CHECK (expires_at > issued_at)
);

GRANT SELECT, INSERT ON pod_files TO shipment_app;
GRANT INSERT ON proof_of_delivery TO shipment_app;
GRANT SELECT, INSERT ON delivery_otps TO shipment_app;
GRANT UPDATE (code_hash, issued_at, expires_at, attempts, verified_at) ON delivery_otps TO shipment_app;
