CREATE ROLE payment_owner LOGIN PASSWORD 'payment_owner_dev';
CREATE ROLE payment_app LOGIN PASSWORD 'payment_app_dev';

CREATE DATABASE payment OWNER payment_owner;

REVOKE ALL ON DATABASE payment FROM PUBLIC;
GRANT CONNECT ON DATABASE payment TO payment_app;
GRANT CONNECT ON DATABASE payment TO debezium;
