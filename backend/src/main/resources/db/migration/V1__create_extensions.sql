-- V1: Enable required PostgreSQL extensions
-- This migration runs before any table creation.
-- It is idempotent (IF NOT EXISTS) and safe to re-run.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
