CREATE ROLE ledger_owner LOGIN PASSWORD 'ledger_owner_dev';
CREATE ROLE ledger_app LOGIN PASSWORD 'ledger_app_dev';

CREATE DATABASE ledger OWNER ledger_owner;

REVOKE ALL ON DATABASE ledger FROM PUBLIC;
GRANT CONNECT ON DATABASE ledger TO ledger_app;
