-- Mandatory recovery records for full-document application. These do not depend on the optional R5 history flag.
CREATE TABLE action_guide_body_snapshots (
    id uuid PRIMARY KEY,
    conversion_id uuid NOT NULL REFERENCES conversions(id) ON DELETE CASCADE,
    request_id uuid NOT NULL,
    draft_id uuid NOT NULL,
    content_revision bigint NOT NULL CHECK (content_revision BETWEEN 1 AND 9007199254740990),
    applied_content_revision bigint NOT NULL CHECK (applied_content_revision = content_revision + 1),
    analysis_revision bigint NOT NULL CHECK (analysis_revision BETWEEN 1 AND 9007199254740991),
    draft_revision bigint NOT NULL CHECK (draft_revision BETWEEN 1 AND 9007199254740991),
    review_revision bigint NOT NULL CHECK (review_revision BETWEEN 0 AND 9007199254740991),
    payload_encrypted bytea NOT NULL,
    encryption_scheme varchar(16) NOT NULL CHECK (encryption_scheme = 'aes256gcm-v1'),
    key_version smallint NOT NULL CHECK (key_version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (conversion_id, request_id),
    UNIQUE (conversion_id, content_revision)
);
