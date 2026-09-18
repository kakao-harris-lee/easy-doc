-- 쉬운글 본문 버전과 R1 검수 지원 스냅샷.
-- 기존 완료 행은 첫 완료 본문으로 간주해 1, 그 밖의 행은 0으로 안전하게 초기화한다.
ALTER TABLE conversions
    ADD COLUMN content_revision bigint DEFAULT 0 NOT NULL;

UPDATE conversions SET content_revision = 1 WHERE status = 'done';

ALTER TABLE conversions
    ADD CONSTRAINT ck_conversions_content_revision_range
        CHECK (content_revision BETWEEN 0 AND 9007199254740991);

CREATE TABLE review_assessments (
    id uuid NOT NULL,
    conversion_id uuid NOT NULL,
    content_revision bigint NOT NULL,
    analyzer_version character varying(64) NOT NULL,
    review_revision bigint DEFAULT 0 NOT NULL,
    payload_encrypted bytea NOT NULL,
    encryption_scheme character varying(16) NOT NULL,
    key_version smallint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_review_assessments PRIMARY KEY (id),
    CONSTRAINT fk_review_assessments_conversion_id_conversions FOREIGN KEY (conversion_id)
        REFERENCES conversions (id) ON DELETE CASCADE,
    CONSTRAINT uq_review_assessments_snapshot
        UNIQUE (conversion_id, content_revision, analyzer_version),
    CONSTRAINT ck_review_assessments_content_revision_range
        CHECK (content_revision BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_review_assessments_review_revision_range
        CHECK (review_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_review_assessments_encryption_scheme_valid
        CHECK (encryption_scheme IN ('aes256gcm-v1')),
    CONSTRAINT ck_review_assessments_key_version_positive CHECK (key_version > 0)
);

CREATE INDEX ix_review_assessments_conversion_created
    ON review_assessments USING btree (conversion_id, created_at DESC);
