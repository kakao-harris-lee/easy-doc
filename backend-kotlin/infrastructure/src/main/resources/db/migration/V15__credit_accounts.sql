-- 크레딧 계정·차감 (C1, 계획 docs/plans/2026-09-07-credit-accounts.md §2 결정 2).
--
-- 워크스페이스마다 크레딧 잔액을 두고, 문서 등록 때 필요한 크레딧을 예약하고 변환이
-- 끝나면 소비하며, 잔액이 모자라면(집행이 켜졌을 때만) 등록을 거절한다.
--
-- **암호화 대상이 아니다.** 담는 열은 숫자·사유·메모뿐이라 개인정보가 없다 —
-- `llm_calls`(V14)와 같은 판단이다. 그래서 이 두 표는
-- `kr.easydoc.core.crypto.EncryptedField` 가 아는 표 목록에 들지 않고,
-- `OwnershipPredicateGuardTest`·`EnvelopeColumnWriteGuardTest`(둘 다 그 목록만 훑는다)
-- 인구조사 대상도 아니다.
--
-- **2026-09-07 독립 리뷰로 `credit_transactions.credits` 단일 열을 `balance_delta`·
-- `reserved_delta` 두 열로 바꿨다.** 단일 열로는 "이 거래가 balance 와 reserved 각각에
-- 얼마를 움직였는가"를 하나의 부호로 뭉뚱그려야 했고, 그 결과 `consume` 거래는
-- (Δbalance=-n, Δreserved=-n) 인데도 정합 불변식을 지키려면 열 값이 억지로 `0`이어야
-- 했다 — 감사 로그로서는 "얼마나 소비했는가"가 드러나지 않는 결함이었다. 이 마이그레이션은
-- **이 브랜치에서 새로 생겼고 어떤 환경에도 적용된 적이 없어**, 이미 적용된 마이그레이션을
-- 다시 쓰지 않는다는 규칙(프로젝트 `CLAUDE.md`)의 대상이 아니다 — 새 버전을 열지 않고
-- V15 자체를 고친다.

-- --- workspace_credit_accounts ---------------------------------------------
-- 워크스페이스 1건당 계정 1행. `balance` 는 부여 합계 − 소비 합계, `reserved` 는
-- 등록됐지만 아직 끝나지 않은 문서의 몫이다. **가용 = balance − reserved.**
CREATE TABLE workspace_credit_accounts (
    workspace_id uuid NOT NULL,
    balance integer NOT NULL DEFAULT 0,
    reserved integer NOT NULL DEFAULT 0,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_workspace_credit_accounts PRIMARY KEY (workspace_id),
    -- ondelete=CASCADE: 워크스페이스가 사라지면 계정도 함께 사라진다 — `llm_calls` 와
    -- 달리 이 표는 청구 근거 원장이 아니라 **지금 잔액**이라 보존할 이유가 없다(거래
    -- 이력은 아래 credit_transactions 가 진다).
    CONSTRAINT fk_workspace_credit_accounts_workspace_id_workspaces FOREIGN KEY (workspace_id)
        REFERENCES workspaces (id) ON DELETE CASCADE
    -- `reserved` 는 음수가 될 수 없지만(예약·소비·해제가 항상 짝을 맞춘다) `balance` 는
    -- 집행이 꺼진 상태에서 음수로 기록될 수 있다(계획 §2 결정 4) — 그래서 CHECK 를
    -- `reserved >= 0` 하나만 둔다.
);

ALTER TABLE workspace_credit_accounts
    ADD CONSTRAINT ck_workspace_credit_accounts_reserved_non_negative CHECK (reserved >= 0);

-- --- credit_transactions ----------------------------------------------------
-- append-only 거래 원장 — 계정 행의 숫자는 거래의 요약이다. 어긋나면 거래가 정본이다
-- (`CreditAccountRepository.consistencyViolations`).
CREATE TABLE credit_transactions (
    id uuid NOT NULL,
    -- FK 는 있지만 SET NULL — 워크스페이스가 사라져도 거래 이력(청구 근거)은 남는다
    -- (`llm_calls.workspace_id` 와 같은 판단, V14 머리주석).
    workspace_id uuid NULL,
    owner_user_id uuid NOT NULL,
    -- FK 없음 — 문서가 보존 만료로 지워져도 거래는 남아야 한다(V14 의
    -- document_id 와 같은 3차 정정 사유).
    document_id uuid NULL,
    kind character varying(16) NOT NULL,
    -- 두 열로 나눈 델타(2026-09-07 리뷰, 위 머리주석) — 이 거래가 계정 행의 두 숫자에
    -- 각각 얼마를 움직였는지 그대로 남긴다. reserve: (0, +n), consume: (-n, -n),
    -- release: (0, -n), grant/adjust: (±c, 0). 불변식 둘: sum(balance_delta) = balance,
    -- sum(reserved_delta) = reserved (`CreditAccountRepository.consistencyViolations`).
    balance_delta integer NOT NULL DEFAULT 0,
    reserved_delta integer NOT NULL DEFAULT 0,
    reason character varying(32) NOT NULL,
    note character varying(200) NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_credit_transactions PRIMARY KEY (id),
    CONSTRAINT fk_credit_transactions_workspace_id_workspaces FOREIGN KEY (workspace_id)
        REFERENCES workspaces (id) ON DELETE SET NULL,
    CONSTRAINT fk_credit_transactions_owner_user_id_users FOREIGN KEY (owner_user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    -- kind·reason 값 목록을 애플리케이션 상수에서 가져오지 않고 SQL 로 직접 적는다
    -- (다른 CHECK 제약과 같은 이유, V1 머리주석).
    CONSTRAINT ck_credit_transactions_kind_valid
        CHECK (kind IN ('grant', 'reserve', 'consume', 'release', 'adjust')),
    CONSTRAINT ck_credit_transactions_reason_valid
        CHECK (reason IN ('signup', 'plan_monthly', 'manual', 'refund', 'conversion'))
);

-- 워크스페이스별 최근 거래 조회(`GET /workspaces/{workspace_id}/credits`, 최근 50건)와
-- 정합 검사 집계가 이 열 조합으로 좁힌다.
CREATE INDEX ix_credit_transactions_workspace_id_created_at
    ON credit_transactions USING btree (workspace_id, created_at);

-- --- conversions.credits_reserved -------------------------------------------
-- 등록 시 예약한 크레딧을 **정산 때 다시 계산하지 않도록** 저장한다(계획 §2 결정 3) —
-- 공식이 바뀌어도 예약과 정산이 같은 수를 본다. 0 은 이 조각 이전에 만든 문서다
-- (worker 가 no-op 로 다룬다).
ALTER TABLE conversions
    ADD COLUMN credits_reserved integer NOT NULL DEFAULT 0;

ALTER TABLE conversions
    ADD CONSTRAINT ck_conversions_credits_reserved_non_negative CHECK (credits_reserved >= 0);

-- --- backfill ----------------------------------------------------------------
-- 기존 워크스페이스마다 0 잔액 계정 행을 만든다 — 배포 직후 모든 예약이 0행이 되어
-- 조용히 실패하지 않게 한다(`CreditAccountService.ensureAccount` 가 신규 워크스페이스에
-- 만드는 것과 같은 불변식을 과거 데이터에도 세운다).
INSERT INTO workspace_credit_accounts (workspace_id, balance, reserved)
SELECT id, 0, 0 FROM workspaces;
