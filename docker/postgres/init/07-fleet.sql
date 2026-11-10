CREATE ROLE fleet_owner LOGIN PASSWORD 'fleet_owner_dev';
CREATE ROLE fleet_app LOGIN PASSWORD 'fleet_app_dev';

CREATE DATABASE fleet OWNER fleet_owner;

REVOKE ALL ON DATABASE fleet FROM PUBLIC;
GRANT CONNECT ON DATABASE fleet TO fleet_app;
GRANT CONNECT ON DATABASE fleet TO debezium;
