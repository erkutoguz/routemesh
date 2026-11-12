CREATE ROLE search_owner LOGIN PASSWORD 'search_owner_dev';
CREATE ROLE search_app LOGIN PASSWORD 'search_app_dev';

CREATE DATABASE search OWNER search_owner;

REVOKE ALL ON DATABASE search FROM PUBLIC;
GRANT CONNECT ON DATABASE search TO search_app;
