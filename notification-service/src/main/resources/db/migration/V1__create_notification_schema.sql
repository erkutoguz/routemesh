CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    topic        TEXT        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX processed_events_processed_at_idx ON processed_events (processed_at);

CREATE TABLE notifications (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id       UUID        NOT NULL,
    template       TEXT        NOT NULL,
    recipient_type TEXT        NOT NULL CHECK (recipient_type IN ('MERCHANT', 'COURIER', 'RECIPIENT')),
    recipient_id   UUID        NOT NULL,
    channel        TEXT        NOT NULL CHECK (channel IN ('IN_APP', 'SMS')),
    shipment_id    UUID,
    params         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at        TIMESTAMPTZ,
    CONSTRAINT notifications_dedupe_key UNIQUE (event_id, template, recipient_id, channel),
    CONSTRAINT notifications_sms_unread_check CHECK (channel = 'IN_APP' OR read_at IS NULL)
);

CREATE INDEX notifications_recipient_idx ON notifications (recipient_id, created_at DESC, id DESC)
    WHERE channel = 'IN_APP';

GRANT USAGE ON SCHEMA public TO notification_app;
GRANT SELECT, INSERT, DELETE ON processed_events TO notification_app;
GRANT SELECT, INSERT ON notifications TO notification_app;
GRANT UPDATE (read_at) ON notifications TO notification_app;
