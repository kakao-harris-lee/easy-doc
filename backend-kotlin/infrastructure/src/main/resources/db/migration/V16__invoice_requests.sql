-- 세금계산서 요청 기록 (계획 docs/plans/2026-09-07-invoice-requests.md §2 결정 1).
--
-- 로그인한 워크스페이스 소유자가 사업자등록번호로 세금계산서 발급을 요청하면 기록을
-- 남기고, 운영자가 메일로 알림을 받아 홈택스에서 수동 발급한 뒤 상태를 바꾼다
-- (`invoice-handle` 운영 프로필). 발급 자체(전자세금계산서 API 연동)는 범위 밖이다.
--
-- **암호화 대상이 아니다.** 사업자등록번호·상호·대표자·주소·연락 이메일은 사업자 정보이지
-- 문서 본문·개인정보가 아니다 — `credit_transactions`(V15)·`llm_calls`(V14)와 같은 판단
-- 이다. 그래서 이 표는 `kr.easydoc.core.crypto.EncryptedField`가 아는 봉인 대상 목록에
-- 들지 않고, `OwnershipPredicateGuardTest`(그 목록만 훑는다)의 인구조사 대상도 아니다 —
-- 다만 애플리케이션 `toString()`에는 마스킹한다(인구조사 규약, `SensitiveToStringReachTest`).

CREATE TABLE invoice_requests (
    id uuid NOT NULL,
    -- 워크스페이스가 사라져도 요청 이력(청구 근거)은 남는다 — credit_transactions.workspace_id
    -- 와 같은 판단(V15 머리주석).
    workspace_id uuid NULL,
    owner_user_id uuid NOT NULL,
    business_number char(10) NOT NULL,
    company_name varchar(100) NOT NULL,
    representative_name varchar(50) NULL,
    contact_email varchar(255) NOT NULL,
    address varchar(200) NULL,
    period_from date NOT NULL,
    period_to date NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'requested',
    operator_note varchar(500) NULL,
    requested_at timestamp with time zone DEFAULT now() NOT NULL,
    handled_at timestamp with time zone NULL,
    CONSTRAINT pk_invoice_requests PRIMARY KEY (id),
    CONSTRAINT fk_invoice_requests_workspace_id_workspaces FOREIGN KEY (workspace_id)
        REFERENCES workspaces (id) ON DELETE SET NULL,
    CONSTRAINT fk_invoice_requests_owner_user_id_users FOREIGN KEY (owner_user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_invoice_requests_business_number_digits CHECK (business_number ~ '^[0-9]{10}$'),
    -- 값 목록을 애플리케이션 상수에서 가져오지 않고 SQL로 직접 적는다(다른 CHECK 제약과
    -- 같은 이유, V1 머리주석).
    CONSTRAINT ck_invoice_requests_status_valid
        CHECK (status IN ('requested', 'issued', 'rejected')),
    CONSTRAINT ck_invoice_requests_period_valid CHECK (period_to >= period_from)
);

-- 워크스페이스별 요청 최근 50건 조회(`GET /workspaces/{workspace_id}/invoice-requests`)가
-- 이 열 조합으로 좁힌다.
CREATE INDEX ix_invoice_requests_workspace_id_requested_at
    ON invoice_requests USING btree (workspace_id, requested_at);

-- 같은 워크스페이스에 같은 기간의 처리 대기(requested) 요청이 둘 이상 있을 수 없다
-- (계획 §2 결정 2 — 409 「같은 기간의 요청이 처리 대기 중입니다」). 부분 유니크 색인이라
-- issued·rejected로 끝난 요청은 같은 기간을 다시 요청할 수 있다.
CREATE UNIQUE INDEX ux_invoice_requests_open_period
    ON invoice_requests (workspace_id, period_from, period_to)
    WHERE status = 'requested';
