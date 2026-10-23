-- Events this service owes to Kafka. A row is written in the same transaction as the
-- change it reports, so the change and the event are committed or rolled back together.
-- A relay reads the rows afterwards and publishes them. The message contract (topic, key,
-- headers) is the team's Kafka design document, "Message 계약".
--
-- The columns become the message: id -> header ce_id, event_type -> header ce_type,
-- aggregate_id -> header ce_subject, created_at -> header ce_time, seq -> header
-- ce_sequence, event_key -> record key, payload -> record value.
CREATE TABLE outbox_events (
    -- Unlike users.id, generated here: rows are written by SQL inside the persistence
    -- transaction and nothing in the application needs the id before the insert.
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    -- Publishing order. created_at is the transaction's start time, which can differ from
    -- commit order, and uuidv7 is not ordered across sessions. seq is assigned at INSERT.
    seq bigint GENERATED ALWAYS AS IDENTITY,
    event_type varchar(100) NOT NULL,
    -- Kafka record key. Kept apart from the payload so the relay never parses JSON.
    event_key varchar(100) NOT NULL,
    -- The thing the event is about. Also inside the payload; repeated so consumers can
    -- read it from a header.
    aggregate_id uuid NOT NULL,
    payload jsonb NOT NULL,
    -- W3C Trace Context (header traceparent). The relay runs on another thread after the
    -- request has ended, so a trace can only continue if it is stored with the row.
    -- Empty until tracing is added. A version 00 value is always 55 characters.
    traceparent varchar(55),
    created_at timestamptz NOT NULL DEFAULT now(),
    -- Set once Kafka has confirmed the record. Rows are deleted some days after this.
    published_at timestamptz,
    -- Failed publish attempts and the last cause. A record, not a quarantine criterion:
    -- during a broker outage the count of healthy rows rises too.
    attempts integer NOT NULL DEFAULT 0,
    last_error text,
    -- Set for a row that can never be sent, such as an event type the relay has no topic
    -- for. The relay skips it until someone clears this column.
    quarantined_at timestamptz
);

-- The relay looks for rows that are neither published nor quarantined, in seq order.
-- A partial index, so it does not grow with the rows that are already published.
CREATE INDEX ix_outbox_events_pending ON outbox_events (seq)
    WHERE published_at IS NULL AND quarantined_at IS NULL;
