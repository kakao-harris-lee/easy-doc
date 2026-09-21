-- R5: append-only review history. Snapshot rows are independently encrypted and can be
-- detached by retention pruning without deleting the event ledger.

CREATE TABLE review_snapshots (
    id uuid NOT NULL,
    conversion_id uuid NOT NULL,
    content_revision bigint NOT NULL,
    artifact_revision bigint NULL,
    kind character varying(32) NULL,
    payload_encrypted bytea NOT NULL,
    encryption_scheme character varying(16) NOT NULL,
    key_version smallint NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT pk_review_snapshots PRIMARY KEY (id),
    CONSTRAINT fk_review_snapshots_conversion FOREIGN KEY (conversion_id)
        REFERENCES conversions (id) ON DELETE CASCADE,
    CONSTRAINT ck_review_snapshots_revision
        CHECK (content_revision BETWEEN 1 AND 9007199254740991
            AND (artifact_revision IS NULL OR artifact_revision BETWEEN 0 AND 9007199254740991)),
    CONSTRAINT ck_review_snapshots_kind
        CHECK (kind IS NULL OR kind IN ('review_assessment', 'action_guide')),
    -- AES-GCM adds a 12-byte nonce and a 16-byte tag to the 64KiB plaintext bound.
    CONSTRAINT ck_review_snapshots_ciphertext_size
        CHECK (octet_length(payload_encrypted) <= 65600),
    CONSTRAINT ck_review_snapshots_scheme CHECK (encryption_scheme = 'aes256gcm-v1'),
    CONSTRAINT ck_review_snapshots_key_version CHECK (key_version > 0)
);

CREATE INDEX ix_review_snapshots_conversion_revision
    ON review_snapshots (conversion_id, content_revision DESC, created_at DESC, id DESC);

CREATE TABLE review_events (
    id uuid NOT NULL,
    conversion_id uuid NOT NULL,
    event_type character varying(32) NOT NULL,
    actor_user_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    content_revision bigint NOT NULL,
    artifact_revision bigint NULL,
    item_id uuid NULL,
    assessment_id uuid NULL,
    guide_id uuid NULL,
    snapshot_id uuid NULL,
    CONSTRAINT pk_review_events PRIMARY KEY (id),
    CONSTRAINT fk_review_events_conversion FOREIGN KEY (conversion_id)
        REFERENCES conversions (id) ON DELETE CASCADE,
    CONSTRAINT fk_review_events_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES review_snapshots (id) ON DELETE SET NULL,
    -- Actor is intentionally not a restrictive user FK. Document/account deletion cascades the
    -- conversion and history, while an orphaned ledger row must never block account deletion.
    CONSTRAINT ck_review_events_type CHECK
        (event_type IN ('item_confirmed', 'item_reopened', 'item_not_applicable',
                        'guide_reviewed', 'invalidated_by_edit')),
    CONSTRAINT ck_review_events_revision
        CHECK (content_revision BETWEEN 1 AND 9007199254740991
            AND (artifact_revision IS NULL OR artifact_revision BETWEEN 0 AND 9007199254740991))
);

CREATE INDEX ix_review_events_conversion_created
    ON review_events (conversion_id, created_at DESC, id DESC);
