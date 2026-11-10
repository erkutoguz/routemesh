GRANT SELECT ON outbox_events TO debezium;

CREATE PUBLICATION outbox_publication FOR TABLE outbox_events WITH (publish = 'insert');
