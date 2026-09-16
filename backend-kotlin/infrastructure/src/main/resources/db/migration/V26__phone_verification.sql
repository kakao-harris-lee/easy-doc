-- 휴대폰 인증 및 번호당 무료 체험 1회.
-- 평문 전화번호는 저장하지 않고, 회전하지 않는 pepper를 사용한 HMAC-SHA256 지문만 남긴다.
ALTER TABLE users
    ADD COLUMN phone_verified_at timestamptz NULL,
    ADD COLUMN pending_phone_fingerprint char(64) NULL;

CREATE TABLE phone_verification_codes (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    code_hash text NOT NULL,
    salt text NOT NULL,
    expires_at timestamptz NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    consumed_at timestamptz NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_phone_verification_codes PRIMARY KEY (id),
    CONSTRAINT fk_phone_verification_codes_user_id_users FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX ix_phone_verification_codes_user_id_created_at
    ON phone_verification_codes USING btree (user_id, created_at DESC);

-- 사용자 FK를 일부러 두지 않는다. 탈퇴 후 같은 번호로 재가입해도 체험을 중복 지급하지 않는다.
CREATE TABLE phone_trial_grant_records (
    phone_fingerprint char(64) NOT NULL,
    granted_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_phone_trial_grant_records PRIMARY KEY (phone_fingerprint)
);
