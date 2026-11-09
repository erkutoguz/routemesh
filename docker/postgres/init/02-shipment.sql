CREATE ROLE shipment_owner LOGIN PASSWORD 'shipment_owner_dev';
CREATE ROLE shipment_app LOGIN PASSWORD 'shipment_app_dev';

CREATE DATABASE shipment OWNER shipment_owner;

REVOKE ALL ON DATABASE shipment FROM PUBLIC;
GRANT CONNECT ON DATABASE shipment TO shipment_app;
