CREATE ROLE pricing_owner LOGIN PASSWORD 'pricing_owner_dev';
CREATE ROLE pricing_app LOGIN PASSWORD 'pricing_app_dev';

CREATE DATABASE pricing OWNER pricing_owner;

REVOKE ALL ON DATABASE pricing FROM PUBLIC;
GRANT CONNECT ON DATABASE pricing TO pricing_app;

\connect pricing

CREATE EXTENSION IF NOT EXISTS postgis;
