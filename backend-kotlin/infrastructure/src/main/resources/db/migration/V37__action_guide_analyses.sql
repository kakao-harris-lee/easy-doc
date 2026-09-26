-- ER-28: private analysis snapshots are independent of legacy six-section guides.
CREATE TABLE action_guide_analyses (
    id uuid PRIMARY KEY,
    conversion_id uuid NOT NULL REFERENCES conversions(id) ON DELETE CASCADE,
    based_on_content_revision bigint NOT NULL CHECK (based_on_content_revision BETWEEN 1 AND 9007199254740991),
    payload_encrypted bytea NOT NULL,
    encryption_scheme varchar(16) NOT NULL CHECK (encryption_scheme = 'aes256gcm-v1'),
    key_version smallint NOT NULL CHECK (key_version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (conversion_id, based_on_content_revision),
    UNIQUE (id, conversion_id)
);
CREATE TABLE action_guide_analysis_requests (
    conversion_id uuid NOT NULL,
    request_id uuid NOT NULL,
    analysis_id uuid NOT NULL,
    PRIMARY KEY (conversion_id, request_id),
    FOREIGN KEY (analysis_id, conversion_id) REFERENCES action_guide_analyses(id, conversion_id) ON DELETE CASCADE
);
