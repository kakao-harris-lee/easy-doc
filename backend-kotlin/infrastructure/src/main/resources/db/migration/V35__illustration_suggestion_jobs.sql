-- R7 ER-17 문맥 기반 그림 제안 작업과 결과, 그리고 호출/크레딧 원장과의 결합.
--
-- 구조는 V29 행동 안내 작업을 그대로 가져온다(명세 §2 「R2 작업 패턴 재사용」) — 요청 키
-- UNIQUE, 문서당·계정당 활성 1건 부분 UNIQUE, lease/fence = attempts, worker_slot 1..2,
-- provider 시작 일관성, status ↔ settlement CHECK. R2 스키마를 제네릭으로 바꾸지 않고 같은
-- 불변식을 새 표에 다시 세운다.
--
-- V29 와 다른 점은 둘이다.
-- ⑴ `expected_guide_revision` 이 없다 — 제안 분석에는 담당자 편집본이 없다.
-- ⑵ 예약량이 0 일 수 있다. 이용량 단가(`easydoc.illustration-suggestions.credits-per-100-chars`)
--    가 0 인 fake 모드에서는 크레딧 거래 행을 만들지 않으므로(명세 §3) 그 작업의 정산 상태는
--    `not_charged` 로 고정된다. 「예약했는데 정산이 안 됐다」와 「애초에 예약이 없다」를 같은
--    값으로 뭉개면 원장 대조가 두 경우를 구분하지 못한다.

CREATE TABLE illustration_suggestion_jobs (
    id uuid NOT NULL,
    request_id uuid NOT NULL,
    owner_user_id uuid NULL,
    workspace_id uuid NULL,
    document_id uuid NOT NULL,
    conversion_id uuid NOT NULL,
    expected_content_revision bigint NOT NULL,
    based_on_content_revision bigint NOT NULL,
    input_fingerprint character varying(64) NOT NULL,
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
    CONSTRAINT pk_illustration_suggestion_jobs PRIMARY KEY (id),
    CONSTRAINT uq_illustration_suggestion_jobs_id_conversion UNIQUE (id, conversion_id),
    CONSTRAINT fk_illustration_suggestion_jobs_owner_user_id_users FOREIGN KEY (owner_user_id)
        REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_illustration_suggestion_jobs_workspace_id_workspaces FOREIGN KEY (workspace_id)
        REFERENCES workspaces (id) ON DELETE SET NULL,
    CONSTRAINT uq_illustration_suggestion_jobs_request UNIQUE (owner_user_id, conversion_id, request_id),
    CONSTRAINT ck_illustration_suggestion_jobs_status_valid
        CHECK (status IN ('queued', 'running', 'succeeded', 'failed', 'superseded')),
    CONSTRAINT ck_illustration_suggestion_jobs_settlement_valid
        CHECK (settlement IN ('reserved', 'consumed', 'released', 'not_charged')),
    CONSTRAINT ck_illustration_suggestion_jobs_failure_code_valid
        CHECK (failure_code IS NULL OR failure_code IN ('generation_failed', 'result_invalid', 'outcome_unknown')),
    CONSTRAINT ck_illustration_suggestion_jobs_revisions
        CHECK (
            expected_content_revision BETWEEN 1 AND 9007199254740991
            AND based_on_content_revision BETWEEN 1 AND 9007199254740991
        ),
    CONSTRAINT ck_illustration_suggestion_jobs_fingerprint_length CHECK (length(input_fingerprint) = 64),
    CONSTRAINT ck_illustration_suggestion_jobs_reserved_credits_non_negative CHECK (reserved_credits >= 0),
    CONSTRAINT ck_illustration_suggestion_jobs_reserved_credits_tenth
        CHECK (reserved_credits = trunc(reserved_credits, 1)),
    CONSTRAINT ck_illustration_suggestion_jobs_attempts_non_negative CHECK (attempts >= 0),
    CONSTRAINT ck_illustration_suggestion_jobs_provider_attempts_range CHECK (provider_attempts BETWEEN 0 AND 1),
    CONSTRAINT ck_illustration_suggestion_jobs_provider_started_consistent
        CHECK (
            (provider_started_at IS NULL) = (provider_attempts = 0)
            AND (provider_execution_id IS NULL) = (provider_attempts = 0)
        ),
    CONSTRAINT ck_illustration_suggestion_jobs_lease_paired
        CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CONSTRAINT ck_illustration_suggestion_jobs_running_lease
        CHECK ((status = 'running') = (lease_owner IS NOT NULL)),
    CONSTRAINT ck_illustration_suggestion_jobs_worker_slot
        CHECK (
            (status = 'running' AND worker_slot BETWEEN 1 AND 2)
            OR (status <> 'running' AND worker_slot IS NULL)
        ),
    -- 예약이 0이면 정산할 것이 없다. 예약이 있는 작업만 V29와 같은 상태↔정산 짝을 진다.
    CONSTRAINT ck_illustration_suggestion_jobs_terminal_settlement
        CHECK (
            (reserved_credits = 0 AND settlement = 'not_charged')
            OR (
                reserved_credits > 0
                AND (
                    (status IN ('queued', 'running') AND settlement = 'reserved')
                    OR (status = 'succeeded' AND settlement = 'consumed')
                    OR (status IN ('failed', 'superseded') AND settlement = 'released')
                )
            )
        )
);

CREATE INDEX ix_illustration_suggestion_jobs_request_lookup
    ON illustration_suggestion_jobs USING btree (owner_user_id, conversion_id, request_id);

CREATE UNIQUE INDEX uq_illustration_suggestion_jobs_active_document
    ON illustration_suggestion_jobs USING btree (document_id)
    WHERE status IN ('queued', 'running');

CREATE UNIQUE INDEX uq_illustration_suggestion_jobs_active_owner
    ON illustration_suggestion_jobs USING btree (owner_user_id)
    WHERE status IN ('queued', 'running');

CREATE INDEX ix_illustration_suggestion_jobs_queued
    ON illustration_suggestion_jobs USING btree (created_at, id)
    WHERE status = 'queued';

CREATE INDEX ix_illustration_suggestion_jobs_expired_lease
    ON illustration_suggestion_jobs USING btree (lease_until, id)
    WHERE status = 'running';

-- running 행만 slot을 가지며 1, 2가 각각 한 번만 나타난다. R2 와 별개의 표이므로 두 기능의
-- 동시 호출은 각각 최대 두 건이다.
CREATE UNIQUE INDEX uq_illustration_suggestion_jobs_running_slot
    ON illustration_suggestion_jobs USING btree (worker_slot)
    WHERE status = 'running';

-- 결과는 작업당 1행이고 문서와 함께 파기된다(V30 후보와 같은 판단) — 작업 행은 청구 근거로
-- 남지만 제안 본문은 남지 않는다.
CREATE TABLE illustration_suggestion_results (
    id uuid NOT NULL,
    job_id uuid NOT NULL,
    conversion_id uuid NOT NULL,
    based_on_content_revision bigint NOT NULL,
    payload_encrypted bytea NOT NULL,
    encryption_scheme character varying(16) NOT NULL,
    key_version smallint NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT pk_illustration_suggestion_results PRIMARY KEY (id),
    CONSTRAINT uq_illustration_suggestion_results_job UNIQUE (job_id),
    CONSTRAINT fk_illustration_suggestion_results_job FOREIGN KEY (job_id, conversion_id)
        REFERENCES illustration_suggestion_jobs (id, conversion_id) ON DELETE CASCADE,
    CONSTRAINT fk_illustration_suggestion_results_conversion FOREIGN KEY (conversion_id)
        REFERENCES conversions (id) ON DELETE CASCADE,
    CONSTRAINT ck_illustration_suggestion_results_revision
        CHECK (based_on_content_revision BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_illustration_suggestion_results_scheme CHECK (encryption_scheme = 'aes256gcm-v1'),
    CONSTRAINT ck_illustration_suggestion_results_key_version CHECK (key_version > 0)
);

CREATE INDEX ix_illustration_suggestion_results_conversion
    ON illustration_suggestion_results (conversion_id);

-- 제안 분석의 예약/정산도 일반 변환 거래와 같은 원장에 남기되 job id 로 정확히 묶는다.
ALTER TABLE credit_transactions
    ADD COLUMN illustration_suggestion_job_id uuid NULL;

ALTER TABLE credit_transactions
    DROP CONSTRAINT ck_credit_transactions_reason_valid;

ALTER TABLE credit_transactions
    ADD CONSTRAINT ck_credit_transactions_reason_valid
        CHECK (
            reason IN (
                'signup', 'plan_monthly', 'manual', 'refund', 'conversion', 'cycle_end',
                'action_guide', 'illustration_suggestion'
            )
        );

CREATE UNIQUE INDEX uq_credit_transactions_illustration_suggestion_reserve
    ON credit_transactions USING btree (illustration_suggestion_job_id)
    WHERE illustration_suggestion_job_id IS NOT NULL
      AND kind = 'reserve' AND reason = 'illustration_suggestion';

CREATE UNIQUE INDEX uq_credit_transactions_illustration_suggestion_terminal
    ON credit_transactions USING btree (illustration_suggestion_job_id)
    WHERE illustration_suggestion_job_id IS NOT NULL
      AND kind IN ('consume', 'release') AND reason = 'illustration_suggestion';

-- 호출 원장은 job당 한 행이다. V29와 같은 이유로 job id 에는 FK를 두지 않는다.
ALTER TABLE llm_calls
    ADD COLUMN illustration_suggestion_job_id uuid NULL;

CREATE UNIQUE INDEX uq_llm_calls_illustration_suggestion_job
    ON llm_calls USING btree (illustration_suggestion_job_id)
    WHERE illustration_suggestion_job_id IS NOT NULL;

-- `illustration_suggestion` 은 23자다. V14 의 varchar(16) 을 그대로 두면 새 목적이 CHECK 를
-- 통과하고도 길이에서 거절된다 — 두 제약이 같은 어휘를 말하도록 폭을 함께 넓힌다.
ALTER TABLE llm_calls
    ALTER COLUMN purpose TYPE character varying(32);

ALTER TABLE llm_calls
    DROP CONSTRAINT ck_llm_calls_purpose_valid;

ALTER TABLE llm_calls
    ADD CONSTRAINT ck_llm_calls_purpose_valid
        CHECK (purpose IN ('convert', 'repair', 'reconvert', 'action_guide', 'illustration_suggestion'));

-- 문서 삭제·보존 만료·회원 탈퇴의 CASCADE 가 활성 작업을 지나갈 때 예약을 먼저 해제한다
-- (V29 의 같은 이름 함수와 같은 구조). 예약이 0인 작업은 계정도 원장도 건드리지 않고 상태만
-- 종결시킨다 — `not_charged` 는 삭제 뒤에도 `not_charged` 다.
CREATE FUNCTION settle_illustration_suggestion_jobs_for_document(deleting_document_id uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM id
    FROM illustration_suggestion_jobs
    WHERE document_id = deleting_document_id
      AND status IN ('queued', 'running')
    FOR UPDATE;

    UPDATE workspace_credit_accounts AS account
    SET reserved = account.reserved - released.amount,
        updated_at = now()
    FROM (
        SELECT workspace_id, sum(reserved_credits) AS amount
        FROM illustration_suggestion_jobs
        WHERE document_id = deleting_document_id
          AND status IN ('queued', 'running')
          AND settlement = 'reserved'
          AND workspace_id IS NOT NULL
        GROUP BY workspace_id
    ) AS released
    WHERE account.workspace_id = released.workspace_id;

    INSERT INTO credit_transactions
        (id, workspace_id, owner_user_id, document_id, kind, balance_delta,
         reserved_delta, reason, note, illustration_suggestion_job_id)
    SELECT gen_random_uuid(), job.workspace_id, job.owner_user_id, job.document_id,
           'release', 0, -job.reserved_credits, 'illustration_suggestion',
           'document_deleted', job.id
    FROM illustration_suggestion_jobs AS job
    WHERE job.document_id = deleting_document_id
      AND job.status IN ('queued', 'running')
      AND job.settlement = 'reserved'
      AND job.owner_user_id IS NOT NULL;

    UPDATE llm_calls AS call
    SET outcome = 'outcome_unknown',
        failure_class = NULL
    FROM illustration_suggestion_jobs AS job
    WHERE job.document_id = deleting_document_id
      AND job.status = 'running'
      AND job.provider_started_at IS NOT NULL
      AND call.illustration_suggestion_job_id = job.id
      AND call.outcome = 'in_progress';

    UPDATE illustration_suggestion_jobs
    SET status = 'superseded',
        settlement = CASE WHEN settlement = 'not_charged' THEN 'not_charged' ELSE 'released' END,
        failure_code = NULL,
        lease_owner = NULL,
        lease_until = NULL,
        worker_slot = NULL,
        updated_at = now()
    WHERE document_id = deleting_document_id
      AND status IN ('queued', 'running');
END;
$$;

CREATE FUNCTION settle_illustration_suggestion_jobs_before_document_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM settle_illustration_suggestion_jobs_for_document(OLD.id);
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_documents_settle_illustration_suggestion_jobs
BEFORE DELETE ON documents
FOR EACH ROW
EXECUTE FUNCTION settle_illustration_suggestion_jobs_before_document_delete();
