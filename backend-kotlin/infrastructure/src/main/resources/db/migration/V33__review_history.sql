-- R5: append-only review history. Snapshot rows are independently encrypted and can be
-- detached by retention pruning without deleting the event ledger.

CREATE TABLE review_snapshots (
    id uuid NOT NULL,
    conversion_id uuid NOT NULL,
    content_revision bigint NOT NULL,
    artifact_revision bigint NULL,
    kind character varying(32) NULL,
    payload_encrypted bytea NOT NULL,
    encryption_scheme character varying(16) NOT NULL,
    key_version smallint NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT pk_review_snapshots PRIMARY KEY (id),
    CONSTRAINT fk_review_snapshots_conversion FOREIGN KEY (conversion_id)
        REFERENCES conversions (id) ON DELETE CASCADE,
    CONSTRAINT ck_review_snapshots_revision
        CHECK (content_revision BETWEEN 1 AND 9007199254740991
            AND (artifact_revision IS NULL OR artifact_revision BETWEEN 0 AND 9007199254740991)),
    CONSTRAINT ck_review_snapshots_kind
        CHECK (kind IS NULL OR kind IN ('review_assessment', 'action_guide')),
    -- 65,600 = 평문 상한 65,536바이트(64KiB, ReviewHistorySnapshotCodec.MAX_PLAINTEXT_BYTES) + AES-GCM
    -- 오버헤드 28바이트(12바이트 nonce + 16바이트 tag) + 여유 36바이트. 실제로는 앱 계층의
    -- MAX_PLAINTEXT_BYTES 검사가 이보다 먼저 초과 입력을 거절하므로, 이 CHECK는 그 검사를
    -- 우회한 값이 DB까지 도달했을 때의 최후 방어선이다.
    CONSTRAINT ck_review_snapshots_ciphertext_size
        CHECK (octet_length(payload_encrypted) <= 65600),
    CONSTRAINT ck_review_snapshots_scheme CHECK (encryption_scheme = 'aes256gcm-v1'),
    CONSTRAINT ck_review_snapshots_key_version CHECK (key_version > 0)
);

CREATE INDEX ix_review_snapshots_conversion_revision
    ON review_snapshots (conversion_id, content_revision DESC, created_at DESC, id DESC);

CREATE TABLE review_events (
    id uuid NOT NULL,
    conversion_id uuid NOT NULL,
    event_type character varying(32) NOT NULL,
    actor_user_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    content_revision bigint NOT NULL,
    artifact_revision bigint NULL,
    item_id uuid NULL,
    assessment_id uuid NULL,
    guide_id uuid NULL,
    snapshot_id uuid NULL,
    CONSTRAINT pk_review_events PRIMARY KEY (id),
    CONSTRAINT fk_review_events_conversion FOREIGN KEY (conversion_id)
        REFERENCES conversions (id) ON DELETE CASCADE,
    CONSTRAINT fk_review_events_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES review_snapshots (id) ON DELETE SET NULL,
    -- Actor is intentionally not a restrictive user FK. Document/account deletion cascades the
    -- conversion and history, while an orphaned ledger row must never block account deletion.
    -- item_id/assessment_id/guide_id도 같은 이유로 FK가 없다: 이 원장은 append-only라, 참조
    -- 대상인 conversion이 지워지면 이 표 자체가 fk_review_events_conversion으로 CASCADE 삭제되고
    -- (snapshot_id만 review_snapshots 쪽에서 ON DELETE SET NULL로 개별 풀린다), 현재 이 id들만
    -- 독립적으로 지우는 DELETE 경로가 없어 고아 참조가 생기지도 않는다. 그럼에도 FK를 걸지 않는
    -- 것은 이력의 불변성을 우선해, 앞으로 그런 독립 삭제 경로가 생기더라도 과거 이벤트 행이
    -- 참조 무결성 때문에 지워지거나 삭제가 막히는 일이 없도록 참조 무결성을 일부러 느슨히 둔다.
    CONSTRAINT ck_review_events_type CHECK
        (event_type IN ('item_confirmed', 'item_reopened', 'item_not_applicable',
                        'guide_reviewed', 'invalidated_by_edit')),
    CONSTRAINT ck_review_events_revision
        CHECK (content_revision BETWEEN 1 AND 9007199254740991
            AND (artifact_revision IS NULL OR artifact_revision BETWEEN 0 AND 9007199254740991))
);

CREATE INDEX ix_review_events_conversion_created
    ON review_events (conversion_id, created_at DESC, id DESC);
