-- 법정 거래·공급·동의 기록은 계정/워크스페이스 CASCADE와 분리된 암호화 원장에 둔다.
-- 원문, 문서 id, 카드/빌링 키와 provider payload는 이 표에 넣지 않는다.
CREATE TABLE retained_transaction_records (
    id uuid PRIMARY KEY,
    record_type varchar(16) NOT NULL CHECK (record_type IN ('payment','refund','supply','consent','complaint')),
    source_id uuid NOT NULL,
    payment_id uuid,
    workspace_id uuid,
    environment varchar(16) CHECK (environment IN ('toss_test','toss_live') OR environment IS NULL),
    occurred_at timestamptz,
    retained_until timestamptz NOT NULL,
    manual_review boolean NOT NULL DEFAULT false,
    payload_encrypted bytea NOT NULL,
    encryption_scheme varchar(32) NOT NULL CHECK (encryption_scheme='aes256gcm-v1'),
    key_version smallint NOT NULL CHECK (key_version >= 1),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT retained_transaction_source_unique UNIQUE (record_type, source_id)
);
CREATE INDEX retained_transaction_expiry ON retained_transaction_records(retained_until, id);
CREATE INDEX retained_transaction_payment ON retained_transaction_records(payment_id, record_type);
CREATE INDEX retained_transaction_occurred ON retained_transaction_records(occurred_at DESC, id DESC);

-- 관리자 심사 결과는 원장 payload와 별도의 최소 메타데이터다. 작업자 계정이
-- 탈퇴해도 심사 이력은 남겨야 하므로 actor FK는 SET NULL이다.
CREATE TABLE retained_transaction_decisions (
    operation_id uuid PRIMARY KEY,
    -- Deliberately no FK: a decision has its own legal retention period and must outlive
    -- the source record when the two periods differ.
    record_id uuid NOT NULL,
    decision_type varchar(16) NOT NULL CHECK (decision_type IN ('refund','complaint')),
    actor_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    status varchar(16) NOT NULL CHECK (status IN ('approved','rejected','resolved')),
    amount integer CHECK (amount IS NULL OR amount >= 0),
    recovery_credits numeric CHECK (recovery_credits IS NULL OR recovery_credits >= 0),
    stop_renewal boolean,
    received_at timestamptz NOT NULL,
    retained_until timestamptz NOT NULL,
    payload_encrypted bytea NOT NULL,
    encryption_scheme varchar(32) NOT NULL CHECK (encryption_scheme='aes256gcm-v1'),
    key_version smallint NOT NULL CHECK (key_version >= 1),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX retained_transaction_decisions_record ON retained_transaction_decisions(record_id, created_at, operation_id);
CREATE INDEX retained_transaction_decisions_expiry ON retained_transaction_decisions(retained_until, operation_id);

-- Survives account restore from a backup taken before deletion. There is intentionally no FK
-- because the marker is the evidence that the account row was removed.
CREATE TABLE account_deletion_tombstones (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    deleted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL DEFAULT clock_timestamp() + interval '8 days'
);
CREATE INDEX account_deletion_tombstones_expiry ON account_deletion_tombstones(expires_at);
CREATE INDEX account_deletion_tombstones_user ON account_deletion_tombstones(user_id);

