-- 어드민 최소 — 관리자 플래그, 감사 흔적 2종, 공지 표 (계획
-- docs/plans/2026-09-07-admin-minimum.md §2 결정 1·3·5).
--
-- **관리자는 DB 플래그다.** 설정 파일·환경변수로 관리자를 정하지 않는다 — 부여는
-- `admin-grant --email=<이메일> [--revoke]` 운영 프로필뿐이다(대상 계정의 이메일이
-- **검증된** 상태여야 부여). 판정은 요청마다 이 열을 다시 읽는다(토큰에 넣지 않는다) —
-- 회수가 다음 요청부터 즉시 반영돼야 한다(`AdminGuard`).
ALTER TABLE users
    ADD COLUMN is_admin boolean NOT NULL DEFAULT false;

-- **감사 흔적.** 관리자가 화면에서 하는 변경은 누가 했는지 남긴다 — 관리자 화면·프로필
-- 경유 부여는 채우고, 자동 경로(예약·소비·해제, 가입 보너스)는 NULL로 둔다.
-- ondelete=SET NULL: 관리자 계정이 지워져도 거래·요청 이력(청구 근거)은 남는다 —
-- `credit_transactions.workspace_id`·`invoice_requests.workspace_id`와 같은 판단(V15·V16
-- 머리주석).
ALTER TABLE credit_transactions
    ADD COLUMN actor_user_id uuid NULL;

ALTER TABLE credit_transactions
    ADD CONSTRAINT fk_credit_transactions_actor_user_id_users FOREIGN KEY (actor_user_id)
        REFERENCES users (id) ON DELETE SET NULL;

ALTER TABLE invoice_requests
    ADD COLUMN handled_by uuid NULL;

ALTER TABLE invoice_requests
    ADD CONSTRAINT fk_invoice_requests_handled_by_users FOREIGN KEY (handled_by)
        REFERENCES users (id) ON DELETE SET NULL;

-- --- announcements -----------------------------------------------------------
-- 관리자가 쓰는 공지 — 사용자 화면 상단 배너(`GET /announcements/active`, 인증 사용자,
-- 활성 최대 5건 최신순). 결제·플랜 자동화·세밀한 권한 체계와 무관한 단순 표라 워크스페이스에
-- 속하지 않는다(전역 공지).
CREATE TABLE announcements (
    id uuid NOT NULL,
    body character varying(500) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    created_by uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_announcements PRIMARY KEY (id),
    -- ondelete=CASCADE: 작성한 관리자 계정이 지워지면 공지도 함께 사라진다 — 공지는
    -- 청구 근거가 아니라 운영 중 배너 문구일 뿐이라 `credit_transactions`·
    -- `invoice_requests`와 달리 보존할 이유가 없다.
    CONSTRAINT fk_announcements_created_by_users FOREIGN KEY (created_by)
        REFERENCES users (id) ON DELETE CASCADE
);

-- `GET /announcements/active`(활성만, 최신순 최대 5건)가 이 열 조합으로 좁힌다.
CREATE INDEX ix_announcements_active_created_at
    ON announcements USING btree (active, created_at);
