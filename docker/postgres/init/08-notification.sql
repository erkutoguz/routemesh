CREATE ROLE notification_owner LOGIN PASSWORD 'notification_owner_dev';
CREATE ROLE notification_app LOGIN PASSWORD 'notification_app_dev';

CREATE DATABASE notification OWNER notification_owner;

REVOKE ALL ON DATABASE notification FROM PUBLIC;
GRANT CONNECT ON DATABASE notification TO notification_app;
