-- Server-side Toss billing-auth hand-off. Bearer values are SHA-256 fingerprints; credentials
-- and the retained resume value stay inside the callback envelope.
CREATE TABLE toss_billing_callbacks (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    session_id uuid NOT NULL,
    customer uuid NOT NULL,
    environment text NOT NULL CHECK (environment IN ('toss_test','toss_live')),
    purpose text NOT NULL CHECK (purpose IN ('purchase','replace_card')),
    state_hash char(64) NOT NULL UNIQUE CHECK (length(state_hash)=64),
    resume_hash char(64) UNIQUE CHECK (resume_hash IS NULL OR length(resume_hash)=64),
    state_expires_at timestamptz NOT NULL,
    auth_expires_at timestamptz,
    resume_expires_at timestamptz,
    processing_until timestamptz,
    completed_at timestamptz,
    payload_encrypted bytea NOT NULL,
    encryption_scheme varchar(32) NOT NULL CHECK (encryption_scheme='aes256gcm-v1'),
    key_version smallint NOT NULL CHECK (key_version >= 1),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((resume_hash IS NULL AND resume_expires_at IS NULL) OR
           (resume_hash IS NOT NULL AND resume_expires_at IS NOT NULL)),
    CHECK (completed_at IS NULL OR auth_expires_at IS NULL)
);

-- A workspace has one active billing session, so one callback artifact per session is enough.
CREATE UNIQUE INDEX ux_toss_billing_callback_session ON toss_billing_callbacks(workspace_id,session_id);
CREATE INDEX ix_toss_billing_callback_resume_expiry
    ON toss_billing_callbacks(resume_expires_at)
    WHERE resume_hash IS NOT NULL;
CREATE INDEX ix_toss_billing_callback_state_expiry
    ON toss_billing_callbacks(state_expires_at)
    WHERE resume_hash IS NULL;
