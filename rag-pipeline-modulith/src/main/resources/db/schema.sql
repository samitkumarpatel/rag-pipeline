-- Spring Modulith event publication registry.
-- spring.sql.init runs this BEFORE Hibernate initializes (Spring Boot guarantee),
-- so the table is always created with TEXT columns.
-- ddl-auto=none means Hibernate will never touch this table.
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

-- Widen columns if the table was previously created with VARCHAR(255).
ALTER TABLE event_publication ALTER COLUMN serialized_event TYPE TEXT;
ALTER TABLE event_publication ALTER COLUMN event_type        TYPE TEXT;
ALTER TABLE event_publication ALTER COLUMN listener_id       TYPE TEXT;
