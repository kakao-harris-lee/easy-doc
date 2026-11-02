-- R7 ER-18 요청형 그림 생성 작업과 작업당 최대 한 개의 비공개 PNG 자산.
-- 감사/정산 행은 문서 삭제 뒤에도 남기고, 생성 입력·메타데이터·PNG는 모두 파기한다.

CREATE TABLE illustration_generation_jobs (
    id uuid NOT NULL,
    request_id uuid NOT NULL,
    owner_user_id uuid NULL,
    workspace_id uuid NULL,
    document_id uuid NOT NULL,
    conversion_id uuid NOT NULL,
    suggestion_result_id uuid NOT NULL,
    suggestion_id uuid NOT NULL,
    analysis_version character varying(64) NOT NULL,
    configuration_revision character varying(64) NOT NULL,
    expected_content_revision bigint NOT NULL,
    based_on_content_revision bigint NOT NULL,
    input_fingerprint character varying(64) NOT NULL,
    input_payload_encrypted bytea NULL,
    encryption_scheme character varying(16) NULL,
    key_version smallint NULL,
    status character varying(16) NOT NULL DEFAULT 'queued',
    reserved_credits numeric NOT NULL,
    settlement character varying(16) NOT NULL DEFAULT 'reserved',
    failure_code character varying(64) NULL,
    attempts integer NOT NULL DEFAULT 0,
    provider_attempts integer NOT NULL DEFAULT 0,
    provider_execution_id uuid NULL,
    provider_started_at timestamp with time zone NULL,
    lease_owner character varying(64) NULL,
    lease_until timestamp with time zone NULL,
    worker_slot smallint NULL,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT pk_illustration_generation_jobs PRIMARY KEY (id),
    CONSTRAINT uq_illustration_generation_jobs_id_conversion UNIQUE (id, conversion_id),
    CONSTRAINT fk_illustration_generation_jobs_owner FOREIGN KEY (owner_user_id)
        REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_illustration_generation_jobs_workspace FOREIGN KEY (workspace_id)
        REFERENCES workspaces (id) ON DELETE SET NULL,
    CONSTRAINT uq_illustration_generation_jobs_request UNIQUE (owner_user_id, conversion_id, request_id),
    CONSTRAINT ck_illustration_generation_jobs_status
        CHECK (status IN ('queued', 'running', 'succeeded', 'failed', 'superseded')),
    CONSTRAINT ck_illustration_generation_jobs_settlement
        CHECK (settlement IN ('reserved', 'consumed', 'released', 'not_charged')),
    CONSTRAINT ck_illustration_generation_jobs_failure
        CHECK (failure_code IS NULL OR failure_code IN ('generation_failed', 'result_invalid', 'outcome_unknown')),
    CONSTRAINT ck_illustration_generation_jobs_revisions CHECK (
        expected_content_revision BETWEEN 1 AND 9007199254740991
        AND based_on_content_revision BETWEEN 1 AND 9007199254740991
    ),
    CONSTRAINT ck_illustration_generation_jobs_configuration_revision
        CHECK (configuration_revision ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_illustration_generation_jobs_fingerprint CHECK (length(input_fingerprint) = 64),
    CONSTRAINT ck_illustration_generation_jobs_input_envelope CHECK (
        (input_payload_encrypted IS NULL) = (encryption_scheme IS NULL)
        AND (input_payload_encrypted IS NULL) = (key_version IS NULL)
        AND (encryption_scheme IS NULL OR encryption_scheme = 'aes256gcm-v1')
        AND (key_version IS NULL OR key_version > 0)
        AND (status NOT IN ('queued', 'running') OR input_payload_encrypted IS NOT NULL)
    ),
    CONSTRAINT ck_illustration_generation_jobs_reserved_non_negative CHECK (reserved_credits >= 0),
    CONSTRAINT ck_illustration_generation_jobs_reserved_tenth
        CHECK (reserved_credits = trunc(reserved_credits, 1)),
    CONSTRAINT ck_illustration_generation_jobs_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_illustration_generation_jobs_provider_attempts CHECK (provider_attempts BETWEEN 0 AND 1),
    CONSTRAINT ck_illustration_generation_jobs_provider_started CHECK (
        (provider_started_at IS NULL) = (provider_attempts = 0)
        AND (provider_execution_id IS NULL) = (provider_attempts = 0)
    ),
    CONSTRAINT ck_illustration_generation_jobs_lease CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CONSTRAINT ck_illustration_generation_jobs_running_lease
        CHECK ((status = 'running') = (lease_owner IS NOT NULL)),
    CONSTRAINT ck_illustration_generation_jobs_worker_slot CHECK (
        (status = 'running' AND worker_slot BETWEEN 1 AND 2)
        OR (status <> 'running' AND worker_slot IS NULL)
    ),
    CONSTRAINT ck_illustration_generation_jobs_terminal_settlement CHECK (
        (reserved_credits = 0 AND settlement = 'not_charged')
        OR (reserved_credits > 0 AND (
            (status IN ('queued', 'running') AND settlement = 'reserved')
            OR (status = 'succeeded' AND settlement = 'consumed')
            OR (status IN ('failed', 'superseded') AND settlement = 'released')
        ))
    )
);

CREATE UNIQUE INDEX uq_illustration_generation_jobs_active_document
    ON illustration_generation_jobs (document_id) WHERE status IN ('queued', 'running');
CREATE UNIQUE INDEX uq_illustration_generation_jobs_active_owner
    ON illustration_generation_jobs (owner_user_id) WHERE status IN ('queued', 'running');
CREATE UNIQUE INDEX uq_illustration_generation_jobs_running_slot
    ON illustration_generation_jobs (worker_slot) WHERE status = 'running';
CREATE INDEX ix_illustration_generation_jobs_queued
    ON illustration_generation_jobs (created_at, id) WHERE status = 'queued';
CREATE INDEX ix_illustration_generation_jobs_expired
    ON illustration_generation_jobs (lease_until, id) WHERE status = 'running';
CREATE INDEX ix_illustration_generation_jobs_document
    ON illustration_generation_jobs (document_id);

CREATE TABLE generated_illustration_assets (
    id uuid NOT NULL,
    job_id uuid NOT NULL,
    conversion_id uuid NOT NULL,
    based_on_content_revision bigint NOT NULL,
    metadata_encrypted bytea NOT NULL,
    png_encrypted bytea NOT NULL,
    encryption_scheme character varying(16) NOT NULL,
    key_version smallint NOT NULL,
    png_byte_size integer NOT NULL,
    width integer NOT NULL,
    height integer NOT NULL,
    provenance character varying(16) NOT NULL CHECK (provenance IN ('fake', 'provider')),
    asset_revision bigint NOT NULL DEFAULT 1,
    applied boolean NOT NULL DEFAULT false,
    applied_content_revision bigint NULL,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT pk_generated_illustration_assets PRIMARY KEY (id),
    CONSTRAINT uq_generated_illustration_assets_job UNIQUE (job_id),
    CONSTRAINT fk_generated_illustration_assets_job FOREIGN KEY (job_id, conversion_id)
        REFERENCES illustration_generation_jobs (id, conversion_id) ON DELETE CASCADE,
    CONSTRAINT fk_generated_illustration_assets_conversion FOREIGN KEY (conversion_id)
        REFERENCES conversions (id) ON DELETE CASCADE,
    CONSTRAINT ck_generated_illustration_assets_revision
        CHECK (based_on_content_revision BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_generated_illustration_assets_scheme CHECK (encryption_scheme = 'aes256gcm-v1'),
    CONSTRAINT ck_generated_illustration_assets_key_version CHECK (key_version > 0),
    CONSTRAINT ck_generated_illustration_assets_png_size CHECK (png_byte_size BETWEEN 1 AND 8388608),
    CONSTRAINT ck_generated_illustration_assets_dimensions CHECK (width = 1024 AND height = 1024),
    CONSTRAINT ck_generated_illustration_assets_asset_revision
        CHECK (asset_revision BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_generated_illustration_assets_application CHECK (
        (applied AND applied_content_revision BETWEEN 1 AND 9007199254740991)
        OR (NOT applied AND applied_content_revision IS NULL)
    )
);
CREATE INDEX ix_generated_illustration_assets_conversion
    ON generated_illustration_assets (conversion_id, created_at, id);

ALTER TABLE credit_transactions ADD COLUMN illustration_generation_job_id uuid NULL;
ALTER TABLE credit_transactions DROP CONSTRAINT ck_credit_transactions_reason_valid;
ALTER TABLE credit_transactions ADD CONSTRAINT ck_credit_transactions_reason_valid CHECK (
    reason IN (
        'signup', 'plan_monthly', 'manual', 'refund', 'conversion', 'cycle_end',
        'action_guide', 'illustration_suggestion', 'illustration_generation'
    )
);
CREATE UNIQUE INDEX uq_credit_transactions_illustration_generation_reserve
    ON credit_transactions (illustration_generation_job_id)
    WHERE illustration_generation_job_id IS NOT NULL AND kind = 'reserve' AND reason = 'illustration_generation';
CREATE UNIQUE INDEX uq_credit_transactions_illustration_generation_terminal
    ON credit_transactions (illustration_generation_job_id)
    WHERE illustration_generation_job_id IS NOT NULL
      AND kind IN ('consume', 'release') AND reason = 'illustration_generation';

ALTER TABLE llm_calls ADD COLUMN illustration_generation_job_id uuid NULL;
CREATE UNIQUE INDEX uq_llm_calls_illustration_generation_job
    ON llm_calls (illustration_generation_job_id) WHERE illustration_generation_job_id IS NOT NULL;
ALTER TABLE llm_calls DROP CONSTRAINT ck_llm_calls_purpose_valid;
ALTER TABLE llm_calls ADD CONSTRAINT ck_llm_calls_purpose_valid CHECK (
    purpose IN ('convert', 'repair', 'reconvert', 'action_guide', 'illustration_suggestion', 'illustration_generation')
);

CREATE FUNCTION settle_illustration_generation_jobs_for_document(deleting_document_id uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM id FROM illustration_generation_jobs
    WHERE document_id = deleting_document_id AND status IN ('queued', 'running')
    FOR UPDATE;

    UPDATE workspace_credit_accounts AS account
    SET reserved = account.reserved - released.amount, updated_at = now()
    FROM (
        SELECT workspace_id, sum(reserved_credits) AS amount
        FROM illustration_generation_jobs
        WHERE document_id = deleting_document_id AND status IN ('queued', 'running')
          AND settlement = 'reserved' AND workspace_id IS NOT NULL
        GROUP BY workspace_id
    ) AS released
    WHERE account.workspace_id = released.workspace_id;

    INSERT INTO credit_transactions
        (id, workspace_id, owner_user_id, document_id, kind, balance_delta,
         reserved_delta, reason, note, illustration_generation_job_id)
    SELECT gen_random_uuid(), job.workspace_id, job.owner_user_id, job.document_id,
           'release', 0, -job.reserved_credits, 'illustration_generation', 'document_deleted', job.id
    FROM illustration_generation_jobs AS job
    WHERE job.document_id = deleting_document_id AND job.status IN ('queued', 'running')
      AND job.settlement = 'reserved' AND job.owner_user_id IS NOT NULL;

    UPDATE llm_calls AS call
    SET outcome = 'outcome_unknown', failure_class = NULL
    FROM illustration_generation_jobs AS job
    WHERE job.document_id = deleting_document_id AND job.status = 'running'
      AND job.provider_started_at IS NOT NULL AND call.illustration_generation_job_id = job.id
      AND call.outcome = 'in_progress';

    UPDATE illustration_generation_jobs
    SET status = 'superseded',
        settlement = CASE WHEN settlement = 'not_charged' THEN 'not_charged' ELSE 'released' END,
        failure_code = NULL, lease_owner = NULL, lease_until = NULL, worker_slot = NULL, updated_at = now()
    WHERE document_id = deleting_document_id AND status IN ('queued', 'running');

    UPDATE illustration_generation_jobs
    SET input_payload_encrypted = NULL, encryption_scheme = NULL, key_version = NULL, updated_at = now()
    WHERE document_id = deleting_document_id AND input_payload_encrypted IS NOT NULL;
END;
$$;

CREATE OR REPLACE FUNCTION settle_document_jobs_before_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM id FROM action_guide_jobs
    WHERE document_id = OLD.id AND status IN ('queued', 'running') FOR UPDATE;
    PERFORM id FROM illustration_generation_jobs
    WHERE document_id = OLD.id AND status IN ('queued', 'running') FOR UPDATE;
    PERFORM id FROM illustration_suggestion_jobs
    WHERE document_id = OLD.id AND status IN ('queued', 'running') FOR UPDATE;

    PERFORM settle_action_guide_jobs_for_document(OLD.id);
    PERFORM settle_illustration_generation_jobs_for_document(OLD.id);
    PERFORM settle_illustration_suggestion_jobs_for_document(OLD.id);
    RETURN OLD;
END;
$$;
