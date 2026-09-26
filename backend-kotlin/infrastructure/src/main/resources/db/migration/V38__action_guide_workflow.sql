ALTER TABLE action_guide_jobs ADD COLUMN operation varchar(16) NOT NULL DEFAULT 'guide'
    CHECK (operation IN ('guide', 'analysis'));
DO $$ DECLARE constraint_name text; BEGIN
    SELECT conname INTO constraint_name FROM pg_constraint
    WHERE conrelid = 'action_guide_analyses'::regclass AND contype = 'u'
      AND pg_get_constraintdef(oid) = 'UNIQUE (conversion_id, based_on_content_revision)';
    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE action_guide_analyses DROP CONSTRAINT %I', constraint_name);
    END IF;
END $$;
ALTER TABLE action_guide_analyses ADD COLUMN analysis_revision bigint NOT NULL DEFAULT 1 CHECK (analysis_revision > 0);
ALTER TABLE action_guide_analyses ADD COLUMN review_revision bigint NOT NULL DEFAULT 0 CHECK (review_revision >= 0);
CREATE TABLE action_guide_drafts (
    id uuid PRIMARY KEY,
    conversion_id uuid NOT NULL REFERENCES conversions(id) ON DELETE CASCADE,
    analysis_id uuid NOT NULL REFERENCES action_guide_analyses(id) ON DELETE CASCADE,
    request_id uuid NOT NULL,
    draft_revision bigint NOT NULL CHECK (draft_revision > 0),
    payload_encrypted bytea NOT NULL,
    encryption_scheme varchar(32) NOT NULL,
    key_version integer NOT NULL CHECK (key_version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(conversion_id, request_id)
);
CREATE INDEX idx_action_guide_drafts_conversion ON action_guide_drafts(conversion_id, created_at DESC);
