-- ER-16: 변환 한 건의 그림 배치 — 현재 집합만 저장한다(이력 없음). `conversion_id`가
-- UNIQUE라 변환당 행이 최대 하나다.
CREATE TABLE illustration_placements (
    id uuid NOT NULL,
    conversion_id uuid NOT NULL,
    content_revision bigint NOT NULL,
    payload_encrypted bytea NOT NULL,
    encryption_scheme character varying(16) NOT NULL,
    key_version smallint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_illustration_placements PRIMARY KEY (id),
    CONSTRAINT uq_illustration_placements_conversion_id UNIQUE (conversion_id),
    CONSTRAINT fk_illustration_placements_conversion_id_conversions FOREIGN KEY (conversion_id)
        REFERENCES conversions (id) ON DELETE CASCADE,
    CONSTRAINT ck_illustration_placements_content_revision
        CHECK (content_revision BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_illustration_placements_encryption_scheme_valid
        CHECK (encryption_scheme IN ('aes256gcm-v1')),
    CONSTRAINT ck_illustration_placements_key_version_positive CHECK (key_version > 0),
    -- 4,160 = 평문 상한 4,096바이트(IllustrationPlacementCodec.MAX_PLAINTEXT_BYTES) + AES-GCM
    -- 오버헤드 28바이트(12바이트 nonce + 16바이트 tag) + 여유 36바이트(V33의 같은 계산 관례).
    CONSTRAINT ck_illustration_placements_payload_size CHECK (octet_length(payload_encrypted) <= 4160)
);
