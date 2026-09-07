# 세금계산서 요청 기록 — 사업자등록번호로 요청, 운영자 알림, 수동 발급 상태

- 작성 2026-09-07. 사용자 결정(2026-09-04): 「세금계산서는 로그인한 사용자가 사업자등록번호로 요청하면 발급한다 — 초기는 수동 발급」(`docs/master-plan.md`). 크레딧 묶음(PR #69) 뒤의 다음 후보(2026-09-07 「다음 후보 진행」).
- 목표: 워크스페이스 소유자가 세금계산서를 **요청**하고 그 기록이 남으며, 운영자가 메일로 알림을 받고 발급 뒤 상태를 바꾼다. 발급 자체(홈택스 전자세금계산서)는 수동이다. 결제·PG·자동 발급은 범위 밖.

## 1. 지금 참인 것

1. 청구 근거는 `usage-report`(월간 CSV)와 `credit-grant`(충전)로 만들어진다(`docs/pilot-runbook.md` 「월간 청구」·「크레딧 충전」). 세금계산서 발급을 요청하는 진입점은 없다.
2. 메일은 `MailSender` 포트(fake/SMTP), 발신 주소 `easydoc.mail.from-address`. 운영자 수신 주소 설정은 없다.
3. 계약 2.22.0, 마이그레이션 V15. 이 묶음은 **2.23.0 · V16**.
4. 일회성 운영 프로필 선례: `usage-report`, `credit-grant`(인자 파싱·트랜잭션·종료 코드).

## 2. 결정

1. **표 `invoice_requests`(V16)**: `id uuid PK`, `workspace_id uuid NULL FK workspaces ON DELETE SET NULL`, `owner_user_id uuid NOT NULL FK users ON DELETE CASCADE`, `business_number char(10)`(숫자 10자리, 체크섬 검증), `company_name varchar(100)`, `representative_name varchar(50) NULL`, `contact_email varchar(255)`, `address varchar(200) NULL`, `period_from date`, `period_to date`, `status varchar(16)` (`requested | issued | rejected`, CHECK), `operator_note varchar(500) NULL`, `requested_at timestamptz`, `handled_at timestamptz NULL`. 색인 `(workspace_id, requested_at)`. 사업자등록번호·상호·대표자·주소는 **사업자 정보**이지 문서 본문이 아니므로 암호화 열이 아니다 — 다만 `toString`에는 마스킹한다(인구조사 규약).
2. **요청 API(계약 2.23.0)**: `POST /workspaces/{workspace_id}/invoice-requests` 본문 `{ business_number, company_name, representative_name?, contact_email, address?, period_from, period_to }` → **201** `InvoiceRequestResponse`. 규칙: 소유자만(404), 사업자등록번호는 하이픈 허용 입력을 숫자 10자리로 정규화하고 **국세청 체크섬**(가중치 1,3,7,1,3,7,1,3,5 + 9번째×5의 십의 자리 규칙)을 통과해야 한다(422 「사업자등록번호가 올바르지 않습니다」), 기간은 `period_to >= period_from`이고 366일 이하, 같은 워크스페이스에 **같은 기간의 `requested` 상태 요청이 있으면 409** 「같은 기간의 요청이 처리 대기 중입니다」. 문자열 상한은 `x-request-field-constraints`. `GET /workspaces/{workspace_id}/invoice-requests` → 최근 50건(최신순).
3. **메일 두 통(최선 노력, 커밋 뒤)**: ① 운영자에게 — 수신 `easydoc.billing.operator-email`(typed 설정, 비어 있으면 보내지 않고 경고 로그 1줄), 제목 「[쉬운 글] 세금계산서 요청 — {company_name} {period}」, 본문에 요청 id·워크스페이스 id·사업자번호·상호·기간·연락 이메일. ② 요청자에게 — 「[쉬운 글] 세금계산서 요청을 받았습니다」 + 요청 id·기간. 발송 실패는 요청을 실패시키지 않는다(예외 클래스명만 로그).
4. **처리는 운영자 수동**: 일회성 프로필 **`invoice-handle`** `--id=<uuid> --status=issued|rejected --note="…"`(note ≤500) → `handled_at` 갱신 + 요청자에게 상태 메일(「발급되었습니다」/「처리할 수 없습니다: {note}」). `requested`가 아닌 요청은 exit 1. stdout에는 id·상태만.
5. **프런트**: `/usage` 크레딧 카드 아래 「세금계산서 요청」 버튼 → 폼(사업자등록번호·상호·대표자·연락 이메일·주소·기간 — 기본값은 지난달) → 성공 시 목록 갱신. 요청 목록 표(기간·상호·상태·요청일·운영자 메모). 접근성: 라벨·오류 `role=alert`·상태 `role=status`.
6. **러너북**: 「월간 청구」 절에 「세금계산서 요청이 오면 운영자 메일 확인 → 홈택스 발급 → `invoice-handle --status=issued`」 순서를 잇는다.

## 3. 슬라이스 (한 조각, M)

V16, core `BusinessNumber` 값 객체(정규화·체크섬), application `InvoiceRequestService`(+포트), Jdbc 어댑터, 계약 2.23.0(두 작업, 스키마, 409/422 예시, 변경 기록), 컨트롤러, `AuthenticatedEndpoints`, 메일 두 통, `invoice-handle` 프로필, 프런트 폼·목록, 러너북, backlog. 테스트: 체크섬(유효/무효/하이픈), 409 중복, 422 기간, 404 타 소유자, 메일 발송(FakeMailSender) 및 미설정 운영자 주소, 프로필 3 케이스(발급/거절/이미 처리), 실 DB 삽입·목록, Vitest 폼·목록·오류.

## 4. 수용 기준

- 유효한 사업자번호(예: 하이픈 포함 입력)로 요청하면 201, 목록에 `requested`, 운영자·요청자 메일 각 1통.
- 체크섬이 틀리면 422이고 행이 남지 않는다. 같은 기간을 다시 요청하면 409.
- `invoice-handle --status=issued`로 처리하면 목록 상태가 `issued`, 요청자 메일 1통, 두 번째 실행은 exit 1.

## 5. 범위 밖

자동 발급(홈택스 API), 결제·PG, 금액 계산(청구 금액은 운영자가 리포트로 산정), 어드민 화면.
