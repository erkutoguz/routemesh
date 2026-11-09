GRANT SELECT ON outbox_events TO debezium;
GRANT DELETE ON outbox_events TO shipment_app;

CREATE PUBLICATION outbox_publication FOR TABLE outbox_events WITH (publish = 'insert');
