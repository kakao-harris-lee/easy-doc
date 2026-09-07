# 크레딧 계정·차감 — 워크스페이스 잔액, 등록 시 예약, 완료 시 소비

- 작성 2026-09-07. 사용자 결정(2026-09-07): 「크레딧 계정·차감 스팩 쓰고 진행」. 사용량 가시화 묶음(PR #65, `docs/plans/2026-09-07-usage-ledger-and-report.md`) 위에 얹는다.
- 기준: `docs/master-plan.md` L186–188(크레딧 = 공백 포함 1,000자 = 1크레딧, Starter 50 / Pro 200 / Enterprise 500+ 월 구독, 연간 라이선스), L156–157(P0-7 크레딧 과금 · P0-8 결제 — PG는 MVP 밖, 청구서 → 계좌이체 → 세금계산서 수동).
- 목표: 워크스페이스마다 크레딧 잔액을 두고, 문서 등록 때 필요한 크레딧을 **예약**하고 변환이 끝나면 **소비**하며, 잔액이 모자라면 등록을 **거절**한다. 충전은 운영자가 수동으로 한다(계좌이체 확인 뒤). 결제·플랜 자동 갱신·어드민 화면은 범위 밖이다.

## 1. 지금 참인 것

1. 문서 등록은 `DocumentService.createFromText/…` → `store()` 한 트랜잭션에서 `documents`와 pending `conversions`를 넣는다. `charCount`는 정규화 뒤 확정되고 20,000자 상한(422)과 이메일 미인증 403이 그 앞에 있다(`DocumentService.kt:74–212`).
2. 변환 결과는 worker의 `ProcessConversionJob.finishSuccess`(`saveSuccess`)와 영구 실패 `saveFailure`가 한 트랜잭션씩 저장한다. 재시도 예정 실패는 상태를 pending으로 되돌린다.
3. 재변환 호출 예산이 **예약 → 정산** 패턴의 선례다: `UPDATE conversions SET reconversion_calls_reserved = … WHERE id = :id AND <소유 술어> AND used + reserved + n <= budget RETURNING …`, 0행이면 429(`JdbcConversionRepository.kt:177–381`, V10).
4. 사용량 집계는 원장 `llm_calls`에서 문서별 `ceil(document_char_count/1000)`을 크레딧으로 **파생**해 보여 준다(2.21.0). 그 값은 「썼어야 할 크레딧」이지 잔액이 아니다.
5. 계약 2.21.0, 마이그레이션 V14. 이 묶음은 **2.22.0 · V15**. 오류 본문은 `{detail}`뿐이고 추가 정보는 헤더로 낸다(`X-Remaining-Call-Budget` 선례). 402는 아직 계약에 없다.
6. 운영 진입점 선례: `usage-report` 일회성 프로필(`--from --to --out`, 종료 코드 0/1).

## 2. 결정

1. **계정 단위는 워크스페이스다.** 플랜(Starter/Pro/Enterprise)은 워크스페이스에 붙는 개념이고, 팀원은 같은 잔액을 쓴다. 사용자 단위 잔액은 만들지 않는다.
2. **표 두 개(V15).**
   - `workspace_credit_accounts(workspace_id uuid PK FK workspaces ON DELETE CASCADE, balance int NOT NULL DEFAULT 0, reserved int NOT NULL DEFAULT 0, updated_at timestamptz)`. `balance`는 부여 합계 − 소비 합계, `reserved`는 등록됐지만 아직 끝나지 않은 문서의 몫. **가용 = balance − reserved.** 워크스페이스가 생길 때 행을 만든다(기존 워크스페이스는 V15가 backfill로 0잔액 행을 넣는다).
   - `credit_transactions(id uuid PK, workspace_id uuid NULL FK workspaces ON DELETE SET NULL, owner_user_id uuid NOT NULL FK users ON DELETE CASCADE, document_id uuid NULL(FK 없음), kind varchar(16) — grant | reserve | consume | release | adjust, credits int NOT NULL(부호 있음: grant/release는 +, reserve/consume/adjust는 방향대로), reason varchar(32) — signup | plan_monthly | manual | refund | conversion, note varchar(200) NULL, created_at timestamptz)`. 색인 `(workspace_id, created_at)`. **거래는 append-only이고 계정 행의 숫자는 거래의 요약이다** — 둘이 어긋나면 거래가 정본이다(§4 수용 기준의 정합 검사).
   - 원장 `llm_calls`처럼 본문·개인정보가 없어 `EncryptedField` 밖이다. `document_id`에 FK를 두지 않는 이유는 보존 만료 뒤에도 거래가 남아야 하기 때문이다(사용량 묶음과 같은 결정).
3. **필요 크레딧 = `ceil(char_count / 1000)`**, 문서 등록 시 1회. 재변환·보정은 크레딧을 쓰지 않는다(호출 예산이 상한, master-plan §3.3). 필요값은 `conversions.credits_reserved int NOT NULL DEFAULT 0`(V15)에 **저장**한다 — 정산 때 다시 계산하지 않는다(공식이 바뀌어도 예약과 정산이 같은 수를 본다).
4. **예약(등록 트랜잭션 안).** `UPDATE workspace_credit_accounts SET reserved = reserved + :n, updated_at = now() WHERE workspace_id = :ws AND EXISTS(소유 술어: workspaces.user_id = :owner) AND (:enforced = false OR balance - reserved >= :n) RETURNING balance, reserved`. 0행이면 **402** `detail` 「크레딧이 부족합니다. 충전 후 다시 시도하세요.」 + 헤더 `X-Credit-Balance`(가용 크레딧)·`X-Credits-Required`(필요). `documents`/`conversions` insert 앞에 둔다 — 거절되면 문서가 생기지 않는다. 같은 트랜잭션에 `credit_transactions(kind=reserve, credits=-n, reason=conversion, document_id)`를 넣는다.
   - **집행 스위치 `easydoc.credits.enforced`(기본 `false`).** 배포 직후 모든 워크스페이스 잔액이 0이라 곧바로 켜면 모든 등록이 402가 된다. 꺼져 있어도 예약·소비·거래는 **똑같이 기록**한다(잔액이 음수가 될 수 있고, 그 사실이 곧 청구 근거다). 운영자가 잔액을 부여한 뒤 켠다. 응답 헤더 `X-Credit-Balance`는 스위치와 무관하게 성공 응답(`202 Accepted`)에도 싣는다 — 화면이 잔액을 안다.
   - **가입 부여 `easydoc.credits.signup-grant`(기본 `0`).** 기본 워크스페이스가 만들어질 때 그 수만큼 `grant/signup` 거래와 잔액을 넣는다. 파일럿 값은 운영자가 정한다(예: 50). 워크스페이스를 추가로 만들 때는 부여하지 않는다.
5. **정산(worker).** `finishSuccess`의 트랜잭션에서 `reserved -= n, balance -= n` + `consume` 거래. 영구 실패(`saveFailure`)의 트랜잭션에서 `reserved -= n` + `release`(+n) 거래 — 변환이 안 된 문서는 청구하지 않는다. 재시도 예정 실패는 손대지 않는다. n은 `conversions.credits_reserved`에서 읽고 0이면 아무것도 하지 않는다(V15 이전 문서).
6. **부여·조정은 운영자 수동.** 일회성 프로필 **`credit-grant`**: `--workspace=<uuid> --credits=<정수, 음수 허용> --reason=plan_monthly|manual|refund --note="…"`. 잔액과 `grant`(양수) 또는 `adjust`(음수) 거래를 한 트랜잭션에서 쓰고, 결과 잔액을 stdout에 낸다(워크스페이스 이름·이메일은 내지 않는다). 종료 코드 0/1. 월 구독 갱신은 이 프로필을 월초에 돌리는 것으로 시작한다(자동화는 P0-8과 함께).
7. **조회 API(계약 2.22.0).** `GET /workspaces/{workspace_id}/credits` → `{ workspace_id, balance, reserved, available, enforced, transactions: [{ id, kind, credits, reason, note, document_id, created_at }] }` — 최근 50건, 소유자만(404). `createDocument`에 402 응답과 두 헤더를 선언한다. 402는 새 공유 컴포넌트 `InsufficientCredits`(본문 `{detail}`만, 헤더 둘 필수).
8. **프런트.** `/usage` 위에 「크레딧」 카드(가용·잔액·예약 중·집행 여부 안내)와 거래 표. 업로드 화면: 글자 수 카운터 옆에 「필요 크레딧 N / 가용 M」(가용은 `GET …/credits`로, 필요는 클라이언트가 `ceil(자수/1000)`), 402면 그 안내를 오류 자리에 보여 준다. 집행이 꺼져 있으면 「(지금은 집행되지 않습니다)」를 덧붙인다.
9. **만료·플랜 자동화·PG·어드민 화면은 범위 밖.** 월 크레딧 만료(이월 없음)는 플랜 결정이 필요하므로 backlog에 남긴다.

## 3. 슬라이스

- **C1 백엔드·계약 (M) — 구현(2026-09-07).** V15(두 표 + backfill + `conversions.credits_reserved`), core `Credits` 값 객체·`CreditTransactionKind/Reason` enum, application `CreditAccountService`(reserve/consume/release/grant, 포트 `CreditAccountRepository`), `DocumentService.store` 예약 삽입, `ProcessConversionJob` 정산, 워크스페이스 생성·가입 시 계정 행/가입 부여, `CreditsProperties(enforced, signupGrant)`, 402 예외·헤더, 계약 2.22.0(`createDocument` 402·헤더, `readWorkspaceCredits`, `InsufficientCredits`), 인구조사·`OwnershipPredicateGuardTest`. 테스트: 예약 성공/402/집행 꺼짐 음수 허용, 소비·해제 트랜잭션, 재시도 무영향, V15 이전 문서(0) 무영향, 실 DB 동시 등록 경쟁(가용 1에 문서 2건 → 하나만 성공), 거래 합 = 계정 잔액.
  - `consume` 거래의 `credits` 는 항상 `0`이다(구현 확정) — `reserve` 가 예약 시점에 이미 `-n`을 기록했으므로, 완료 확정이 잔액-예약 합계를 다시 바꾸지 않는 것이 §4 정합 불변식(거래 합 = balance − reserved)이 각 거래 시점마다 성립하도록 강제한 결과다.
  - `CreditAccountService.reserve`는 `enforced` 를 인자로 받지 않고 서비스 생성 시점(`easydoc.credits.enforced`)에 고정한다 — 이 저장소에 호출자가 하나(`DocumentService`)뿐이라 매 호출 인자로 갈릴 이유가 없고, 정책 판단을 서비스 하나에 모은다(리포지토리 포트의 `reserve`는 계획 원안대로 `enforced` 를 받는다).
  - `DocumentService`·`ProcessConversionJob` 은 크레딧과 무관한 기존 실 DB 테스트가 새 협력자를 배선하지 않아도 되도록 `NoopCreditAccountRepository`(`application.credit`, 항상 성공)를 공유 대역으로 공개했다 — 실 조립(`CreditAccountConfiguration`)은 쓰지 않는다.
- **C2 운영·프런트 (S).** `credit-grant` 프로필 + 테스트, `/usage` 크레딧 카드·거래 표, 업로드 화면 필요/가용·402 안내, Vitest, e2e 1건(집행 켠 e2e 스택에서 잔액 0 → 402; 프로필로 부여 → 202)은 e2e 스택 구성 변경이 크면 생략하고 backlog에 남긴다. 러너북 「크레딧 충전」 절(계좌이체 확인 → `credit-grant`).

## 4. 수용 기준

- 가용 3에서 2,500자 문서 등록 → 예약 3, 가용 0; 완료되면 잔액 −3·예약 0; 영구 실패면 잔액 그대로·예약 0. 재변환은 잔액을 바꾸지 않는다.
- 집행이 켜진 상태에서 가용 1에 1,001자 문서 → 402 + `X-Credit-Balance: 1`, `X-Credits-Required: 2`, 문서 없음.
- 집행이 꺼진 상태에서 같은 요청 → 202, 잔액이 음수로 기록된다.
- 동시 등록 2건(가용 1) → 정확히 하나만 202.
- `credit_transactions`의 `credits` 합 = `balance − reserved`가 모든 워크스페이스에서 성립한다(실 DB 테스트, 정합 검사 쿼리는 운영 리포트에서도 쓸 수 있게 리포지토리에 둔다).
- `credit-grant`로 50을 부여하면 `GET …/credits`가 잔액 50과 거래 1건을 보여 준다.

## 5. 범위 밖

PG 결제, 플랜 자동 갱신·만료, 세금계산서 요청 기록, 어드민 화면, 사용자 단위 잔액.

## 6. 리스크

1. 집행을 켜는 시점 운영 실수(잔액 미부여) → 전체 402. 완화: 스위치 기본 꺼짐, 러너북에 「부여 → 확인 → 켜기」 순서.
2. 예약 후 worker가 죽어 영원히 pending인 문서는 예약을 잡고 있다. 기존 lease 만료·재시도가 영구 실패로 끝내면 해제된다. 사용자가 그 문서를 직접 지우거나(`DocumentService.delete`) 보존 만료 파기(`JdbcExpiredDocumentPurge`)가 돌면, 삭제 전에 끝나지 않은 예약을 조회해 해제한다(2026-09-07 리뷰 HIGH-1) — worker 크래시로 문서가 영원히 pending에 남아도 삭제·파기 경로가 예약을 끝까지 붙잡아 두지 않는다.
3. 사용량 화면의 「크레딧」(원장 파생)과 계정 거래의 `consume` 합이 다를 수 있다(원장은 완료 호출 기준, 계정은 문서 완료 기준 — 보정 호출은 둘 다 문서 1건). 화면에서 둘의 이름을 구분한다: 「사용 크레딧」(원장) vs 「잔액」(계정).
