-- ER-06: 결과 본문은 각각 고유한 행 ID + 필드 AAD로 봉인한다. 작업/사용량 원장은
-- 문서 삭제 후에도 과금 근거로 남길 수 있지만, 후보와 안내문은 문서와 함께 파기한다.

ALTER TABLE action_guide_jobs
    ADD CONSTRAINT uq_action_guide_jobs_id_conversion UNIQUE (id, conversion_id);

CREATE TABLE action_guide_candidates (
    id uuid NOT NULL,
    job_id uuid NOT NULL,
    conversion_id uuid NOT NULL,
    based_on_content_revision bigint NOT NULL,
    payload_encrypted bytea NOT NULL,
    encryption_scheme character varying(16) NOT NULL,
    key_version smallint NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT pk_action_guide_candidates PRIMARY KEY (id),
    CONSTRAINT uq_action_guide_candidates_job UNIQUE (job_id),
    CONSTRAINT fk_action_guide_candidates_job FOREIGN KEY (job_id, conversion_id)
        REFERENCES action_guide_jobs (id, conversion_id) ON DELETE CASCADE,
    CONSTRAINT fk_action_guide_candidates_conversion FOREIGN KEY (conversion_id)
        REFERENCES conversions (id) ON DELETE CASCADE,
    CONSTRAINT ck_action_guide_candidates_revision
        CHECK (based_on_content_revision BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_action_guide_candidates_scheme CHECK (encryption_scheme = 'aes256gcm-v1'),
    CONSTRAINT ck_action_guide_candidates_key_version CHECK (key_version > 0)
);

CREATE INDEX ix_action_guide_candidates_conversion ON action_guide_candidates (conversion_id);

CREATE TABLE action_guides (
    id uuid NOT NULL,
    conversion_id uuid NOT NULL,
    based_on_content_revision bigint NOT NULL,
    guide_revision bigint NOT NULL,
    status character varying(16) NOT NULL,
    payload_encrypted bytea NOT NULL,
    encryption_scheme character varying(16) NOT NULL,
    key_version smallint NOT NULL,
    reviewed_at timestamp with time zone NULL,
    reviewed_by uuid NULL,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT pk_action_guides PRIMARY KEY (id),
    CONSTRAINT uq_action_guides_conversion UNIQUE (conversion_id),
    CONSTRAINT fk_action_guides_conversion FOREIGN KEY (conversion_id)
        REFERENCES conversions (id) ON DELETE CASCADE,
    CONSTRAINT ck_action_guides_revision
        CHECK (based_on_content_revision BETWEEN 1 AND 9007199254740991
            AND guide_revision BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_action_guides_status CHECK (status IN ('draft', 'reviewed', 'stale')),
    CONSTRAINT ck_action_guides_review_consistent
        CHECK ((status = 'reviewed') = (reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL)),
    CONSTRAINT ck_action_guides_scheme CHECK (encryption_scheme = 'aes256gcm-v1'),
    CONSTRAINT ck_action_guides_key_version CHECK (key_version > 0)
);

-- 현재 본문이 바뀌면 기존 안내문은 보존하되 사용/내보내기를 막는다. 변환 저장과
-- 같은 트랜잭션에서 상태와 CAS 버전을 바꿔, 이전 화면의 늦은 편집 저장을 거절한다.
CREATE FUNCTION invalidate_action_guide_on_content_change()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.content_revision IS DISTINCT FROM OLD.content_revision THEN
        UPDATE action_guides
        SET status = 'stale', reviewed_at = NULL, reviewed_by = NULL,
            guide_revision = guide_revision + 1, updated_at = now()
        WHERE conversion_id = NEW.id AND status <> 'stale';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_conversions_invalidate_action_guide
AFTER UPDATE OF content_revision ON conversions
FOR EACH ROW EXECUTE FUNCTION invalidate_action_guide_on_content_change();
