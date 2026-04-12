-- Enable required PostgreSQL extensions for PGVector and Spring Modulith
-- Spring AI's initialize-schema=true creates the vector_store table and index,
-- but the extensions must exist first.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Spring Modulith event publication registry table.
-- Created here (before Spring Boot connects) with TEXT columns to avoid
-- the VARCHAR(255) default that Hibernate would use from its entity mapping.
CREATE TABLE IF NOT EXISTS event_publication (
    id                      UUID                     NOT NULL PRIMARY KEY,
    listener_id             TEXT                     NOT NULL,
    event_type              TEXT                     NOT NULL,
    serialized_event        TEXT                     NOT NULL,
    publication_date        TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date         TIMESTAMP WITH TIME ZONE,
    status                  VARCHAR(50)              NOT NULL DEFAULT 'PUBLISHED',
    completion_attempts     INTEGER                  NOT NULL DEFAULT 0,
    last_resubmission_date  TIMESTAMP WITH TIME ZONE
);

-- Widen columns if a previous Hibernate run created them as VARCHAR(255).
ALTER TABLE event_publication ALTER COLUMN serialized_event TYPE TEXT;
ALTER TABLE event_publication ALTER COLUMN event_type        TYPE TEXT;
ALTER TABLE event_publication ALTER COLUMN listener_id       TYPE TEXT;
