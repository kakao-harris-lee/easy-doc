# 어드민 최소 화면 — 관리자 플래그, 고객·사용량·오류 조회, 크레딧 수동 조정, 세금계산서 처리, 공지

- 작성 2026-09-07. 사용자 결정(2026-09-07): 「어드민 최소 화면 스팩 쓰고 진행」. 기준: `docs/master-plan.md` §4.1 **P0-11 어드민(최소)** — 「고객·사용량·오류 조회, 크레딧 수동 조정, 공지」.
- 목표: 지금 CLI 프로필로만 되는 운영 작업(`usage-report`·`credit-grant`·`invoice-handle`)을 로그인한 **관리자**가 화면에서 할 수 있게 한다. CLI 프로필은 그대로 둔다(배포·자동화용). 결제·플랜 자동화·세밀한 권한 체계는 범위 밖.

## 1. 지금 참인 것

1. 사용자 역할이 없다. 모든 인증 사용자는 자기 워크스페이스만 본다(소유 술어, 타인 자원 404).
2. 운영 데이터는 이미 있다: `llm_calls`(사용량), `workspace_credit_accounts`·`credit_transactions`(크레딧), `invoice_requests`(세금계산서), `conversions.status/failure_code`(오류). 집계 서비스 `UsageQueryService`·`UsageReportService`, `CreditAccountService.grant`, `InvoiceRequestService.handle`이 application에 있다.
3. `UserResponse`는 `email_verified`·`has_password`·`identities`를 가진다. 이메일 미검증 계정(네이버 등)은 존재한다 — **관리자 판정은 이메일 문자열에 기대면 안 된다**(미검증 이메일 선점 문제, backlog §1.4).
4. 계약 2.24.0, 마이그레이션 V16. 이 묶음은 **2.25.0 · V17**.

## 2. 결정

1. **관리자는 DB 플래그다.** `users.is_admin boolean NOT NULL DEFAULT false`(V17). 부여는 일회성 프로필 **`admin-grant --email=<이메일> [--revoke]`**(대상 계정의 이메일이 **검증된** 상태여야 부여, 아니면 exit 1). 설정 파일·환경변수로 관리자를 정하지 않는다. `UserResponse.is_admin`(필수, 2.25.0)으로 화면이 안다.
2. **관리자 판정은 요청마다 DB에서 읽는다**(토큰에 넣지 않는다 — 회수가 즉시 반영돼야 한다). `AdminGuard`가 `users.is_admin AND email_verified_at IS NOT NULL`을 확인하고 아니면 **403** `detail` 「관리자 권한이 필요합니다」. 관리자 API는 모두 `/admin/…` 아래이며 `AuthenticatedEndpoints`에 들어가고, 계약은 `x-admin-only: true` 표식으로 묶는다(`AuthenticationCoverageContractTest`가 그 표식을 이해하도록 확장 — 인증 필수 + 관리자 필수 두 축).
3. **감사 흔적.** 관리자가 화면에서 하는 변경은 누가 했는지 남긴다: `credit_transactions.actor_user_id uuid NULL`(V17, 관리자 화면·프로필 경유 부여에 채움; 자동 경로는 NULL), `invoice_requests.handled_by uuid NULL`(V17). 관리자 조회·변경은 `admin_action` 구조화 로그 한 줄(actor id, action, target id — 본문·이메일 없음).
4. **조회 API(계약 2.25.0, 모두 관리자 전용).**
   - `GET /admin/workspaces?q=&page=&size=` — 워크스페이스 목록: id, 이름, 소유자 이메일, 생성일, 크레딧(잔액·예약·가용), 이번 달 사용(문서·크레딧·비용 — `UsageQueryService` 재사용). `q`는 이름·소유자 이메일 부분 일치. 페이지 기본 20, 최대 100.
   - `GET /admin/workspaces/{workspace_id}` — 상세: 위 항목 + 최근 거래 50 + 세금계산서 요청 + 최근 변환 20(id, 문서 제목, status, failure_code, created_at — 본문 없음).
   - `POST /admin/workspaces/{workspace_id}/credits` `{ credits(≠0), reason: plan_monthly|manual|refund, note? }` → 200 계정 + 거래. CLI와 같은 서비스 경로, `actor_user_id` 기록.
   - `GET /admin/invoice-requests?status=requested|issued|rejected&page&size` / `POST /admin/invoice-requests/{id}/handle` `{ status: issued|rejected, note? }` → 200. `InvoiceRequestService.handle` 재사용 + `handled_by`.
   - `GET /admin/errors?from&to` — 기간 내 `failed` 변환: `failure_code`별 건수와 최근 50건 목록(id, workspace_id, created_at, failure_code). 본문·프롬프트 없음.
   - `GET /admin/usage?from&to` — `UsageReportService`의 행을 JSON으로(워크스페이스별). CSV는 계속 프로필.
5. **공지.** V17 `announcements(id uuid, body varchar(500), active boolean, created_by uuid, created_at, updated_at)`. 관리자: `GET/POST /admin/announcements`, `PATCH /admin/announcements/{id}` `{ body?, active? }`. 사용자: `GET /announcements/active`(인증 사용자, 활성 공지 목록 최신순 최대 5) — `AppLayout`이 상단 배너로 보여 주고, 닫기는 브라우저 로컬 저장(공지 id별).
6. **프런트.** `/admin` 라우트(관리자 아니면 `/`로 리다이렉트 + 안내), 화면 4개를 탭으로: 「워크스페이스」(검색·목록·상세·크레딧 조정 폼), 「세금계산서」(상태 필터·처리 폼), 「오류」(기간·코드별 건수·목록), 「공지」(작성·활성 토글). 계정 메뉴에 「관리」 링크는 `is_admin`일 때만. 접근성: 표 캡션·라벨·`role=alert/status`.
7. **범위 밖.** 역할 세분화, 관리자 초대 화면(부여는 CLI만), 사용자 계정 정지·삭제, 문서 본문 열람(관리자도 본문을 보지 않는다 — 개인정보 원칙), 결제.

## 3. 슬라이스

- **A1 백엔드·계약 (L). 구현(2026-09-07, 계약 2.25.0).** V17(`users.is_admin`·`credit_transactions.actor_user_id`·`invoice_requests.handled_by`·`announcements` 표), `admin-grant --email=<이메일> [--revoke]` 프로필(검증된 이메일만 부여, stdout은 user_id·플래그뿐), `AdminGuard`(매 요청 DB 재확인 + 403 매핑, `AdminAccessInterceptor`가 인증 뒤에 건다), `AdminQueryService`(목록·상세·오류·사용량 — 기존 서비스 조합: `CreditAccountService`·`UsageQueryService`·`InvoiceRequestRepository`·`UsageReportService`, 새 조회는 `JdbcAdminWorkspaceQueryRepository`·`JdbcAdminConversionQueryRepository`), 크레딧 조정(`AdminCreditAdjustmentService`, `credit-grant`와 같은 경로 재사용)·세금계산서 처리(`InvoiceRequestService.handle` 재사용)에 actor 기록, 공지 서비스(`AnnouncementService`), 계약 2.25.0(`x-admin-only` 오퍼레이션 열 개 + `listActiveAnnouncements` 하나로 11개, `UserResponse.is_admin` 필수), `AuthenticationCoverageContractTest`에 관리자 축 확장, 인구조사(`SensitiveToStringReachTest` 197·`OwnershipPredicateGuardTest` 미방어 상한 35 — 관리자 조회 셋 추가). 테스트: 비관리자 403(미검증 관리자 포함), 회수 즉시 반영, 목록 검색·페이지, 상세 본문 부재(`AdminWorkspaceDetailDtoLeakTest`), 크레딧 조정 actor 기록, 처리 handled_by + 메일, 오류 집계, 공지 CRUD + 활성 목록, `admin-grant` 3 케이스 — 전부 `AdminReachTest`(실 PostgreSQL) + 단위 테스트(`AdminGuardTest`·`AdminGrantServiceTest`·`Jdbc*Test`)로 확인. A2(프런트)·A3(러너북)는 아직.
- **A2 프런트 (M).** `/admin` 탭 4개, 가드, 계정 메뉴 링크, 공지 배너(닫기 로컬 저장), Vitest, a11y.
- **A3 문서 (S).** 러너북 「관리자 부여」·「어드민 화면 운영」(CLI와 화면의 관계), master-plan §4.1 P0-11 상태, backlog. A1 뒤 A2·A3 병행.

## 4. 수용 기준

- 관리자가 아닌 사용자가 `/admin/workspaces`를 부르면 403이고, `admin-grant`로 부여한 검증된 계정은 200을 받는다. 부여 회수는 다음 요청부터 즉시 403이다.
- 화면에서 크레딧 50을 부여하면 `credit_transactions`에 `actor_user_id`가 관리자 id인 `grant` 행이 남고 `GET /workspaces/{id}/credits`가 잔액 50을 보여 준다.
- 세금계산서 요청을 화면에서 발급 처리하면 `handled_by`가 채워지고 요청자 메일이 1통 간다.
- 오류 화면은 기간 내 실패 변환을 코드별 건수로 보여 주고 어떤 응답에도 본문·프롬프트가 없다.
- 활성 공지 하나를 만들면 일반 사용자 화면 상단에 배너가 뜨고, 닫으면 새로 고침해도 같은 공지는 다시 뜨지 않는다.

## 5. 리스크

1. 관리자 API 표면이 커진다 — `x-admin-only` 표식과 커버리지 테스트가 누락을 막는다.
2. 이메일 미검증 관리자 부여 사고 — 프로필이 검증 상태를 요구하고 가드가 매 요청 다시 확인한다.
3. 어드민 상세가 본문을 노출하는 회귀 — DTO에 본문 필드가 없고 `DocumentDtoLeakTest` 류 검사를 admin DTO에도 건다.
