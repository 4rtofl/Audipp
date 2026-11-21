ALTER TABLE devices ADD UNIQUE (user_id, device_id);

CREATE TABLE sync_events (
    user_id uuid NOT NULL,
    event_id uuid NOT NULL,
    device_id uuid NOT NULL,
    entity_type text NOT NULL,
    entity_id text NOT NULL,
    operation text NOT NULL CHECK (operation IN ('upsert', 'delete')),
    base_revision bigint NOT NULL CHECK (base_revision >= 0),
    history_epoch bigint NOT NULL CHECK (history_epoch >= 0),
    server_revision bigint NOT NULL UNIQUE DEFAULT nextval('server_revision') CHECK (server_revision > 0),
    payload jsonb,
    request_sha256 bytea CHECK (octet_length(request_sha256) = 32),
    CHECK ((request_sha256 IS NULL) = (entity_type = 'history' AND payload IS NULL)),
    PRIMARY KEY (user_id, event_id),
    FOREIGN KEY (user_id, device_id) REFERENCES devices(user_id, device_id)
);

CREATE INDEX sync_events_entity_revision
    ON sync_events(user_id, entity_type, entity_id, server_revision DESC);
CREATE INDEX sync_events_owner_revision ON sync_events(user_id, server_revision);
CREATE UNIQUE INDEX sync_history_id ON sync_events(user_id, entity_id) WHERE entity_type = 'history';

CREATE TABLE history_epochs (
    user_id uuid PRIMARY KEY REFERENCES users(user_id),
    history_epoch bigint NOT NULL CHECK (history_epoch > 0),
    server_revision bigint NOT NULL DEFAULT nextval('server_revision') CHECK (server_revision > 0)
);

CREATE TABLE history_tombstones (
    user_id uuid NOT NULL REFERENCES users(user_id),
    entity_id uuid NOT NULL,
    server_revision bigint NOT NULL DEFAULT nextval('server_revision') CHECK (server_revision > 0),
    PRIMARY KEY (user_id, entity_id)
);
