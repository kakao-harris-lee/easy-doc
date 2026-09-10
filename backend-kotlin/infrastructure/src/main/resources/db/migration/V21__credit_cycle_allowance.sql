-- 크레딧을 「구독 주기에 포함된 이용량」으로 바꾼다 — 사용자 결정(2026-09-10). 몬딱
-- 솔루션은 별도 구매·충전 기능을 제공하지 않는다. 플랜의 크레딧은 독립된 선불 상품이
-- 아니라 각 결제주기에 포함된 문서변환 이용량이며, 결제주기 종료 시 초기화하고
-- 이월하지 않는다(§8 「이월 없음」 결정은 그대로 유지하되, 만료 묶음 모델 대신 계정
-- 하나에 붙는 단일 주기로 구현한다 — 플랜·구독 엔티티는 아직 없다).
--
-- **이 조각에서 만들지 않는 것**: 플랜·구독 엔티티, 결제, 만료 묶음(bucket), 차감
-- 순서, 추가 구매. 주기는 계정(`workspace_credit_accounts`) 자체에 붙는다.
--
-- **계정당 이용량 하나다** — 무료 체험과 플랜을 별도 잔액으로 쪼개지 않는다(사용자
-- 확정). 대신 `cycle_renews`가 그 주기가 갱신되는지(플랜)·한 번 쓰고 닫히는지(무료
-- 체험, 1개월 뒤 종료)를 가른다.

-- --- workspace_credit_accounts.allowance / cycle_started_at / cycle_ends_at --
-- `allowance`: 이번 주기에 제공된 이용량 — `CreditAccountService.setAllowance`가 잔액을
-- 이 값으로 **설정**할 때, 그리고 주기 종료 배치가 잔액을 이 값으로 다시 채울 때 쓴다.
-- `cycle_started_at`: 이번 주기가 시작한 시각. `cycle_ends_at`: 이번 주기가 끝나는
-- 시각 — **NULL이면 주기가 없다**(기존 계정, 또는 아직 플랜을 배정받지 않은 계정).
-- 주기가 없는 계정은 배치가 건드리지 않는다(이월도 소멸도 없이 잔액이 그대로 쌓인다 —
-- 기존 `grant`/`adjust` 동작과 같다).
ALTER TABLE workspace_credit_accounts
    ADD COLUMN allowance integer NOT NULL DEFAULT 0;

ALTER TABLE workspace_credit_accounts
    ADD COLUMN cycle_started_at timestamp with time zone NOT NULL DEFAULT now();

ALTER TABLE workspace_credit_accounts
    ADD COLUMN cycle_ends_at timestamp with time zone NULL;

-- `cycle_renews`: 주기 종료 배치가 이 계정을 **갱신**할지(`true` — 플랜: 잔액을
-- allowance로 다시 채우고 다음 달로 민다) **닫을지**(`false` — 무료 체험: 잔액·이용량을
-- 0으로, 주기를 NULL로 닫아 다시는 건드리지 않는다) 가른다. 기본값 false — 갱신은
-- 명시적으로 여는 주기(플랜)만의 성질이다.
ALTER TABLE workspace_credit_accounts
    ADD COLUMN cycle_renews boolean NOT NULL DEFAULT false;

-- --- backfill --------------------------------------------------------------
-- 기존 행은 「주기 없음」으로 둔다 — `cycle_ends_at`이 NULL이면 배치가 건드리지
-- 않으므로 배포 직후 아무 계정도 초기화되지 않는다. `allowance`는 현재 잔액과 같게
-- 맞춰 둔다 — 이후 운영자가 `setAllowance`로 진짜 주기를 열기 전까지 화면의
-- "이번 주기 N 중 M 남음" 표시가 잔액과 어긋나지 않게 하기 위해서다.
-- `cycle_started_at`은 컬럼을 더할 때 이미 DEFAULT now()로 채워졌다.
--
-- **`balance`가 아니라 `GREATEST(balance, 0)`을 쓴다.** `balance`는 음수로 기록될 수
-- 있다(V15 머리주석 — 집행 스위치 `easydoc.credits.enforced`가 꺼진 상태에서 초과
-- 사용하면 음수가 된다. 지금 파일럿이 실제로 이 상태로 돈다). 아래에서 막 추가할
-- `ck_workspace_credit_accounts_allowance_non_negative` CHECK는 이 UPDATE가 손대는
-- 모든 행에 적용되므로, 음수 잔액 계정이 하나라도 있으면 `allowance = balance`는
-- 마이그레이션 전체를 실패시킨다. 의미로도 `GREATEST`가 맞다 — 초과 사용해 잔액이
-- 음수인 계정의 "이번 주기 제공량"은 0이지 음수가 아니다. 그 계정들은
-- `cycle_ends_at`이 NULL(주기 없음)이라 `allowance` 값이 배치에 쓰이지도 않는다 —
-- 화면의 "이번 주기 N 중 M 남음" 표시도 `cycle_ends_at`이 있을 때만 뜬다.
UPDATE workspace_credit_accounts
SET allowance = GREATEST(balance, 0);

-- `allowance`도 `reserved`와 같은 이유로 음수를 허용하지 않는다 — 이번 주기에 "제공된"
-- 양이라는 뜻 자체가 0 이상이다(`balance`와 달리 집행 꺼짐과 무관하다). **backfill
-- 뒤에** 추가한다 — 먼저 추가하면 backfill UPDATE가 손대는 모든 행에 이 CHECK가
-- 걸려, 음수 잔액 계정이 하나라도 있으면 `balance`를 그대로 넣는 UPDATE가
-- 마이그레이션 전체를 실패시킨다(위 backfill 주석 참고).
ALTER TABLE workspace_credit_accounts
    ADD CONSTRAINT ck_workspace_credit_accounts_allowance_non_negative CHECK (allowance >= 0);

-- --- credit_transactions.kind — cycle_set · cycle_reset 추가 ----------------
-- `CreditTransactionKind.CYCLE_SET`(운영자가 주기를 연다)·`CYCLE_RESET`(배치가 주기
-- 종료 시 잔액을 이용량으로 다시 채운다). 기존 값 다섯 개는 그대로 둔다(V15).
ALTER TABLE credit_transactions
    DROP CONSTRAINT ck_credit_transactions_kind_valid;

ALTER TABLE credit_transactions
    ADD CONSTRAINT ck_credit_transactions_kind_valid
        CHECK (kind IN ('grant', 'reserve', 'consume', 'release', 'adjust', 'cycle_set', 'cycle_reset'));

-- --- credit_transactions.reason — cycle_end 추가 -----------------------------
-- 주기 종료 배치의 `cycle_reset` 거래는 갱신(renews=true)이면 기존 `plan_monthly`를
-- 그대로 쓴다(그 주기의 제공량을 다시 준 것이 맞다). 갱신 없이 닫히면(renews=false)
-- `CreditReason.CYCLE_END`를 쓴다 — **무엇을 했는지는 kind, 왜 그랬는지는 reason**이라는
-- 구분을 지킨다. 무료 체험 전용 사유가 아니다 — 운영자가 `credit-grant` CLI의
-- `--cycle-ends-at`만 주고 `--cycle-renews` 없이 연 유상 주기도 같은 사유로 닫힌다.
-- 「이용량은 계정당 하나」 결정(2026-09-10)이 잔액을 출처별로 쪼개지 않는 대신 이
-- 사유의 정확성에 기댄다 — `plan_monthly`로 잘못 적으면 없었던 구독이 있었던 것처럼
-- 보인다.
ALTER TABLE credit_transactions
    DROP CONSTRAINT ck_credit_transactions_reason_valid;

ALTER TABLE credit_transactions
    ADD CONSTRAINT ck_credit_transactions_reason_valid
        CHECK (reason IN ('signup', 'plan_monthly', 'manual', 'refund', 'conversion', 'cycle_end'));
