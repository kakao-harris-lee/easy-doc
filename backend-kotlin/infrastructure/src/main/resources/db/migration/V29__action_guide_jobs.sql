-- R2 행동 안내 생성 작업과 호출/크레딧 원장의 결합.
--
-- 실제 provider 호출은 후속 구현이 맡는다. 이 스키마는 호출 직전에 `provider_started_at`과
-- `llm_calls(outcome = 'in_progress')`를 같은 트랜잭션으로 남기고, 그 뒤 worker가 죽으면
-- 자동으로 다시 호출하지 않고 `outcome_unknown`으로 정리할 수 있는 경계를 만든다.

CREATE TABLE action_guide_jobs (
    id uuid NOT NULL,
    request_id uuid NOT NULL,
    owner_user_id uuid NULL,
    workspace_id uuid NULL,
    document_id uuid NOT NULL,
    conversion_id uuid NOT NULL,
    expected_content_revision bigint NOT NULL,
    expected_guide_revision bigint NULL,
    based_on_content_revision bigint NOT NULL,
    input_fingerprint character varying(64) NOT NULL,
    status character varying(16) NOT NULL DEFAULT 'queued',
    reserved_credits integer NOT NULL,
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
    CONSTRAINT pk_action_guide_jobs PRIMARY KEY (id),
    CONSTRAINT fk_action_guide_jobs_owner_user_id_users FOREIGN KEY (owner_user_id)
        REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_action_guide_jobs_workspace_id_workspaces FOREIGN KEY (workspace_id)
        REFERENCES workspaces (id) ON DELETE SET NULL,
    CONSTRAINT uq_action_guide_jobs_request UNIQUE (owner_user_id, conversion_id, request_id),
    CONSTRAINT ck_action_guide_jobs_status_valid
        CHECK (status IN ('queued', 'running', 'succeeded', 'failed', 'superseded')),
    CONSTRAINT ck_action_guide_jobs_settlement_valid
        CHECK (settlement IN ('reserved', 'consumed', 'released')),
    CONSTRAINT ck_action_guide_jobs_failure_code_valid
        CHECK (failure_code IS NULL OR failure_code IN ('generation_failed', 'result_invalid', 'outcome_unknown')),
    CONSTRAINT ck_action_guide_jobs_revisions
        CHECK (
            expected_content_revision BETWEEN 1 AND 9007199254740991
            AND based_on_content_revision BETWEEN 1 AND 9007199254740991
            AND (expected_guide_revision IS NULL OR expected_guide_revision BETWEEN 0 AND 9007199254740991)
        ),
    CONSTRAINT ck_action_guide_jobs_fingerprint_length CHECK (length(input_fingerprint) = 64),
    CONSTRAINT ck_action_guide_jobs_reserved_credits_positive CHECK (reserved_credits > 0),
    CONSTRAINT ck_action_guide_jobs_attempts_non_negative CHECK (attempts >= 0),
    CONSTRAINT ck_action_guide_jobs_provider_attempts_range CHECK (provider_attempts BETWEEN 0 AND 1),
    CONSTRAINT ck_action_guide_jobs_provider_started_consistent
        CHECK (
            (provider_started_at IS NULL) = (provider_attempts = 0)
            AND (provider_execution_id IS NULL) = (provider_attempts = 0)
        ),
    CONSTRAINT ck_action_guide_jobs_lease_paired
        CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CONSTRAINT ck_action_guide_jobs_running_lease
        CHECK ((status = 'running') = (lease_owner IS NOT NULL)),
    CONSTRAINT ck_action_guide_jobs_worker_slot
        CHECK (
            (status = 'running' AND worker_slot BETWEEN 1 AND 2)
            OR (status <> 'running' AND worker_slot IS NULL)
        ),
    CONSTRAINT ck_action_guide_jobs_terminal_settlement
        CHECK (
            (status IN ('queued', 'running') AND settlement = 'reserved')
            OR (status = 'succeeded' AND settlement = 'consumed')
            OR (status IN ('failed', 'superseded') AND settlement = 'released')
        )
);

-- 같은 요청 키는 입력이 같을 때 기존 작업을 돌려주고, 입력이 다르면 충돌로 판정한다.
-- 위 UNIQUE가 동시 요청도 한 행으로 직렬화한다.
CREATE INDEX ix_action_guide_jobs_request_lookup
    ON action_guide_jobs USING btree (owner_user_id, conversion_id, request_id);

-- 사용자 한 명과 문서 하나에는 동시에 한 활성 작업만 둔다. 두 제약은 요청 중복 외에도
-- 서로 다른 request_id로 들어온 경합을 DB에서 막는다.
CREATE UNIQUE INDEX uq_action_guide_jobs_active_document
    ON action_guide_jobs USING btree (document_id)
    WHERE status IN ('queued', 'running');

CREATE UNIQUE INDEX uq_action_guide_jobs_active_owner
    ON action_guide_jobs USING btree (owner_user_id)
    WHERE status IN ('queued', 'running');

CREATE INDEX ix_action_guide_jobs_queued
    ON action_guide_jobs USING btree (created_at, id)
    WHERE status = 'queued';

CREATE INDEX ix_action_guide_jobs_expired_lease
    ON action_guide_jobs USING btree (lease_until, id)
    WHERE status = 'running';

-- running 행만 slot을 가지며 1, 2가 각각 한 번만 나타날 수 있다. 모든 replica가 같은
-- unique index를 경합하므로 프로세스 수와 무관하게 provider 동시 실행은 최대 두 건이다.
CREATE UNIQUE INDEX uq_action_guide_jobs_running_slot
    ON action_guide_jobs USING btree (worker_slot)
    WHERE status = 'running';

-- 행동 안내 예약/정산은 일반 변환 거래와 같은 원장에 남기되 job id로 정확히 묶는다.
ALTER TABLE credit_transactions
    ADD COLUMN action_guide_job_id uuid NULL;

ALTER TABLE credit_transactions
    DROP CONSTRAINT ck_credit_transactions_reason_valid;

ALTER TABLE credit_transactions
    ADD CONSTRAINT ck_credit_transactions_reason_valid
        CHECK (reason IN ('signup', 'plan_monthly', 'manual', 'refund', 'conversion', 'cycle_end', 'action_guide'));

CREATE UNIQUE INDEX uq_credit_transactions_action_guide_reserve
    ON credit_transactions USING btree (action_guide_job_id)
    WHERE action_guide_job_id IS NOT NULL AND kind = 'reserve' AND reason = 'action_guide';

CREATE UNIQUE INDEX uq_credit_transactions_action_guide_terminal
    ON credit_transactions USING btree (action_guide_job_id)
    WHERE action_guide_job_id IS NOT NULL AND kind IN ('consume', 'release') AND reason = 'action_guide';

-- 호출 원장은 job당 한 행이다. 시작 전에 in_progress로 만들고, 완료 또는 회수 시 같은
-- 행을 갱신한다. job 삭제 뒤에도 청구 근거를 남기기 위해 job id에는 FK를 두지 않는다.
ALTER TABLE llm_calls
    ADD COLUMN action_guide_job_id uuid NULL;

ALTER TABLE llm_calls
    ALTER COLUMN provider DROP NOT NULL;

CREATE UNIQUE INDEX uq_llm_calls_action_guide_job
    ON llm_calls USING btree (action_guide_job_id)
    WHERE action_guide_job_id IS NOT NULL;

ALTER TABLE llm_calls
    DROP CONSTRAINT ck_llm_calls_purpose_valid;

ALTER TABLE llm_calls
    ADD CONSTRAINT ck_llm_calls_purpose_valid
        CHECK (purpose IN ('convert', 'repair', 'reconvert', 'action_guide'));

ALTER TABLE llm_calls
    DROP CONSTRAINT ck_llm_calls_outcome_valid;

ALTER TABLE llm_calls
    ADD CONSTRAINT ck_llm_calls_outcome_valid
        CHECK (outcome IN ('completed', 'provider_error', 'in_progress', 'outcome_unknown'));

ALTER TABLE llm_calls
    ADD CONSTRAINT ck_llm_calls_unfinished_zero_usage
        CHECK (
            outcome NOT IN ('in_progress', 'outcome_unknown')
            OR (
                input_tokens = 0
                AND output_tokens = 0
                AND estimated_cost_usd IS NULL
                AND provider IS NULL
                AND model IS NULL
            )
        );

ALTER TABLE llm_calls
    ADD CONSTRAINT ck_llm_calls_provider_by_outcome
        CHECK (
            (outcome IN ('in_progress', 'outcome_unknown') AND provider IS NULL)
            OR (outcome IN ('completed', 'provider_error') AND provider IS NOT NULL)
        );

-- 문서 삭제·보존 만료·회원 탈퇴의 CASCADE가 활성 작업을 지나갈 때 예약을 먼저 해제한다.
-- action_guide_jobs.document_id/conversion_id와 두 원장의 job id에는 일부러 FK를 두지 않아
-- 문서가 사라진 뒤에도 정산/호출 근거가 보존된다.
CREATE FUNCTION settle_action_guide_jobs_for_document(deleting_document_id uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    -- worker 정산과 같은 job 행을 먼저 잠근다. 잠금을 얻은 뒤에도 활성인 행만 후속
    -- 문장들이 건드리므로 성공/실패 정산과 삭제 정산이 이중 실행되지 않는다.
    PERFORM id
    FROM action_guide_jobs
    WHERE document_id = deleting_document_id
      AND status IN ('queued', 'running')
    FOR UPDATE;

    UPDATE workspace_credit_accounts AS account
    SET reserved = account.reserved - released.amount,
        updated_at = now()
    FROM (
        SELECT workspace_id, sum(reserved_credits)::integer AS amount
        FROM action_guide_jobs
        WHERE document_id = deleting_document_id
          AND status IN ('queued', 'running')
          AND settlement = 'reserved'
          AND workspace_id IS NOT NULL
        GROUP BY workspace_id
    ) AS released
    WHERE account.workspace_id = released.workspace_id;

    INSERT INTO credit_transactions
        (id, workspace_id, owner_user_id, document_id, kind, balance_delta,
         reserved_delta, reason, note, action_guide_job_id)
    SELECT gen_random_uuid(), job.workspace_id, job.owner_user_id, job.document_id,
           'release', 0, -job.reserved_credits, 'action_guide',
           'document_deleted', job.id
    FROM action_guide_jobs AS job
    WHERE job.document_id = deleting_document_id
      AND job.status IN ('queued', 'running')
      AND job.settlement = 'reserved'
      AND job.owner_user_id IS NOT NULL;

    UPDATE llm_calls AS call
    SET outcome = 'outcome_unknown',
        failure_class = NULL
    FROM action_guide_jobs AS job
    WHERE job.document_id = deleting_document_id
      AND job.status = 'running'
      AND job.provider_started_at IS NOT NULL
      AND call.action_guide_job_id = job.id
      AND call.outcome = 'in_progress';

    UPDATE action_guide_jobs
    SET status = 'superseded',
        settlement = 'released',
        failure_code = NULL,
        lease_owner = NULL,
        lease_until = NULL,
        worker_slot = NULL,
        updated_at = now()
    WHERE document_id = deleting_document_id
      AND status IN ('queued', 'running')
      AND settlement = 'reserved';
END;
$$;

CREATE FUNCTION settle_action_guide_jobs_before_document_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM settle_action_guide_jobs_for_document(OLD.id);
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_documents_settle_action_guide_jobs
BEFORE DELETE ON documents
FOR EACH ROW
EXECUTE FUNCTION settle_action_guide_jobs_before_document_delete();
