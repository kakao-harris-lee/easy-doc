# Kotlin 재개발 backlog

- 작성일: 2026-08-24
- 목적: 현재 Kotlin/Spring Boot 제품에서 아직 구현되지 않은 기능과 결정이 필요한 항목을 관리한다. 이 문서는 계획이 아니라 **backlog**다. 착수 순서는 `docs/master-plan.md`와 활성 Sprint K1 문서를 따른다.
- 상태 근거: 현재 `backend-kotlin/`, `frontend/`, `contracts/easy-doc-v1.yaml`의 구현과 테스트.

## 1. 미구현 기능 (2026-08-24 현재 코드 기준)

**(2026-09-04)** 단계 2(P0)는 파일럿(게이트 ①)의 통과를 기다리지 않고 병행 착수한다 — `docs/master-plan.md` §9. P0 항목별 상태 지도는 §4.1.

| 기능 | 상태 | 비고 |
|---|---|---|
| `GET /conversions/{conversion_id}/export` (docx·txt·hwpx) | 구현 | `pdf`·구버전 `hwp`는 계약 enum 밖(422). POST export 없음 |
| Worker 작업 처리(리스 획득 → LLM 호출 → 결과 반영; 마스킹 단계는 2026-09-07 제거) | 구현 | `worker` 프로필이 lease를 집어 트랜잭션 밖에서 LLM을 호출하고 fencing으로 완료를 쓴다. 로컬 Compose는 `EASYDOC_LLM_PROVIDER=fake`로 유료 호출 없이 상태를 끝낸다 |
| 보존·자동 삭제 정책(기본 30일) | 구현 | worker가 `retention_expires_at` 만료 문서를 배치가 짧아질 때까지 반복 삭제한다. 활성 lease는 건너뛰고, dry-run은 한 배치만 미리본다. 건수 메트릭·문서 ID 감사 로그만 남긴다 |
| 긴 문서 처리(4,000자 초과) | **구현(2026-09-03, 계약 2.7.0)** — 상한 20,000자·출력 토큰 구성 기본값 64,000 | **게이트 ⓪ — 파일럿 착수의 선행 조건**(master-plan §9). 구현이 아니라 **측정과 선택**이 먼저다. 아래 §1.2 → 판정 기록은 §1.2 「최종 판정」 |
| 레이아웃 인지 PDF 추출(읽기 순서 복원) | 보류(결정 2026-09-02, 현행 유지) | 디자인 PDF(2단·카드 레이아웃)에서 추출 순서가 뒤섞이는 것을 실측 확인(2026-08-31). 평범한 채용공고 PDF(표 위주, 디자인 레이아웃 아님)에서도 같은 결함이 재현되는 것을 2026-09-02에 두 번째로 실측 확인했다. 아래 §1.3 |
| PDF 원본의 파일 내보내기 | **구현(2026-09-02, 계약 2.6.0)** — PDF는 사용자가 고른 DOCX·HWPX 신문서로 나간다. 렌더러 미도입 결정 | `ExportFormat.ofSource(PDF) = null` — 렌더러가 없고 `DESIGN.md` §6.5가 TXT 우회를 금지한다. 오늘 PDF 건의 export 요청은 형식 합의 단계에서 **409**다. **방향(2026-08-31): 라이브러리 조사 우선, PDF 렌더러가 어려우면 TXT 또는 DOCX·HWPX 선택 신문서 조립(레이아웃 유지)으로 간다 — 단 형식 선택·조립 분기 모두 현 경로에 없어 §1.3의 변경 단위를 따른다.** **결정(2026-09-02): PDF 렌더러는 도입하지 않는다 — PDF 원본은 사용자가 DOCX 또는 HWPX를 골라 신문서 조립으로 내보낸다.** 상세는 §1.3 |
| 쉬운 말 사전(RAG, pgvector 기반 팝업) | **구현(2026-09-05, PR #26·#29·#34; 임베딩 조건부)** | master-plan P0-5. Lean MVP 범위 밖으로 의도적으로 미뤄져 있었다. 계획은 `docs/plans/2026-09-04-p0-5-easy-word-dictionary-rag.md` (2026-09-06, PR 조각 6: e2e 조회 흐름 1건 추가). **caution/review_note 분리(2026-09-06, 독립 리뷰 반영 포함):** `entries.caution`이 사용자 노출용 안내문과 검수자 내부 메모를 섞어 담고 있어(474건 중 372~373건이 내부 메모), 프런트(`TermLookupPopover`)와 LLM 프롬프트(`DictionaryContextLines`)로 검수 이력이 그대로 유출되고 있었다. 새 필드 `review_note`(내부 전용, `export_index`에서 절대 제외)를 추가했다. 1차 구현 후 독립 리뷰에서 두 가지가 지적됐다 — ⑴ 문장 분리 규칙이 "메모가 먼저, 안내가 나중"인 순서(id 1926 '면제', 2024 '분기')를 놓쳐 안내 문장까지 통째로 옮겨버림 ⑵ `classify_caution()`을 `build.py`가 호출하지 않아 다음 빌드에서 같은 CSV를 다시 읽으면 유출이 되풀이됨. 두 가지 모두 고쳤다: 문장 분리를 매칭 여부로 필터링하는 방식(위치 비의존)으로 재작성했고, `row_to_entries()`가 CSV의 `note`를 Entry에 담기 직전에 `classify_caution()`을 호출하도록 해 잉제스트 시점 게이트로 만들었다(1회성 마이그레이션이 아니라 상시 불변식). `tools/check_invariants.py`에 "caution의 검수 메모 잔존 금지" 검사를 추가해 `check.sh`가 회귀를 잡는다. `NEEDS_CONFIRMATION_MARKER`(`[확인 필요]`)가 있는 caution은 build.py의 deprecated 강제 신호라 세 지점(분류·잉제스트·불변식) 모두에서 보존한다. 최종 분류: kept 101 / moved 358 / split 15(그중 human_pass 52 — 단일 문장 이동을 전부 표시하도록 강화). `dist/*`와 Kotlin 리소스 사본을 기존 빌드 경로(SQLite 정본 → `export.py` → `./gradlew :infrastructure:syncDictionaryIndex`)로 재생성했고, `surface_index`·entry id는 전부 그대로다. 참조 픽스처(`infrastructure/src/test/resources/dictionary/reference/*.txt`)도 함께 재생성했다(023·032·042·045·047·050·063·070 갱신 — 내부 검수 메모가 빠져 프롬프트가 짧아졌다). id 2185 '공지'는 검수 메모 분리와 무관하게 남아 있던 존댓말 불일치(`data/raw/welfare_seed_5.csv`)도 함께 고쳤다. 상세는 `dictionary/DESIGN.md` §3.2 |
| 골든셋 품질 평가(스타일 규칙 + LLM-as-judge) | 구현 | `./gradlew build`가 스키마·원문 사실·변환 스냅샷 스타일/사실·파일·ID·JSON digest 기준선을 검사한다. LLM-as-judge는 `./gradlew testLlm`이며 비밀값이 없으면 skip |
| 검수 피드백 기록(게이트 ① 판정 근거) | 구현 | `PUT /conversions/{id}/feedback` 멱등 upsert. 배포 의향·품질 만족도·소요 시간 + 자유 의견(AEAD 봉인). 수정률 지표는 저장 시점에 계산해 평문 숫자로 남긴다. `conversion_feedback`은 문서 30일 파기와 **분리**돼 있다(FK 없음). 집계는 `scripts/pilot-report.sql`, 절차는 `docs/pilot-runbook.md` 「게이트 ① 판정」. 조회 API(`GET`)와 재방문 시 이전 값 표시는 범위 밖 |
| 변환 완료 이메일 알림(P0-3) | **구현(2026-09-04) — provider: fake·smtp(임시)** | `ConversionCompletedNotifier`가 worker 완료 커밋 뒤(트랜잭션 밖) 문서 소유자에게 제목·링크만 담은 메일을 보낸다. `conversions.notified_at`(migration V5)로 재실행 멱등, 실패해도 변환을 막지 않는다. `MailSender` 포트에 `fake`(메모리 기록, 실제 네트워크 없음)와 `smtp`(2026-09-04 사용자 결정 — Daum 등 소비자 메일 계정을 임시 relay로, SMTPS만 지원) 두 어댑터가 있다. **SES가 의도한 운영 provider이고 smtp는 그 전환 전까지의 임시 조치다** — §1.4의 벤더 조사·요금 비교 결론은 그대로 유효하다 |
| 결제(카드·계좌이체·세금계산서) | 카드·계좌이체 자동화는 미구현(Lean MVP 범위 밖, master-plan 4.0). **세금계산서 요청 기록은 구현(2026-09-07, 계약 2.24.0)** — 발급 자체는 수동 | 계획 `docs/plans/2026-09-07-invoice-requests.md`. `POST`·`GET /workspaces/{workspace_id}/invoice-requests`(사업자등록번호 국세청 체크섬, 404 존재 은닉, 409 같은 기간 중복), 운영자·요청자 메일(FakeMailSender/SMTP, 운영자 주소 미설정 시 경고 로그), `invoice-handle` 운영 프로필(`--id --status=issued\|rejected --note`, 러너북 「월간 청구」에 이어진다), `/usage` 화면 요청 폼·목록. 홈택스 전자세금계산서 API 연동·PG·금액 계산은 범위 밖 |
| 크레딧 계정·차감(워크스페이스 잔액, 등록 시 예약, 완료 시 소비, 402 거절) | **구현(2026-09-07, C1+C2, 계약 2.22.0)** — 백엔드·API·운영 진입점·프런트·e2e(비집행) | 계획 `docs/plans/2026-09-07-credit-accounts.md`. V15(`workspace_credit_accounts`·`credit_transactions`·`conversions.credits_reserved`), `GET /workspaces/{workspace_id}/credits`, `createDocument` 402, `credit-grant` 운영 프로필(계좌이체 확인 후 수동 부여, 러너북 「크레딧 충전」, 소유자 조회+부여는 한 트랜잭션), `/usage` 크레딧 카드·거래 표, 업로드 화면 필요/가용·402 안내. **e2e E21은 비집행 변형으로 존재한다**(`frontend/e2e/credits.spec.ts`, `compose.e2e.yml`의 `EASYDOC_CREDITS_SIGNUP_GRANT=1000`) — 가입 부여·예약이 화면·202 헤더에 보이는지만 잰다. **집행을 켠 변형(가용 부족 → 402)은 아직 없다** — 켜려면 기존 e2e 스펙 전체(202 기대)가 깨지지 않는지 감사해야 한다. 그 감사는 아직 없다 — 추후 조각 |
| 운영자 어드민(고객·사용량·오류 조회, 크레딧 수동 조정, 세금계산서 처리, 공지) | **A1 백엔드·계약 구현(2026-09-07, 계약 2.25.0), A2 프런트·A3 문서 구현(2026-09-07)** | 계획 `docs/plans/2026-09-07-admin-minimum.md`. `users.is_admin`(V17), `admin-grant --email=<이메일> [--revoke]` 운영 프로필(검증된 이메일만 부여), `AdminGuard`(매 요청 DB 재확인, 403 「관리자 권한이 필요합니다」). `GET /admin/workspaces`(검색·페이지)·`GET /admin/workspaces/{id}`(크레딧·최근 거래 50·세금계산서 요청·최근 변환 20, 본문 없음)·`POST /admin/workspaces/{id}/credits`(`credit-grant`와 같은 경로, `actor_user_id` 감사 흔적)·`GET /admin/invoice-requests`·`POST /admin/invoice-requests/{id}/handle`(`handled_by` 감사 흔적)·`GET /admin/errors`(코드별 집계)·`GET /admin/usage`(JSON)·공지 CRUD(`GET`·`POST /admin/announcements`·`PATCH .../{id}`)·`GET /announcements/active`(관리자 전용 아님). `admin_action` 구조화 로그(actor·action·target). 프런트: `/admin` 탭 4개(워크스페이스·세금계산서·오류·공지), `RequireAdmin` 가드, 계정 메뉴 「관리」 링크, `AppLayout` 공지 배너(닫기는 `localStorage`). 러너북 「관리자 부여」·「어드민 화면 운영」. 결제·플랜 자동화·세밀한 권한 체계·문서 본문 열람은 범위 밖 |

**2026-09-05:** P0-4 문단 단위 대응·재변환은 S1–S5(원문·쉬운 글 단위 정렬, `segment_map` 조회
API, 검수 화면의 단위별 대응·편집 UI, 재변환 엔드포인트, 재변환 UI) 전부 병합됐다(PR #25, #28,
#30, #35, #36).

**S6(내보내기의 지도 소비) → 구현·병합(2026-09-06, PR #42·#43·#44 → #45, 계약 2.16.0).**
내보내기 반영이
`segment_map`을 소비해 조회 화면이 보여 준 대응과 파일의 대응이 갈리지 않는다 — 합침·나눔이
`HIGH` confidence면 `유지 가능`이 깨지지 않고, 지도가 없거나 전제가 어긋나면 차례 짝짓기로
떨어진다(오늘까지의 동작과 같다). 계획은
`docs/plans/2026-09-04-p0-4-paragraph-mapping-reconversion.md` §10.

**S7(`segment_map.compliant_source_units`) → 구현(2026-09-06, 계약 2.18.0).** 저장된 원문 단위
중 `checkStyle`을 위반 0건으로 통과한(공백 제외) 색인을 조회마다 유도해 싣는다. 검수 화면은 그
문단에 「이미 쉬운 글 규칙을 통과한 문단」 배지와 경고를 달고 재변환은 막지 않는다. 계획 §11,
CPU 실측 ≈24ms/20,000자.

**U1(LLM 호출 원장) → 구현(2026-09-07, 2026-09-08 리뷰로 보존 정책 3차 정정).**
`llm_calls`(V14)가 호출 1건 = 행 1건으로 purpose(`convert`·`repair`·`reconvert`)·
provider·model·토큰·지연·예상 비용·단가 스냅샷·`char_count`(호출별 마스킹 입력 길이)·
`document_char_count`(그 호출이 속한 문서의 `documents.char_count` 스냅샷, 2026-09-08
리뷰로 신설)를 남긴다 — 지금까지 구조화 로그로만 나가고 어디에도 저장되지 않던 값이다.
모델별 단가(`easydoc.llm.pricing.models.<model-id>`)가 단일 값 위에 얹혔고, 응답 model로
찾아 없으면 단일 값, 그것도 없으면 `null`로 떨어진다. `ProcessConversionJob`은 완료/실패
저장과 같은 트랜잭션에서, `ReconvertUnitService`는 정산 트랜잭션에서 원장을 쓴다 —
**2026-09-08 정정(§1 「실패 호출 원장 추적」):** 이전에는 실패한 provider 호출(예외)을
토큰이 없다는 이유로 기록하지 않았지만, 지금은 `outcome = provider_error`로 토큰
0·비용 `null`로 남긴다. 절단·빈 결과처럼 완성 자체는 받은
실패는 실제 사용량이 있어 기록한다. 재변환은 1차·보정 호출 둘 다 `reconvert` 하나로 묶인다
(`ConvertDocumentUseCase.convertMasked`의 `purpose` 매개변수 — 사후 재라벨링 대신 유스케이스가
직접 결정하는 쪽을 선택했다). **보존 정책 — 3차 정정(2026-09-08).** `conversion_id`·
`document_id`는 **FK 자체가 없다** — 참조 대상이 지워져도 원장 행의 값은 그대로 남는다
(당초 권고한 CASCADE, 1차 리뷰의 SET NULL 둘 다 뒤집혔다 — U2가 워크스페이스 사용량의
문서 수·문자 수·크레딧을 이 원장에서 유도하도록 다시 설계되면서, SET NULL조차 「문서
삭제 시 그 문서의 청구 근거가 원장에서도 사라진다」는 문제를 그대로 남긴다는 것이
드러났다). `workspace_id`만 여전히 `SET NULL`이고 `user_id`만 `CASCADE`다(계정이
없으면 청구 대상도 없다). 계획 `docs/plans/2026-09-07-usage-ledger-and-report.md` §3
U1. 집계(U2)·운영 리포트(U3)는 이 조각 밖이다.

**U2(워크스페이스 사용량 집계) → 구현(2026-09-07, 계약 2.20.0, 2026-09-08 리뷰로
집계 출처 정정).** `GET /workspaces/{workspace_id}/usage`가 **`llm_calls`(V14)
하나만** 읽어 워크스페이스·기간(`from`~`to`, 포함 상한, 생략 시 `easydoc.usage.zone`
기본 `Asia/Seoul` 기준 이번 달 1일~오늘)으로 집계한다. **문서 수·문자 수·크레딧은
`documents` 표를 참조하지 않는다** — 최초 설계(`documents.created_at` 기준)는 문서가
보존 만료로 지워지면 그 문서의 청구 근거까지 집계에서 사라지는 결함이 있어, 그 기간에
완료된 LLM 호출이 하나라도 있던 문서만(`document_id`로 distinct) `document_char_count`
스냅샷으로 세도록 리뷰로 정정했다 — 등록만 되고 변환되지 않은 문서는 비용·크레딧이
없으므로 이 집계에도 없다. LLM 호출 수·토큰·예상 비용(알려진 호출만 합산, 단가 미상은
`cost_unknown_calls`로만 센다)·목적별 소계도 `llm_calls.called_at` 기준이다. 소유자가
아니면 404, `from`·`to` 형식 오류·역순·366일 초과는 422(문자열 detail — 스키마 제약이
아니라 서비스 층 규칙이다), 빈 값(`?from=`)은 422 배열 detail(스키마 층,
`TypedValueSlotInterceptor`). 프런트 `/usage` 화면이 기간(이번 달·지난달·직접 입력)을
고르고 합계·목적별 표 둘을 보여준다. 계획
`docs/plans/2026-09-07-usage-ledger-and-report.md` §3 U2. 크레딧 차감(잔액·거절)과
운영 리포트(U3, `usage-report` 프로필, 소유자 전체 워크스페이스 집계는 U2가 쓰지 않고
U3가 새로 정의한다)는 이 조각 밖이다.

**U3(운영 리포트) → 구현(2026-09-07).** `usage-report` 프로필이 지난달(기본, `--from`·
`--to`로 지정 가능) 전체 사용자·워크스페이스별 사용량을 CSV로 쓰고 종료 코드 0/1로
끝난다(`rotate-keys`·`migrate`와 같은 CLI one-off, Compose 상시 서비스가 아니다).
`JdbcUsageReportRepository`가 **한 SQL**로 `(user_id, workspace_id)` 단위 — U2가 다루지
않는 `workspace_id IS NULL`(워크스페이스가 나중에 삭제된) 행까지 — 를 훑는다. CSV는
UTF-8 **BOM 포함**으로 쓴다(저장소에 CSV 선례가 없어 새로 정함 — 엑셀이 BOM 없는
UTF-8 한글을 깨뜨린다). `docs/pilot-runbook.md`에 「월간 청구」 절차(월초 리포트 →
청구서 → 계좌이체 확인 → 세금계산서 수동 발급)를 남겼다. 계획
`docs/plans/2026-09-07-usage-ledger-and-report.md` §3 U3. 크레딧 잔액·차감·거절, PG
결제, 세금계산서 자동 발급은 이 조각 밖이다.

**실패 호출 원장 추적 → 구현(2026-09-08, 계약 2.26.0, V18, U1 §6 리스크 1 후속).**
완성 자체가 나지 않은 provider 예외(`LlmProviderException`)는 U1이 `llm_calls`에
아예 기록하지 않았다 — 벤더는 실패한 요청의 입력 토큰에도 과금할 수 있어, 그 청구를
원장만으로 대조(reconcile)할 방법이 없었다(계획 §6 리스크 1). 이 조각이 그 배제를
뒤집는다: `llm_calls`에 `outcome`(`completed | provider_error`, 기본값
`completed`)·`failure_class`(예외 클래스의 단순 이름, 메시지는 담지 않는다) 열을
더하고(V18), `model` 열의 `NOT NULL`을 걷어냈다(응답 자체가 없으면 모델도 모른다).
`ConvertDocumentUseCase.Pass.complete`가 `LlmProviderException`을 잡을 때도 원장
항목 하나를 남긴다 — 토큰 0, 비용 `null`. `ProcessConversionJob`·
`ReconvertUnitService`는 이미 `usage.calls`를 그대로 원장에 옮기던 경로라 추가
배선이 필요 없었다. 집계(U2 `GET /workspaces/{workspace_id}/usage`, U3
`usage-report`·`GET /admin/usage`)는 `outcome = completed`인 행만 문서·문자·크레딧·
토큰·비용에 합산하고, 실패 건수는 `failed_calls`(워크스페이스 집계·목적별 소계·리포트
행 전부에 신설, CSV는 `llm_calls` 바로 뒤 열)로 따로 낸다 — 실패 호출만 있고 완료가
하나도 없는 문서·목적·워크스페이스·사용자도 집계에서 사라지지 않는다. 어드민
`GET /admin/errors`에 `provider_failures`(`AdminProviderFailureCount`, `failure_class`
별 건수)를 더했다 — 기존 `counts`(`conversions.failure_code`, 변환이 최종적으로
실패로 보고된 사유)와 다른 축으로, 재시도로 결국 성공한 변환의 실패 호출도 잡는다.
프런트 `/usage` 합계 표는 `failed_calls > 0`일 때만 「실패 호출」 열을 보여주고,
어드민 오류 탭은 `provider_failures` 표를 추가로 낸다. 계획
`docs/plans/2026-09-07-usage-ledger-and-report.md` §2 결정 2·§6 리스크 1(둘 다
2026-09-08 정정). 실제 벤더 청구서와의 금액 대조 자체(자동화)는 이 조각 밖이다 —
건수·사유만 보인다.

## 1.1 추후 개선 항목 (동작에는 문제 없음)

| 항목 | 현재 상태 | 판단이 필요한 것 |
|---|---|---|
| 피드백 저장 편집 거리의 셀 예산 초과 시 `NULL` (2026-09-03 추가) | 구현 — `easydoc.feedback.edit-distance-cell-budget`(기본 2억 셀), 접두·접미 제거 뒤에도 예산을 넘으면 `edit_distance`가 `NULL`이다. **2026-09-04 정정:** PR #13에 대한 Codex 리뷰가 이 예산 초과 갈래(글자 수는 채우고 거리만 비움)가 V2의 `ck_conversion_feedback_edit_metrics_paired`(「글자 수와 거리는 함께 있거나 함께 없다」)와 충돌해 실물 JDBC upsert가 매번 실패하는 것을 잡았다 — 인메모리 대역 테스트만 통과하고 실제 DB 경로는 한 번도 재지 않았던 결함이다. `V4__conversion_feedback_edit_distance_skip_reason.sql`이 `edit_distance_skip_reason`(`no_review`·`budget_exceeded`) 컬럼을 더해 두 사유를 구분하고 옛 짝 제약을 새 CHECK 셋으로 바꿨다 — `core/pilot/ConversionFeedback.kt`의 `EditDistanceSkipReason`이 코드 쪽 정본이다. `scripts/pilot-report.sql`의 「③-1 수정률 표본 구성」과 `docs/pilot-runbook.md` 「집계」가 두 사유를 나눠 보여준다. **2026-09-04 재정정:** PR #13에 대한 Codex 재심사가 새 발견(high)을 잡았다 — V4가 추가한 짝 CHECK(`edit_distance_skip_reason`)를 옛 애플리케이션(4e0c1b0, 이 컬럼을 모르는 UPSERT)이 그대로 어겨, 롤백이나 그 사이 떠 있던 옛 인스턴스의 피드백 저장이 전부 500으로 죽고 Flyway가 마이그레이션을 되돌리지 않아 롤백 자체가 막히는 결함이었다. V4에 `BEFORE INSERT OR UPDATE` 트리거 `conversion_feedback_derive_skip_reason`을 더해(V5를 만들지 않음) 사유가 없는 쓰기에서 지표 조합으로 사유를 되짚어 채우고, 거리가 실제로 채워진 재제출에서는 남은 사유를 지운다 — CHECK 셋은 그대로 마지막 방어선으로 남는다. **2026-09-04 2차 재정정:** Codex의 2차 재심사(medium)가 이 트리거 자체의 결함을 잡았다 — `edit_distance`가 채워져 있으면 사유를 조건 없이 항상 `NULL`로 지웠으므로, 새 애플리케이션이나 직접 SQL이 거리와 사유를 동시에 명시적으로 보내는 모순도 조용히 고쳐 써 짝 CHECK를 무력화하고 회귀를 숨겼다. V4를 다시 고쳐(V5 없음) 트리거를 둘로 쪼갰다 — gap-filling(`trg_conversion_feedback_derive_skip_reason`, 사유를 모르는 쓰기에서만 개입)과 거절(`trg_conversion_feedback_a_reject_skip_reason_contradiction`, `UPDATE OF edit_distance_skip_reason`에서 거리·사유 동시 지정을 예외로 막음)이며, 트리거 이름 알파벳 순서(`a_reject`가 `derive`보다 먼저 실행)에 실행 순서가 의존한다 | 지금은 단위 비용 Levenshtein 표를 그대로 굴리다 예산을 넘으면 포기한다. 비트 병렬 Levenshtein(Myers/Hyyrö, 워드당 O(n·⌈m/64⌉))으로 바꾸면 같은 CPU 예산 안에서 전면 수정된 장문의 수정률도 계산할 수 있다 — 지금은 그런 건이 `NULL`로 표본에서 빠진다. 파일럿 표본이 이 손실을 견딜 만한지, 구현을 지금 바꿀지는 판단 보류 |
| `DocumentConfiguration`의 `@Suppress("TooManyFunctions")` | 유지 | 파일럿 피드백 빈 둘이 붙으면서 detekt 임계를 넘어 억제를 걸었다. 근거는 클래스 KDoc에 있다 — 조립 지점의 함수 수는 클래스의 복잡도가 아니라 협력자의 수이고, 규칙을 지키려고 조립을 두 클래스로 가르면 「composition root는 하나」라는 성질이 사라진다. 대안은 ⑴ 억제 유지 ⑵ 도메인별 `@Configuration` 분리(그 성질을 포기) ⑶ detekt 임계 조정 셋이다. **협력자가 더 늘어날 때 다시 판단한다** — 억제가 늘어나는 신호를 못 보게 되는 것이 이 항목의 실제 비용이다 |
| `conversion_feedback`의 삭제 경로 | **구현(2026-09-04, 판단 ⑵)** — 자유 의견 세 열만 TTL로 자동 삭제, 척도 숫자는 영구 보존 | 이 표는 문서 30일 파기(`JdbcExpiredDocumentPurge`)의 사슬 밖이다. 파기는 `documents` → `conversions` → `conversion_jobs`로만 이어지고 피드백 표에는 FK가 없어(판정 근거를 남기려는 의도적 설계) TTL도 purge도 없었다. 자유 의견은 AEAD로 봉했지만 봉인은 기밀성이지 삭제가 아니고, 그 칸에는 문서 본문 조각이 실제로 들어온다(`V2__conversion_feedback.sql` 주석). 마스킹 범주는 2종뿐이라(master-plan §3.2) 그 조각의 이름·주소·전화번호는 가려지지 않는다. **판단 ⑵를 채택했다** — 계정 삭제·삭제 요청 처리 경로(판단 ⑴)는 계약 오퍼레이션에 아직 없어 범위 밖으로 남기고, 대신 자유 의견 세 열(`comment_encrypted`·`encryption_scheme`·`key_version`)에만 TTL을 둔다. 구현: `easydoc.feedback.comment-retention-days`(기본 30일, env `EASYDOC_FEEDBACK_COMMENT_RETENTION_DAYS`, `FeedbackProperties`) + worker의 기존 보존 파기 배치에 새 단계(`PurgeFeedbackComments` → `JdbcFeedbackCommentPurge`, `RetentionPurgeScheduler`가 문서 파기와 나란히 매일 실행하며 한쪽 실패가 다른 쪽을 막지 않는다)를 더했다. 나이 판정은 `updated_at`이 아니라 `submitted_at`이다 — 키 회전(`EnvelopeRotation.rotateFeedback`)은 `updated_at`만 밀고 `submitted_at`은 밀지 않으므로, 회전 주기가 보존 일수보다 짧아도 삭제 시계가 거기에 반응하지 않는다(`FeedbackProperties` KDoc이 정본). 척도 숫자(배포 의향·품질 만족도·소요 시간·수정률 지표)는 이 파기가 건드리지 않고 영구히 남는다. `docs/pilot-runbook.md` 「파일럿 종료 정리」의 수기 명령은 배치를 기다리지 않고 즉시 비우고 싶을 때, 그리고 표를 통째로 지우는 선택(⒝)에 대해서는 여전히 남는다. **FK를 되살리는 것은 답이 아니다** — CASCADE는 판정 근거인 표본 자체를 지운다. 남은 판단: 판단 ⑴(계정 삭제 경로가 생길 때 이 표를 포함시킬지)은 여전히 열려 있다 |
| 키 회전(`EnvelopeRotation`)에 운영 진입점이 없음 | **구현(2026-09-04)** | `DocumentConfiguration.kt:129-145`가 `EnvelopeRotation`을 `@Bean`으로 올리고 세 갈래(`rotateDocument`·`rotateConversion`·`rotateFeedback`)와 동시성 테스트(`EnvelopeRotationConcurrencyTest`)까지 갖췄지만, **프로덕션 코드에서 이 빈을 주입받는 곳이 하나도 없다** — 컨트롤러·스케줄러·CLI·`migrate` 프로필 어디에도 없고 `src/main` 안의 참조는 저 `@Bean` 선언뿐이다. 키 회전은 오늘 테스트에서만 실행된다. **이 PR이 만든 문제가 아니다** — 세 갈래 모두 이전부터 도달 불가였고, 이 PR은 새로 봉인한 열(`conversion_feedback.comment_encrypted`)이 회전 가족에서 빠지는 것을 `EnvelopeRotationTest`의 `EncryptedField` 전수 대조로 막았을 뿐 진입점 상태를 바꾸지 않았다. 왜 중요한가: `core/crypto/StoredContent.kt`가 저장 암호화의 요구 성질로 키 회전을 든다(`migration-safety-gate` I-7). 유출 의심·정기 교체처럼 **실제로 키를 갈아야 하는 사건이 오면 지금은 실행할 수단이 없다.** 판단이 필요한 것: ⑴ 운영 전용 프로필의 배치 태스크 ⑵ 관리자 API ⑶ 일회성 CLI 중 무엇을 진입점으로 둘 것인가, 그리고 **어느 세대까지 회전할지·중단과 재개·진행률 보고·`CONTENDED` 행의 재시도**를 어떻게 다룰 것인가(회전은 행 단위라 한 번에 끝나지 않는다). 함께 볼 것: `EnvelopeRotation.kt:26-33`의 `NOTHING_SEALED` KDoc이 「회전 배치의 집계」를 그 값의 존재 이유로 드는데 **그 배치가 아직 없다** — 진입점이 서면 그 주석이 참이 된다. **2026-09-04 구현:** ⑴ 운영 전용 프로필(`rotate-keys`, `migrate`와 같은 CLI one-off 형태)로 정했다 — `KeyRotationBatch`(신규, `application/document`)가 `SealedStores`의 각 저장소에 더한 `idsOlderThan`류 커서 포트로 가족 넷을 배치 순회하며 `EnvelopeRotation`의 기존 행 단위 메서드를 그대로 부르고, `KeyRotationRunner`(`infrastructure/document`, `ApplicationRunner`+`ExitCodeGenerator`)가 `rotate-keys` 프로필에서 그것을 한 번 돌리고 `ApiApplication.main`이 종료 코드로 프로세스를 끝낸다. 어느 세대까지 회전할지는 `cipher.writeKeyVersion` 하나로 고정(그보다 낮은 세대는 전부 대상)했고, `CONTENDED` 행의 재시도는 별도 로직 없이 **다음 실행이 처음부터 다시 훑는 것**으로 해결했다(커서가 매 실행 초기화되므로 세대가 그대로인 행은 자동으로 다시 후보가 된다). 진행률 보고는 가족별 `rotated`/`skipped`/`remaining` 개수만 로그에 남기고 행 id·본문은 남기지 않는다. 절차는 `docs/pilot-runbook.md` 「키 회전 실행」. Compose에 `migrate`용 one-shot 서비스가 없어(Flyway는 `backend-api` 기동 시 자체 실행) `rotate-keys`도 대응하는 서비스를 추가하지 않았다 — 둘 다 `backend-api` 이미지를 프로필만 바꿔 일회성 `docker compose run`으로 돌린다 |
| 보존 기간 만료를 **목록 질의만 보지 않는다** | 내용을 내주는 경로 전부가 `retention_expires_at > now()`를 건다. `GET /documents`(목록)만 열려 있다 — **의도된 상태다** | 파기는 `RetentionPurgeScheduler`가 **매일 03:00 한 번** 도는 배치라 만료와 파기 사이의 창이 **최대 24시간**이다. 2026-08-27에 그 창을 `GET /documents/{document_id}/source`(계약 2.2.0)와 `GET /conversions/{conversion_id}`·`GET /conversions/{conversion_id}/export`·`PUT /conversions/{conversion_id}`(계약 2.3.0)에서 닫았다. **`PUT /conversions/{conversion_id}/feedback`도 함께 닫혔다** — 그 저장은 소유·상태 판정을 변환 조회에 위임하므로 별도 술어 없이 따라왔고, 사유는 위임의 우연이 아니다: 그 저장은 수정률 지표(`EditMetrics.of`)를 계산하려고 변환 본문을 **복호화해서 읽는다.** **다섯 경로 모두 만료된 문서면 404이고, 없음·타인·만료가 구분되지 않는다.** `PUT` 둘은 쓰기라 특히 중요했다: 파기 대상 문서에 새 검수본을 쓰면 다음 배치가 방금 쓴 내용을 지운다. **`conversion_feedback` 표가 파기 사슬 밖이라는 것(FK 없음)은 「이미 낸 의견이 남는다」는 뜻이지 「만료 뒤에도 새로 낼 수 있다」는 뜻이 아니다** — 그 구분은 계약 문구와 `RetentionReadGuardReachTest` RG-5가 함께 고정한다. **남은 것은 목록 하나뿐이고, 그것은 사용자 결정으로 열어 둔 것이다** — 목록은 제목만 싣고, 문서가 파기됐다는 사실을 사용자가 알아차리는 자리가 목록이다. 만료 직후 목록에서까지 소리 없이 사라지면 사용자는 이유를 알 수 없다. **따라서 이 항목은 「닫아야 할 구멍」이 아니라 「의도적으로 남긴 비대칭」의 기록이다.** 판단이 필요한 것: ⑴ 목록이 만료 행을 **표시는 하되 상태를 다르게** 보여 줄 것인가(예: 「보관 기간 만료」 뱃지 — 지금 `DocumentListItem`에는 그것을 실을 필드가 없다) ⑵ 그대로 둘 것인가(만료 직후 목록에서 클릭하면 상세가 404다 — 프런트는 그 문구를 이미 갖고 있으므로 오늘도 화면은 성립한다) ⑶ 배치 주기를 줄여 창 자체를 좁힐 것인가(`easydoc.retention.cron`). **⑵가 현재 상태이므로 아무것도 하지 않는 것도 선택지다** — 다만 그 선택이 명시적이어야 한다. **결정(2026-09-04, 사용자):** ⑵ 현행 유지를 명시적으로 채택한다 — 뱃지를 추가하지 않고 배치 주기(cron)도 바꾸지 않는다. 만료~파기 창은 최대 24시간이고 상세 화면이 이미 만료 문구를 보여 주므로 지금은 충분하다. 파일럿에서 사용자 혼란이 보고되면 그때 다시 연다. 코드 변경 없음 |
| `documents.title`의 `fallback_title`이 화면에서 도달 불가 | **현행 유지 (결정 2026-09-04)** | 업로드 화면이 제목을 필수로 만들면서(`frontend/src/pages/UploadPage.tsx` — 공백만 있으면 제출을 막는다) 계약이 정의한 `x-title-policy.fallback_title`(`"제목 없음"`)이 제품 경로로는 도달하지 않는다. **계약 위반은 아니다** — 클라이언트가 서버보다 좁게 받는 것은 허용된다. 다만 계약이 유지 중이라고 적어 둔 동작이 조용히 죽은 상태다. 판단이 필요한 것: ⑴ 계약에서 걷을 것인가(그러면 API 직접 호출자도 제목이 필수가 된다) ⑵ 화면을 되돌려 선택 입력으로 할 것인가 ⑶ 서버 계약으로만 남기고 그대로 둘 것인가. **결정(2026-09-04, 사용자): ⑶ 현행 유지.** 계약은 `x-title-policy.fallback_title`을 그대로 두고, 업로드 화면도 제목을 계속 필수로 받는다. 이유: 클라이언트가 서버보다 좁게 받는 것은 허용되는 관계이고, 어느 쪽을 바꿔도 얻는 것이 없다. 코드 변경 없음 |
| 골든 `050`·`055`의 `source_text`가 제품 경로로 재현 불가 | 두 초안의 원본(청소년사업 안내 2권, 노인실태조사 보고서)을 제품 추출기가 상한 초과로 아예 추출하지 못한다(2026-09-02 실측, 위 §1.2 「또 하나의 벽」과 같은 확인) | **결정(2026-09-02, 사용자 승인):** 원본을 제품이 받을 수 있는 형태로 다시 만들지 않는다. 두 표본은 「추출 상한 밖 문서에서 사람이 떼어 온 발췌」임을 명시하고 그대로 쓴다 — 「재현 불가」라는 사실 자체는 지우지 않는다, 그 상태로 쓰기로 한 것이다. **이 결정이 뜻하는 것:** `050`은 2026-09-01 게이트 ⓪ 1·2차 측정에 실제로 쓴 표본이므로, 그 측정 결과(위 §1.2 「1차 측정 실행」·「2차 측정 실행」)는 「제품이 실제로 받을 수 있는 입력」에 대한 것이 아니라 「사람이 떼어 온 발췌」에 대한 것으로 읽어야 한다. `050`의 `source_text`에 남은 `88 2026년 청소년사업 안내`·`90 …` 러닝 헤더는 페이지 범위 추출의 흔적으로 그대로 남는다. **2026-09-02 재확인: `050`은 머리말 오염만이 아니라 본문 꼬리도 문장 중간(「…대응조치방안」)에서 끊긴 조각이다** — `docs/golden-collection-plan.md`의 2026-08-27 감사 기록 정정 ⑶이 이 문서 자신이 다른 초안을 폐기한 기준 두 개에 `050`이 동시에 걸린다는 사실을 기록한다. **그래서 1·2차 측정에서 관측한 스타일 통과·사실 보존 결과는 「끊긴 입력이 변환 품질에 어떻게 작용했는지 모르는 채」 읽어야 한다** — 위 §1.2 「말할 수 없는 것」 문단이 이미 표본 수 부족·구조 항목 미확인을 판정 보류 사유로 들었는데, 이 절단은 그 목록에 추가되는 또 하나의 사유다. **게이트 ⓪ 판정은 이 결정으로 바뀌지 않는다 — 여전히 보류다.** 수집 원칙 차원의 함의(원칙이 「원본이 추출 상한 안에 드는가」를 다루지 않는다는 공백)는 `docs/golden-collection-plan.md`의 2026-09-02 감사 기록에 남겼다 |
| 사전 참조 픽스처가 골든 문서 **전건**에 결박돼 있다 | **구현(2026-09-04, 결정 ⓑ)** — 참조 대조를 승격 시점 56건으로 고정, 신규 문서는 대조하지 않음 | `DictionaryReferenceContextTest`가 `data/golden/documents/` 문서마다 `infrastructure/src/test/resources/dictionary/reference/{id}.txt` 하나를 요구했다. 픽스처는 `4413047`(2026-08-30)이 일반 테스트 리소스로 남긴 것이고 재생성은 그 README 스니펫(Python 참조 구현 `dictionary/src/easydict/lookup.py`)이다. **골든 문서를 승인할 때마다 Python 참조 구현을 돌려야 한다** — 2026-09-02 7건 승격에서 실제로 그랬다(기존 56건 바이트 동일 확인). CLAUDE.md 「Python parity 체계를 새로 만들지 않는다」를 어긴 것은 아니지만(새로 만든 것이 없다), 4413047 자신이 「비용은 실재한다」고 적은 그 결박이 코퍼스 성장과 함께 커진다(Codex 종료 검토 지적, 2026-09-02). 선택지: ⓐ 현행 유지 ⓑ 참조 대조를 고정 부분집합(예: 승격 시점의 56건)으로 한정하고 신규 문서는 대조하지 않음 ⓒ 픽스처를 Kotlin 출력 스냅샷으로 전환(참조 구현과의 대조 성질은 사라진다). **결정(2026-09-04, 사용자): ⓑ 고정 부분집합.** `DictionaryReferenceContextTest`가 `FROZEN_REFERENCE_DOCUMENT_IDS`(2026-08 승격 시점 56건, 상수로 고정)만 참조 구현과 대조하고 그 목록에 없는 id는 대조도 픽스처 요구도 하지 않는다 — 목록에 있는데 픽스처가 지워지면 별도 테스트(`고정 목록의 픽스처가 전부 있다`)가 잡는다. 2026-09-02 승격 7건(`022`·`023`·`047`·`050`·`105`·`106`·`107`)에 이미 만들어 둔 픽스처는 지우지 않았다 — 테스트가 요구하지 않을 뿐 참조로 남긴다(테스트 컨벤션상 미사용 픽스처가 있어도 실패하지 않는다: `TestFactory`는 골든 문서 목록을 순회하지 픽스처 디렉터리를 순회하지 않는다). 목록을 늘리려면 다시 사용자 결정이 필요하다. `docs/golden-collection-plan.md`의 2026-09-02 결박 기록에도 이 결정을 남겼다 |
| 사전 색인이 API·worker 프로필 동시 기동 시 두 번 적재된다 (2026-09-05 추가) | **해결(2026-09-06): 단일 적재 — `DictionaryConfiguration.dictionaryIndexHolder` 하나가 두 스위치의 합집합으로 조립되고, 실제 읽기는 `by lazy` 뒤에서 최초 소비자가 부를 때 한 번만 일어난다. `ConversionWorkerConfiguration.dictionaryContextSource` 는 그 공유 홀더를 소비한다** | 두 조립 지점이 같은 사전 색인 파일을 서로 다른 스위치(`DictionaryLookupProperties.enabled`·`DictionaryProperties.enabled`)로 각각 읽던 것이 원인이었다 — `DictionaryConfiguration` KDoc이 이전에는 "조각 4가 API 컨트롤러를 놓을 때 함께 정리한다"고 적었지만, 조각 4(`DictionaryLookupController`, 2026-09-05)는 소비자 쪽 null 처분(`termCandidateSource`)만 정리했고 이 적재 중복은 그대로 남아 있었다. **구현: `DictionaryConfiguration.dictionaryIndex(lookupProperties, dictionaryProperties)`가 두 스위치 중 하나라도 켜져 있으면 색인을 한 번만 읽고, `ConversionWorkerConfiguration.dictionaryContextSource`는 더 이상 자기 색인을 따로 읽지 않고 이 빈을 그대로 주입받는다.** 두 스위치의 의미는 분리된 채로 남는다 — `termCandidateSource`는 `dictionaryIndex`의 null 여부가 아니라 `DictionaryLookupProperties.enabled`를 직접 보고 판단하고(공유 적재 이후로는 조회가 꺼져 있어도 색인이 non-null일 수 있어 nullability만으로는 "조회가 켜져 있는가"를 답할 수 없다), `dictionaryContextSource`도 같은 원칙으로 `DictionaryProperties.enabled`를 직접 본다. 스위치가 켜져 있는데 공유 색인이 `null`이면(구성상 발생할 수 없다) 두 소비자 모두 조용히 사전 없이 흘려보내는 대신 fail-fast 한다(둘 다 `ConfigurationException`, 2026-09-06 재정정으로 `termCandidateSource`도 `error(...)`에서 통일했다). 검증: `DictionaryConfigurationTest`에 단일 적재를 증명하는 테스트(가짜 표면형을 담은 `DictionaryIndex`를 두 소비자에 공유해, 소비자가 이를 무시하고 자기 색인을 다시 읽으면 잡히게 함)와 두 스위치 조합(둘 다 켜짐·조회만·worker만·둘 다 꺼짐) 케이스를 추가했고, `DictionaryContextSourceTest`·`GoldenLlmLaneDictionary`(레인의 제품 조립 재현)의 호출부도 새 시그니처에 맞춰 갱신했다. **2026-09-06 2차 정정 — 즉시 로드가 `:api:test`를 OOM 시켰다.** 위 1차 구현은 `@Bean fun dictionaryIndex(): DictionaryIndex?`가 두 스위치의 합집합을 그 자리에서 계산해 색인을 즉시 읽었다. `DictionaryConfiguration`은 `@Profile`이 없어 항상 조립되고 `DictionaryProperties.enabled`의 기본값이 **켜짐**이라, Spring이 `@Bean`을 조립 시점(`preInstantiateSingletons`)에 즉시 호출하는 성질과 맞물려 **API 전용 컨텍스트조차** worker 스위치 기본값 때문에 조립 시점에 1.5MB 색인을 무조건 읽었다 — 어떤 소비자도 실제로 요구하지 않았는데도다. `:api:test`가 캐시한 여러 `@SpringBootTest` 컨텍스트가 이 무조건 로드를 겹쳐 힙을 실제로 고갈시켰다(`passwordHasher` 빈 생성 중 `java.lang.OutOfMemoryError: Java heap space`, 관련 없는 빈에서 터져 원인 추적이 어려웠다). **수정:** 새 `DictionaryIndexHolder`(`infrastructure/dictionary`)가 `enabled = lookup.enabled || dictionary.enabled`와 loader를 받아 실제 읽기를 `by lazy`(기본 동기화 모드) 뒤로 미루고 `fun indexOrNull(): DictionaryIndex?`만 공개한다. `dictionaryIndex` `@Bean`은 `dictionaryIndexHolder` `@Bean`(홀더 객체만 조립하는 값싼 호출)으로 바뀌었고, `termCandidateSource`·`dictionaryContextSource`는 각자 자기 스위치가 켜졌을 때만 `indexOrNull()`을 부른다 — 두 스위치 모두 꺼지면 홀더가 존재해도 결코 읽지 않고, 한쪽만 켜지면 그 소비자만 읽으며, 둘 다 켜지면 어느 쪽이 먼저 부르든 `by lazy`가 단 한 번만 읽어 공유한다. 검증은 카운팅 loader로 다시 썼다: (a) 두 스위치 모두 켜짐 → loader 정확히 1회, 두 소비자 모두 그 결과로 동작 (b) 두 스위치 모두 꺼짐 → `dictionaryContextSource`도 `NoDictionaryContext` (c) 두 스위치 모두 꺼짐 → 홀더가 `indexOrNull()`을 반복 호출해도 loader가 결코 안 불림 (d) 조회 꺼짐·worker 켜짐인데 worker 소비자(`dictionaryContextSource`) 자체가 조립되지 않는 상황(API 전용 프로세스에서 `@Profile("worker")` 밖) → `termCandidateSource` 혼자서는 loader를 안 부름. `./gradlew build` 재실행으로 `:api:test` 포함 전체 `BUILD SUCCESSFUL` 확인 |
| 저장 본문에 `\r`이 남아 단위 분할·스타일 검사에 섞인다 (2026-09-06 추가, PR #51 리뷰 지적) | **해결(2026-09-06)** — 저장 경계 셋에서 `\r\n`·단독 `\r`을 `\n`으로 정규화 | `stripControlChars`는 탭·개행·복귀를 문서 구조로 보고 **의도적으로 남기고**(`TermLookup` KDoc), `splitUnits`는 왕복 불변식(`joinUnits(splitUnits(x)) == x`, 계획 §6 A1이 `"a\r\nb"`를 포함) 때문에 `\n`만 자른다. 그래서 CRLF 문서는 단위마다 `\r`이 붙어 `checkStyle` 글자 수·`alignSegments` 앵커·S7 `compliant_source_units`·화면 `segment_map`에 섞였다(내보내기 `Export.kt`만 `\r\n`도 잘라 눈에 띄지 않았다). 고친 자리는 `splitUnits`가 아니라 **쓰는 경계**다 — `core/text/TextNormalization.normalizeLineEndings`를 `DocumentService.store`(붙여넣기·파일 추출 공통, 20,000자 판정 전), `ConversionReviewService.normalize`(PUT 검수본), `ProcessConversionJob.finishSuccess`(LLM 결과)에서 봉인 직전에 한 번씩 적용한다. 재변환 후보는 응답으로만 나가고 저장은 PUT 경로를 타므로 별도 경계가 없다. 기존 행은 마이그레이션하지 않는다 — 파생값뿐이고 다음 저장에서 정규화된다 |

## 1.2 긴 문서 처리 — 게이트 ⓪ (2026-08-27 추가)

4,000자(공백 포함) 상한이 파일럿의 실제 문서를 막는다. 실측 근거와 게이트로 세운 이유는 `docs/master-plan.md` §9 「게이트 ⓪」에 있다. **→ 2026-09-03 20,000자.**

**착수 순서를 뒤집지 말 것.** master-plan §3.2는 2026-08-08에 「분할 변환 필요성은 소멸」로 판정했고(gpt-4.1 기준), 그 절이 남긴 처방은 분할 구현이 아니라 **상한값 재조정**이다. 그런데 그 「필요성 소멸」 판정은 **지금 제품이 쓰는 모델(anthropic·claude-sonnet-5)에서 재검된 적이 없고**, 함께 적힌 「2,000자 초과 스타일 통과율 0.11」은 2026-08-30의 현재 모델 부분 관측(master-plan §3.2)이 같은 구간을 다시 쟀지만 각 조건 1회·15건의 눈금이라 판정을 갱신하지 못한다 — 그 범위도 **골든 최대 글자수(3,993자)까지**라, 이 게이트가 보는 4,000자 초과 구간은 여전히 표본이 없다. 그러므로:

- **0. 먼저 잰다 (다른 모든 항목의 선행).** 현재 모델로 골든셋 장문의 출력 팽창비와 스타일 규칙 통과율을 측정한다. 자리는 `./gradlew testLlm` opt-in 레인이며 실제 유료 호출이므로 **사용자 승인이 필요하다**. 이 측정이 나오기 전에는 아래 1~6을 설계하지 않는다 — 상한 상향으로 끝날 수 있는 일에 분할 파이프라인을 먼저 그리는 것이 이 항목의 가장 큰 낭비다.
  - **⏸ 이 측정은 `easy-dictionary` 프로젝트 완료 후로 미뤄져 있다 (2026-08-27 사용자 결정).** 쉬운 낱말 사전을 별도로 구축하는 작업이 진행 중이고, 그 결과가 변환 품질을 크게 움직인다. 사전이 바뀌기 전에 재면 그 값은 사전이 들어오는 순간 무효가 되므로 **두 번 돈을 쓰게 된다.** 사전 프로젝트가 끝나면 이 항목부터 다시 연다.
  - **레인 자체를 고치는 일은 그 전에 끝냈다** — 아래 「⚠ 그 레인은 지금 상태로 이 측정을 못 한다」가 적은 결함이 2026-08-27 실측에서 실제로 터졌기 때문이다. 그 실행은 1시간 11분을 쓰고 **측정값을 내지 못했다**: 56건 중 17건이 원인 불명 `PROVIDER_ERROR` 였고, 레인이 제품 설정을 읽지 않아 `effort` 가 제품과 달랐다.
  - **⚠ 그 레인은 지금 상태로 이 측정을 못 한다 (2026-08-27 확인).** ⑴ `data/golden/documents/` 56건에 4,000자 초과가 **0건**이다(최댓값 3,993자). 상한에 맞춰 수집된 말뭉치라 상한 밖을 잴 재료가 없다. ⑵ `GoldenCorpusLlmEvaluationTest`가 `OPENAI_API_KEY`를 먼저 보고 provider를 고르며 `easydoc.llm` 설정을 읽지 않아, 키가 둘 다 있으면 **제품이 쓰는 모델이 아닌 것**을 잰다.
  - **⚠ 출력 토큰 상한도 함께 걸린다.** `DEFAULT_MAX_TOKENS = 16_000`이 `core/llm/LlmProvider.kt:7`에 **코드 상수로** 박혀 있다. 2026-08-27 실측(1,500자 → 출력 3,902토큰, 2,113자 → 7,479토큰; 최대 2회 호출 합계라 상계치)으로 보면 1만 자는 이 상한을 넘긴다. 넘기면 `LlmTruncatedException` → `ConversionFailureKind.TRUNCATED`로 **변환 실패**다(§3.2 절단 방지 규칙). 즉 `MAX_CONVERTIBLE_CHARS`만 올리면 장문이 전부 실패로 떨어진다. 이 값은 운영 중 바뀔 수 있으므로 코드 상수가 아니라 `@ConfigurationProperties`로 옮긴다(CLAUDE.md 「상수와 구성 관리」). **→ 완료(2026-08-31):** `easydoc.llm.max-output-tokens`(env `EASYDOC_LLM_MAX_OUTPUT_TOKENS`, 기본 16,000 — 기본값 출처는 core `DEFAULT_MAX_TOKENS` 하나)로 이관했고, 조립은 `ConversionWorkerConfiguration`이, 평가 레인은 같은 해석(`GoldenLlmLane`)이 따르며 측정 조건 보고에 `max_tokens`가 실린다. **→ 상한(ceiling) 도입(2026-09-02, 사용자 결정):** 사용자가 상한 기준을 「A4 20장 정도의 내용을 처리하는 것을 상한으로」로 정했다. 도출: A4 1장 ≈ 1,800자(관공서 문서 관행 — 규격은 아니다) × 20장 ≈ 36,000자 → 팽창비 **1.35**(게이트 ⓪ 2차 측정 실측, `047`이 4건 중 최대)를 보수적으로 적용해 변환문 ≈ 48,600자 → 토큰/글자 **1.3**(2차 측정 실측: `022`가 단일 호출 11,468토큰 ↔ 변환문 11,455자로 거의 1:1, 나머지 문서는 더 높아 보수적으로 적용) → ≈ 63,000 → **64,000**으로 반올림. 64,000에는 근거가 하나 더 있다 — **게이트 ⓪ 1·2차 측정이 실제로 쓴 값**이고(위 「조건: … `max_tokens=64000`」) 그 조건에서 21,926자 문서(`022`)가 절단 없이 처리됐다. **약한 가정**: 팽창비와 토큰/글자 비율은 **n=4 표본**에서 왔고, A4 1장 1,800자는 관행이지 규격이 아니다. 다만 셋 다 보수적으로(높게) 잡아 **상한이 낮아서 정상 문서를 거절하는 방향으로는 틀리지 않는다.** 구현: 상수 `MAX_OUTPUT_TOKENS_CEILING = 64_000`을 **infrastructure**(`LlmProviderConfiguration.kt`)에 뒀다 — 「운영자 설정의 허용 범위」는 배포 정책이지 `LlmOptions`가 지킬 도메인 불변식이 아니라서 core가 알 이유가 없다. 검증은 하한과 같은 자리(`LlmProperties.validatedMaxOutputTokens()`)·같은 예외 타입(`ConfigurationException`)이다. **이 상한이 못 막는 것**: 상한 **안의** 오타는 통과한다(`64000`을 `6400`으로 쳐도 검증은 지난다 — 자릿수가 튀는 실수만 잡는다). 그리고 이것은 **단일 호출** 상한이라 변환+보정 합계 토큰·비용은 다루지 않는다. **→ 레인 검증 우회 발견·수정(2026-09-02):** 이 작업 중 `GoldenLlmLane.assemble()`이 `props.maxOutputTokens`를 **직접 읽어** `LlmOptions`를 만들고 있는 것을 발견했다 — `validatedMaxOutputTokens()`를 거치지 않아 방금 도입한 상한도, 기존 하한도 우회하고 있었다. 즉 **레인은 운영자 검증을 통과하지 않은 값으로 유료 측정을 돌 수 있었다.** 같은 검증을 타도록 고치고 회귀 테스트를 붙였다. **오늘 세 번째 같은 유형이다** — ⑴ 레인이 제품 사전 조립을 쓰지 않았다(2026-09-01, 위 「레인 사전 컨텍스트 주입 보정」) ⑵ 리포트가 실효 상한 대신 코드 상수를 찍었다(2026-09-01, 위 「레인 결함 보정」) ⑶ 이번 검증 우회. **교훈**: 「레인이 제품과 같은 것을 재고 있는가」는 매번 따로 확인해야 한다 — 한 번 고쳤다고 다음 지점이 저절로 맞아 있는 것이 아니다.
  - **측정 항목은 셋이다**: 출력 팽창비, 스타일 규칙 통과율, **절단 발생률**. 앞의 둘만 재면 "품질은 괜찮은데 절반이 실패하는" 상태를 통과로 읽는다.
  - 따라서 0번의 실제 작업은 셋이다: **출력 토큰 상한을 구성값으로 옮기기**(→ 완료 2026-08-31, 위 항목), **장문 골든 표본을 승인 경로에 넣기**(후보 `022`·`023`·`047`·`050`은 `docs/golden-drafts/`에 남겨 뒀다. 사실 3~6개 채우기와 `022`의 평문 연락처 제거가 선행이고, `golden-baseline.json` 갱신은 리뷰 승인 사항이다 — **준비 완료 2026-08-31**: 022 연락처 자리표시자화·022/023 facts 5개씩과 category "행정 안내문" 초안 반영, 047/050 대조 이상 없음(**정정 2026-09-01**: 050의 `"2026년"` fact는 이후 facts 검수에서 오류로 확인·교체됐다 — 아래 1차 측정 읽는 법 ⑸ 참고). 이동 자체는 변환 스냅샷·기준선 갱신과 같은 변경 단위라 측정 실행과 함께 간다)와 **레인이 제품 provider 설정을 따르게 하기**(→ 완료, 위 「레인 자체를 고치는 일은 그 전에 끝냈다」). `docs/golden`의 큰 PDF에서 1만 자 구간 표본을 더 뽑아야 할 수도 있다 — 지금 후보 중 1만 자를 넘는 것은 `022`(21,924자) 하나뿐이다.
  - **1차 측정 실행(2026-08-31, 사용자 승인下).** 조건: anthropic·claude-sonnet-5·effort=LOW·`max_tokens=64000`·dictContext=off, 대상: 장문 초안 4건(승인 코퍼스 밖 스테이징 — `-Peasydoc.golden.documents.dir` 노브), 각 1회. 결과: 변환 성공 4/4 · **절단 0/8 호출, 단일 호출 최대 출력 7,621토큰(현행 상한 16,000의 절반 이하)** · 스타일 통과 3/4 · 품질 실패 1건(050 사실 누락 3) · 팽창비 중앙/p90/최대 0.90/1.20/1.20 · 호출 13회 · 입력 142,059/출력 44,114 토큰(설정 단가 기준 약 $0.73) · 재시도 1회(ResourceAccessException, 회복). 읽는 법: ⑴ 2만 자급(022)도 출력이 **현행 16,000 상한에 닿지 않았다** — 「상한만 올리면 전부 절단 실패」 구조는 이 표본에서 재현되지 않았다. ⑵ 그러나 팽창비 중앙 0.90은 표본에 1.0 미만(수축) 문서가 있다는 뜻이고 **레인이 문서별 팽창비를 남기지 않아 022 단독 값을 확정할 수 없다** — 절단 없음이 충실한 변환의 증거가 아니라 자체 요약의 부산물일 가능성이 남는다(judge는 충실성 축에서 4건 모두 통과 — 050 실패는 사실 누락이다). **→ 레인 결함 보정(2026-09-01):** `LaneReport`에 문서 한 건 한 줄로 원문 글자 수·변환 글자 수·팽창비·출력 토큰·절단 호출 수·스타일 통과를 원문 글자 수 내림차순으로 내는 `appendDocumentSection()`을 추가했다(변환 실패 문서는 `-`로 낸다, 본문·프롬프트·응답은 여전히 싣지 않는다). 같은 회차에 게이트 ⓪ 블록의 「상한」 표시가 코드 상수 `DEFAULT_MAX_TOKENS`(16,000)를 찍던 결함도 고쳤다 — `LaneReport` 생성자가 `maxOutputTokens`를 기본값 없는 필수 인자로 받게 하고, `GoldenCorpusLlmEvaluationTest`가 제품 조립과 같은 해석인 `ready.options.maxTokens`를 넘긴다 — **1차 측정 당시 리포트의 「상한」 표시는 실제 실행값 64,000이 아니라 16,000을 찍고 있었다는 뜻이다**(위 「조건: … `max_tokens=64000`」은 사용자 승인·실행 기록에서 가져온 값이며 이 결함의 영향을 받지 않는다). **다만 이것은 계측 능력의 보정이지 값이 아니다 — 1차 측정은 이 보정 전에 돌아 개별 문서 값이 남지 않았으므로 022 단독 팽창비는 여전히 미확정이다.** ⑶ n=4 각 1회라 통과율·팽창비는 눈금이다. ⑷ dictContext=off라 사전 컨텍스트 주입이 켜진 제품 경로와 조건이 다르다 — **주입 on의 절단 여유는 이 측정이 답하지 않았다.** 주입 on이면 출력이 커지는 관측(dictionary §5.1)은 ≤3,993자 구간의 것이고 그마저 검정 불가로 판정된 값이라, 장문에서 주입이 출력을 얼마나 키우는지는 측정된 적이 없다. 절단 결론(0/8·최대 7,621토큰)은 **주입 off 조건에 한한다.** ⑸ 050 사실 누락 3은 변환 결함일 수도, 오늘 작성한 facts 초안이 너무 좁을 수도 있다(accept 변형 부족) — facts 검수와 같이 본다. **→ facts 검수 완료(2026-09-01):** 050의 `"2026년"` fact가 본문이 아니라 PDF 페이지 머리말에서만 나온 것으로 확인됐다(원문 내 `2026` 등장은 `"88 2026년 청소년사업 안내"`·`"90 2026년 청소년사업 안내"` 두 곳뿐, 둘 다 쪽번호+문서제목 반복) — 정상 변환이 옮길 이유가 없는 문자열이라 이 fact는 판정 도구로 무효였다. `"1388"`(청소년전화, 원문 본문 14회 등장)로 교체했다(accept는 비움 — 고정 식별번호라 표기 변형 여지가 없다). `"9세 이상"`→`"9살 이상"`, `"1주일"`→`"일주일"`·`"한 주"` accept 변형도 보강했다(코퍼스의 나이 세/살, 018의 개월/달 변형 관행과 같다). 같은 8/31 배치인 022·023·047은 전수 점검에서 모든 fact가 본문 실질 조항 근거였다 — **러닝 헤더 결함은 050 국지적이며 배치 전체의 구조적 문제는 아니다.** `"1월 1일"`은 원문 근거는 있으나 법령 예외 조항에 깊이 중첩돼 있어 accept로 풀 문제가 아니라 「이 fact를 요구하는 게 맞나」라는 스코프 판단이라 변환문 확인 전까지 보류했다. **다만 이 정정으로 「050 사실 누락 3」 전체가 설명되는 것은 아니다** — 원인 중 최소 하나(`"2026년"`)는 변환 결함이 아니라 facts 초안 오류로 설명되지만, 나머지 몇 건이 그 때문인지는 변환문이 없어 확정 불가다. **판정(상한 재조정 vs 분할 변환)은 022 변환문 실물 확인 전에 내리지 않는다** — 레인이 변환문을 보존하지 않던 문제는 2026-09-01에 노브로 해소했다(아래 「레인 변환문 보존 노브」 참고). 계측 결함(문서별 값 미기록·상한 표시 오기)은 위에서 보정했고 050 facts 초안도 검수를 마쳤다. **→ 2차 측정 실행(2026-09-01):** 제품 조건(dictContext=product)으로 쟀고, ⑷의 빈 구간(장문×주입 on의 출력·절단)과 022 단독 팽창비도 그 회차가 답했다 — 결과는 아래 「2차 측정 실행」, 판정 상태는 아래 「판정 — 상한값 재조정 쪽이 유력하나 확정은 보류」 항목(2026-09-01에 한 차례 확정했다가 같은 날 보류로 되돌렸다). **→ 레인 사전 컨텍스트 주입 보정(2026-09-01):** 2차 측정의 요구사항(제품 조건 dictContext=on)을 레인이 그대로 잴 수 없는 상태였다 — 레인의 사전 주입 이음매(`LaneDictionary`)는 `EASYDOC_LANE_DICT_CONTEXT_DIR`가 가리키는 디렉터리에서 `<문서id>.txt` 파일을 읽는 방식뿐이었고, 그 파일을 만들던 도구는 커밋 `4413047`에서 이미 제거돼 손으로 채워 넣어야 하는 상태였다 — **그러면 제품이 아닌 것을 재게 된다**(2026-08-27에 레인이 제품 provider 설정을 읽지 않아 측정값을 못 낸 것과 같은 실패 유형). 제품의 실제 조립은 `ConversionWorkerConfiguration.dictionaryContextSource(properties)` 하나로, `properties.enabled`(기본 true)면 `IndexedDictionaryContextSource(DictionaryIndexJsonReader().readClasspathResource(), properties.policy())`, 아니면 `NoDictionaryContext`다. 인덱스 위치는 런타임 노브가 아니라 코드 상수(`DictionaryIndexJsonReader.RESOURCE_PATH` = `/dictionary/easy_dict.index.json`, 클래스패스 리소스)이고 정본은 `dictionary/dist/easy_dict.index.json` → `./gradlew :infrastructure:syncDictionaryIndex`로 복사·커밋되며 `checkDictionaryIndex`가 `check`에 붙어 매 빌드 사본↔정본 바이트 동일성을 확인한다 — 레인에 경로 노브를 새로 만들지 않은 이유다(없는 규칙을 흉내 내면 그게 곧 두 번째 출처가 된다). 조치는 레인 `src/test`만 손댔다(제품 코드 무변경): `EASYDOC_LANE_DICT_PRODUCT` 환경변수를 추가해, 켜면 레인이 `ConversionWorkerConfiguration().dictionaryContextSource(DictionaryProperties())`를 그대로 불러 실제 `DictionaryContextSource`를 얻는다 — 인덱스 경로도 정책 예산도 레인이 다시 적지 않는다. 배선상 중요한 차이는, 제품 조립이 **마스킹 완료 후 본문**(`DictionaryContextSource.contextFor(MaskedText)`)을 요구해 파일 주입처럼 문서 id → 문자열을 미리 뽑을 수 없다는 점이다 — 그래서 제품 조립 모드에서는 컨텍스트 문자열을 미리 만들지 않고 `DictionaryContextSource` 포트를 `ConvertDocumentUseCase`에 넘겨, 실제 변환과 같은 시점(마스킹 직후)에 컨텍스트가 만들어지게 한다. 리포트의 측정 조건 표시는 `dictContext=off` / 파일 주입 `dictContext=N/M` / **제품 조립 `dictContext=product`**로 구분된다. 안전장치: `EASYDOC_LANE_DICT_CONTEXT_DIR`와 `EASYDOC_LANE_DICT_PRODUCT`를 함께 설정하면 우선순위를 두지 않고 거절하고(하나가 조용히 이기면 리포트만 보고 무엇을 쟀는지 알 수 없다), 제품 조립을 요청했는데 색인을 못 읽어도 거절한다(기존 「모호하면 실패로 알린다」와 같은 원칙). 검증: `GoldenLlmLaneDictionaryTest`에 무료 테스트 6개 추가(실제 커밋된 색인으로 컨텍스트가 만들어지는지까지 확인), `./gradlew build` 전체 통과 — 유료 레인은 실행하지 않았다. **→ 레인 변환문 보존 노브 추가(2026-09-01):** 위 ⑵·⑸에서 남긴 물음 — 「충실한 축약」과 「내용을 버린 요약」은 팽창비 숫자 하나로 구분되지 않고, 050 사실 누락 3 중 나머지 몇 건이 변환 결함인지도 변환문 없이는 확정할 수 없다 — 는 문서별 값 계측(`appendDocumentSection()`)만으로는 채워지지 않는다. 그 계측은 **어느 문서를 봐야 하는지 좁혀 줄 뿐 판정 기준(022 변환문 실물 확인) 자체를 채우지 못한다.** 이 노브 없이 2차 유료 측정을 돌렸으면 판정을 못 내리고 같은 측정을 다시 사야 했다. 조치는 레인 `src/test`만 손댔다(제품 코드 무변경): `EASYDOC_LANE_TRANSCRIPT_DIR` 환경변수를 추가해, 설정하면 변환 성공 문서마다 `<디렉터리>/<문서id>.txt`로 변환 결과 본문을 남긴다(미설정이면 기존과 완전히 동일 — 파일도 안 만들고 리포트도 안 바뀐다). 변환 실패 문서는 남길 본문이 없어 건너뛰되 건너뛴 사실(문서 id만)은 리포트에 남긴다. 관측 경계(이 저장소는 본문을 로그·리포트에 남기지 않는다)는 본문을 **파일이라는 다른 자리**에만 두는 방식으로 지켰다 — 리포트에는 보존 여부와 디렉터리 경로만 한 줄(`transcript=off` 또는 `transcript=<경로>`)로 싣는다. `LaneMeasurement`·`LaneReport`가 숫자와 문서 id만 다루는 성질은 그대로이며, 기존 `GoldenCorpusLlmEvaluationTest.score()`의 `assertThat(report.render()).doesNotContain(converted)` 단언이 유지되어 유료 실행 중에도 문서마다 본문이 리포트에 안 실리는지 재확인한다. 기본값은 만들지 않았다 — 호출자가 준 경로만 쓰며, 변환 결과가 저장소 경로로 흘러 들어가지 않도록 저장소 밖 절대경로를 요구한다. 디렉터리를 만들 수 없거나 만들었어도 쓸 수 없으면(빈 probe 파일을 쓰고 지워 확인 — `createDirectories`만으로는 기존 디렉터리의 쓰기 권한을 확인하지 못한다) **유료 호출을 시작하기 전에** 거절한다 — 조용히 넘어가면 변환문이 남는 줄 알고 유료 호출을 다 쓰고 아무것도 못 건진다. 검증: `LaneTranscriptTest` 신규 + `LaneReportTest` 2건 추가, `./gradlew build` 통과 — 유료 레인은 실행하지 않았다. **이 두 보정(사전 주입·변환문 보존)은 2차 측정을 실행할 수 있게 만들었다 — 2차 측정은 2026-09-01에 실행됐다(아래 「2차 측정 실행」). 판정 상태는 아래 「판정 — 상한값 재조정 쪽이 유력하나 확정은 보류」를 본다 — 상한값 재조정으로 한 차례 확정했다가 같은 날 근거 미흡으로 보류로 되돌렸다.**
- **2차 측정 실행 노브(실행 전 기록, 2026-09-01).** 대상 한정: `-Peasydoc.golden.documents.dir=<장문 4건 스테이징 절대경로>` — **이것을 빠뜨리면 기본값인 승인 코퍼스 56건을 재게 되어 승인 범위와 비용을 넘긴다**(이 노브는 `tasks.withType<Test>().configureEach`가 걸어 주므로 `testLlm`에도 적용된다). 제품 사전 조립: `EASYDOC_LANE_DICT_PRODUCT=1`. 변환문 보존: `EASYDOC_LANE_TRANSCRIPT_DIR=<저장소 밖 절대경로>` — **이것을 빠뜨리면 변환문이 남지 않아 022 변환문 실물 확인을 못 해 판정을 내릴 수 없고, 같은 유료 측정을 다시 사야 한다**(기본값 없음 — 저장소 밖 절대경로를 직접 지정해야 한다). 1차와 같게 고정할 조건: `EASYDOC_LLM_PROVIDER=anthropic`·`EASYDOC_LLM_MODEL=claude-sonnet-5`·`EASYDOC_LLM_EFFORT=LOW`·`EASYDOC_LLM_MAX_OUTPUT_TOKENS=64000` — **2차의 요점은 1차에서 dictContext만 바꾸는 것**이므로 나머지를 함께 움직이면 결과를 무엇에 돌릴지 알 수 없게 된다(참고: 제품 `application.yml` 기본은 `provider=anthropic`, `model`·`effort`는 빈 값, `max-output-tokens=16000`이다. 상한 64,000은 모델이 실제로 내려는 출력 크기를 보려는 것이고, 제품 상한 16,000 대비 여유는 그 값으로 판단한다 — 1차가 쓴 방법과 같다). `ANTHROPIC_API_KEY` 필요.
- **2차 측정 실행(2026-09-01, 사용자 승인下).** 조건: anthropic·claude-sonnet-5·effort=LOW·`max_tokens=64000`·**dictContext=product**(제품 조립 경로) — 1차 대비 바뀐 변수는 사전 주입 하나뿐이다. 대상: 장문 4건(승인 코퍼스 밖 스테이징) 각 1회, 변환문 보존 on. 결과: 변환 성공 4/4 · **절단 문서 0/4, 호출 0/8** · 단일 호출 최대 출력 **11,468토큰**(상한 64,000, 제품 기본값 16,000에도 닿지 않았다) · 스타일 통과 **1/4(25%)**(1차는 3/4) · 품질 실패 1건(047 사실 누락 1) · 호출 13회·입력 176,496/출력 59,578 토큰(설정 단가 기준 약 $0.95)·소요 11분 42초·재시도 1회(ResourceAccessException, 회복). 문서별(원문/변환/팽창비/출력토큰): `022` 21,926/11,455/**0.52**/22,469, `023` 4,640/5,961/1.28/15,403, `047` 4,233/5,709/1.35/11,704, `050` 4,153/5,042/1.21/9,986.

  **022의 0.52는 내용 손실이 아니다 — 변환문 실물로 확인했다(1차부터 열려 있던 「절단 없음이 자체 요약의 부산물일 가능성」이 이로써 기각됐다).** required_facts 5개(1,600대·만 18세·8년·14일·250만원) 전부 변환문에 보존됐고, judge도 022를 사실 누락으로 잡지 않았다(이번 사실 누락은 047 한 건뿐). 신청자에게 불이익이 가는 실질 조건 12종(재지원제한기간·의무운행기간·보조금 환수·공동명의 대표소유주·개인사업자 사업장 요건·14일 직권취소·만 18세·지원 제외 대상·취약계층 우선순위·전기택시 특례·폐차/노후경유차·예산 소진 시 국비만)을 원문과 대조한 결과 변환문에서 0으로 사라진 항목이 없다. 변환문은 18개 절로 구조화돼 있고 금액·대수·기한을 그대로 싣으며, "차상위계층은 수급자는 아니지만 소득이 적어서…", "인감도장이란 관공서에 미리 등록해 둔 도장을…"처럼 용어를 풀어 설명한다. **0.52의 정체는 중복 제거다** — 원문이 같은 단서(※)를 여러 절에 반복하는 것을 쉬운 글이 한 번씩만 쓴다. **한계**: 이 대조는 키워드 존재 여부라 「언급됐지만 내용이 바뀐」 경우는 잡지 못하고, 원문 구조 항목 175개 전수 대조가 아니라 표본이다.

  **047의 품질 실패도 변환 결함이 아니다.** `1개월 이내` fact가 사실 누락으로 잡혔지만 변환문은 **「1개월 안에」**로 옳게 옮겨 놓았다 — 한자어 「이내」를 쉬운 말로 바꾸는 것은 이 제품이 하라고 만들어진 일이다. accept가 비어 있어 잡힌 것이며 050의 `9세 이상`→`9살 이상`과 같은 유형이다(accept 보강 별도 진행 중). **050은 이번 회차 사실 누락 0**(1차는 3건) — 오늘 교체한 `1388` 포함 5개 전부 통과했다. 다만 dictContext도 함께 바뀐 회차이므로 **facts 수정 단독의 효과로 귀속하지 않는다.** **→ 표본과 측정의 어긋남(2026-09-02):** 이 측정이 쓴 `047` 표본에는 머리에 쪽번호 오염(소속 없는 숫자 한 줄 `15`)이 있었다 — 그 오염은 이후 제거됐다(`docs/golden-collection-plan.md` 「골든 `047` — 머리 오염 제거, 본문은 유지」). 즉 위 047의 4,233자·팽창비 1.35·스타일 미통과·사실 누락 1은 **지금 커밋된 047(4,230자)이 아니라 오염이 있던 버전을 잰 값**이다. 3자 차이라 판정 자체를 바꾸지 않지만, 측정과 현재 표본이 어긋난 사실은 기록해 둔다.

- **3차 측정 실행(2026-09-03, 사용자 승인下 — 유료 호출, 사용자 터미널에서 직접 실행).** 조건: anthropic·claude-sonnet-5·effort=LOW·`max_tokens=64000`·dictContext=product — 1·2차와 같되 **승인 표본 7건(022·023·047·050·105·106·107) 각 3회 반복**으로 처음 눈금을 벗어났다(위 「확정에 필요한 것」 ⑵ 설계를 그대로 실행, `docs/master-plan.md` §9 「측정 설계」). **실행은 이 세션이 아니라 사용자 터미널에서 직접 돌았다** — 이 자동화 세션에서는 nohup 분리·harness 추적 백그라운드·`--no-daemon`·sandbox 우회를 모두 시도했지만 모든 호출이 120초 읽기 타임아웃(`ANTHROPIC_READ_TIMEOUT`)으로 실패했다. 사용자 터미널에서도 첫 실행은 같은 패턴으로 실패해(노트북 수면 전환과 겹친 구간 포함) 세션 고유의 문제가 아님이 재확인됐고, 문서 1건·1회 재확인이 성공한 뒤에야 본실행이 통과했다 — 원인은 특정되지 않았다(네트워크 경로가 간헐적으로 불안정했던 것으로 보이며, `PROXY_SERVER` 환경변수는 원인이 아니었다 — 프록시 경유는 오히려 연결 실패였다). 결과(문서 단위): 7건 중 6건은 3회 모두 성공, **022만 3회 중 2회가 `ResourceAccessException`(재시도 2회 후 포기)으로 유실돼 성공이 1회뿐**이다 — 회차 단위로는 21회 시도 중 19회 성공. 절단은 **0/7 문서·0/38 호출**(21,926자 022 포함, 상한 64,000 대비 단일 호출 최대 출력 11,320토큰=18%) — 1·2차에 이어 세 번째로 「상한만 올리면 장문이 전부 절단 실패로 떨어진다」는 구조가 재현되지 않았다. 스타일 위반 밀도 중앙값 **0.015**(위반 수/문장 수, 표본 7건) — 최고치는 107의 한 회차(17건/210문장=0.081, 전부 `DIFFICULT_WORD` — §1.3 「⑹ 판단이 필요한 것」의 규칙 결함과 일치하는 패턴). 이진 스타일 통과는 여전히 **0/7**(문서 단위, 3회 전부 통과해야 인정 — 위반 밀도로 갈아탄 이유가 그대로 재확인된다). 사실 누락 4건(023 1회·050 2회·105 1회). LLM 호출 65회·재시도 6회/예산 20(소진 아님)·입력 675,524/출력 240,194 토큰·문서 소요 합계 3,230.8초(중앙값 125.3초·최대 368.1초) — **이번 실행은 가격(pricing) 설정이 없어 리포트가 비용을 계산하지 않았다**(`estimated_cost_usd` 전부 null, 승인 예산 $4~6 범위와 별도로 정산 필요). 문서별 반복 집계(팽창비·밀도, 중앙값/최소/최대): 023 1.32/1.28/1.33·0.012/0.008/0.016, 047 1.34/1.31/1.40·0.005/0.000/0.028, 050 1.17/1.16/1.30·0.051/0.032/0.063, 105 1.05/1.02/1.16·0.015/0.013/0.031, 106 1.11/1.06/1.16·0.021/0.010/0.037, 107 1.08/1.06/1.21·0.022/0.010/0.081. **022는 성공이 1회뿐이라 반복 집계가 세 값 모두 0.28로 같다 — 사실상 n=1이다.** 그 유일한 성공 회차의 팽창비 0.28은 1·2차 값(1.20·0.52)보다 훨씬 낮아 6,169자로 21,926자 원문을 크게 압축했지만, 이 회차의 required_facts 5개는 전부 채점을 통과했다(키워드 존재 여부 표본 확인이라는 기존 한계는 그대로다).

  **§1.2 「확정에 필요한 것」에 대한 영향:** ⑵ 반복 회차는 022를 제외한 6건에서 충족됐다(각 3회 온전) — 022는 인프라 실패로 여전히 사실상 n=1이라 미충족이다. ⑴ 5,000~20,000자 밴드 표본은 이 실행으로 늘지 않았다(신규 수집 없음, 여전히 105·107 둘뿐). §1.3 「⑹ 판단이 필요한 것 — `DIFFICULT_WORD` 규칙 결함」이 미뤄 둔 「B3(위반 밀도) 측정 뒤에 다룬다」의 B3가 이 실행이다 — 그 결정은 이제 열 수 있다. **게이트 ⓪ 최종 판정은 이 기록에서 내리지 않는다.** 절단 0건·위반 밀도 대체로 낮음(0.015)은 상한값 재조정 쪽에 근거를 보태지만, 유일한 장문(022)의 반복 표본이 여전히 1건뿐이고 그 회차의 팽창비가 이전 두 회차보다 크게 낮아졌다는 것은 「그 유일한 장문에서 무슨 일이 있었는지 표본 수준으로만 안다」는 §1.2 「말할 수 없는 것」의 한계를 좁히지 못했다는 뜻이다.
- **판정 — 상한값 재조정 쪽이 유력하나 확정은 보류(2026-09-01 정정).** 2026-09-01에 사용자가 「상한값 재조정으로 끝낸다, 분할 변환은 착수하지 않는다」로 한 차례 확정한 사실은 지우지 않는다 — 다만 그 확정은 아래 두 번째 항목(스타일 통과율 붕괴·표본 부족·확인 방법의 한계)을 반영하지 않은 요약에 근거했다는 것이 같은 날 다시 확인돼, **「확정」을 「보류」로 되돌린다.**
  - **말할 수 있는 것(그대로 유효한 근거):** ⑴ 분할 변환의 동기였던 「`MAX_CONVERTIBLE_CHARS`만 올리면 장문이 전부 절단 실패로 떨어진다」는 구조가 1·2차 두 회차 모두 재현되지 않았다 — 2만 자급 문서(022)도 단일 호출 출력이 제품 상한 16,000 안에 들어왔다(11,468토큰, 상한의 72%). ⑵ 네 문서의 팽창비는 실측이다(022=0.52, 050=1.21, 023=1.28, 047=1.35). 그러나 이를 「장문일수록 팽창비가 낮다」는 추세로 부르지 않는다 — 짧은 셋(050=4,153자→1.21, 047=4,233자→1.35, 023=4,640자→1.28) 안에서는 길이와 팽창비가 단조롭지 않고, 실질적으로 길이 값이 둘(4,000자대·21,926자)뿐이며 긴 쪽 관측은 022 한 건(n=1)이다. 그래서 「상한을 올려도 출력이 비례해 커지지 않는다」는 근거로 쓰지 않는다 — 5,000~21,000자 구간은 표본이 없어 그 구간의 출력 거동은 측정된 적이 없다. 말할 수 있는 것은 **「022 한 건에서는 출력이 입력 길이에 비례해 커지지 않았다」**까지다(§1.3이 스타일 항목의 n=4 각 1회를 눈금으로 판정한 것과 같은 기준이다). ⑶ 022 변환문 실물 확인으로 022에서(키워드 수준·구조 항목 표본) 사실이 보존됨을 확인했다(위).
  - **말할 수 없는 것(그것만으로는 판정이 서지 않는 이유):** 게이트 ⓪의 판정 기준(`docs/master-plan.md` §9)은 출력 팽창비·**스타일 규칙 통과율**·절단 발생률 셋을 함께 재고, 「상한 상향만으로 처리되면 재조정, 품질이 무너지면 분할 변환」이 그 처방이다. **스타일 통과율이 2차에서 1/4(25%)로 무너졌다**(1차 3/4) — 이 기준을 충족하지 못한다. 「확정」 기록은 「스타일은 분할 변환으로 풀리지 않으니 판정 입력에서 뺀다」는 논증으로 이 기준을 우회했는데, 그것은 §9가 정한 기준이 아니라 그날 새로 도입한 논증이다 — 기준을 바꾸려면 바꾼다고 적고 재결정해야 한다(아래 §1.3 참고). 게다가 **장문 표본이 사실상 1건이다** — 측정 대상 4건의 글자 수는 `022`=21,926·`023`=4,640·`047`=4,233·`050`=4,153이며, 4,000자를 크게 넘는 것은 022 하나뿐이고 나머지 셋은 현행 상한 바로 위다. 5,000~20,000자 구간에는 표본이 전혀 없고, **그 유일한 장문 022가 이번 회차에서 스타일 미통과**였다. 022 변환문 확인도 필수 사실 5개·조건 12종의 **존재 여부**만 본 것이라 「언급됐지만 내용이 바뀐」 훼손(예: `8년`이 다른 대상에 붙는 경우)은 이 방법으로 잡히지 않고, 원문 구조 항목 175개 전수가 아니라 표본만 봤다.
  - 아래 1~5(분할 단위·마스킹 일관성·부분 실패·크레딧 환산·계약 변경 범위)는 **아직 열리지 않았다 — 판정이 확정될 때 결정한다.** 6번(새 상한값)도 판정이 서기 전까지는 착수하지 않는다. 절단이 재현되지 않았다는 것(위 「말할 수 있는 것」 ⑴)은 두 회차의 실측이며 그대로 유효하다 — 다만 그것만으로 분할 변환 불필요가 서지는 않는다는 것이 이번 정정의 요지다. **→ 확정됨(2026-09-03): 1~5는 닫힌 채로 유지하고 6번(새 상한값)만 연다** — 아래 「최종 판정」.
  - **확정에 필요한 것:** ⑴ **5,000~20,000자 구간의 장문 표본 확보** — `docs/golden-drafts/`의 나머지 후보나 `docs/golden`의 큰 PDF에서 뽑는다(§9가 이미 「1만 자 구간 표본을 더 뽑아야 할 수도 있다」고 적어 둔 그 작업). **→ 부분 충족(2026-09-02):** 장문 초안 7건을 사용자 결정으로 승인해 `data/golden/documents/`에 올렸다(기준선 56→63건) — `022`·`023`·`047`·`050`·`105`·`106`·`107`. 상세는 `docs/golden-collection-plan.md` 「승인 (2026-09-02)」. 다만 5,000~20,000자 밴드에 실제로 드는 것은 `105`·`107` 둘뿐이고(022는 21,926자로 밴드보다 위, 023·047·050은 4,000자대로 밴드보다 아래), 그중 `107`은 순서가 뒤섞인 표본이다(§1.3 「레이아웃 인지 PDF 추출」 실측 참고 — 읽기 순서 복원 미구현이 현행 유지로 결정됐으므로 `107` 표본은 그 상태 그대로 쓴다). 수집은 사용자가 병행 계속한다. ⑵ **반복 회차** — 지금은 1·2차 각 1회라 눈금이다. **→ 설계됨(결정 2026-09-02):** 승인 표본 각 3회 반복, 조건은 제품 구성(anthropic·claude-sonnet-5·dictContext=product·`max_tokens=64000`) — 상세는 `docs/master-plan.md` §9 「게이트 ⓪」 「측정 설계」. **→ 실행됨(2026-09-03, 3차 측정)** — 위 「3차 측정 실행」 참고. 022를 제외한 6건은 충족, 022는 인프라 실패로 사실상 n=1 유지. ⑶ ~~**스타일 통과율을 판정 입력에서 어떻게 다룰지 명시적 재결정**~~ — **→ 결정됨(2026-09-02, PR #12 Codex 승인 뒤 확정):** 이진 통과율을 **위반 밀도(문장당 스타일 위반 수)**로 대체한다. 「분할로 풀리지 않으므로 판정 입력에서 뺀다」가 타당한 논증일 수는 있으나, 그것은 기록으로 남겨 §9 판정 기준을 고치는 결정이지 슬쩍 적용할 것이 아니다. **아래 §1.3 「사전 컨텍스트 주입과 스타일 통과율」의 2026-09-02 조사 결과가 이 재결정에 실질적 근거를 준다** — `checkStyle`이 문서 단위 all-or-nothing이라 통과율 자체가 길이에 편향된 지표이며, 이진 통과율 대신 위반 밀도로 읽으면 「분량 페널티」와 「진짜 품질 문제」가 갈린다. **「무너짐」 임계값은 이 결정에 포함되지 않는다 — 측정 뒤에 정한다.** 다만 이 조사는 근거를 보탤 뿐 그 자체가 재결정은 아니었다 — 재결정은 위 사용자 확정으로 별도로 이뤄졌다.
  - **최종 판정(2026-09-03, 사용자 확정): 상한값 재조정으로 확정한다 — 분할 변환은 착수하지 않는다.** 근거는 위 「말할 수 있는 것」과 3차 측정이다: 세 회차 모두 절단 0건(3차 0/7 문서·0/38 호출, 21,926자 022 포함, 단일 호출 최대 출력 11,320토큰=상한 64,000의 18%), 3차 위반 밀도 중앙값 0.015, 022 변환문 실물 확인(키워드 존재 여부 표본 수준). 위 「확정에 필요한 것」 ⑴(5,000~20,000자 밴드 표본은 105·107 둘뿐)과 ⑵(022 반복은 인프라 실패로 사실상 n=1)는 **완전히 충족되지 않은 채 내린 결정**이며, 「말할 수 없는 것」의 한계는 그대로 기록으로 남긴다 — 특히 022 유일 성공 회차의 팽창비 0.28이 1·2차(1.20·0.52)보다 크게 낮았던 이유는 알지 못한다. 판정이 서면서: 아래 「판단이 필요한 것」 1~5는 분할 갈래이므로 닫힌 채로 유지하고, **6번(새 상한값)만 연다.** 새 상한값의 변경 단위는 §9 3번대로 `max_convertible_chars`·`max_review_chars` 동시 변경이고, 범위는 5번 목록이 그대로 적용된다 — 5번은 분할 전용이 아니라 상한 변경의 공통 범위다: `MAX_CONVERTIBLE_CHARS`(`core/document/DocumentLimits.kt:15`, 코드 상수 4_000 — 검수 상한도 같은 상수를 재사용한다), 계약 `x-input-limits`(`contracts/easy-doc-v1.yaml:560-561`)와 422 detail 문자열, Kotlin 계약 테스트, `frontend/src/api/`와 업로드 화면 안내(4,000자 카운터·경고문). **새 상한값 숫자는 이 기록에서 정하지 않는다.** **→ 새 상한값 20,000자로 결정(2026-09-03, 사용자) — 계약 2.7.0으로 구현. 022(21,926자)는 상한 위라 제품 경로로는 거절되고 레인용 골든으로만 남는다.**

아래는 **분할 변환으로 가기로 판정된 경우에만** 답해야 하는 것들이다. **→ 위 판정은 아직 확정되지 않았다(2026-09-01 정정) — 이 갈래는 아직 열리지 않았고, 판정이 확정될 때 다시 연다. 내용은 그대로 기록으로 남긴다.** **→ 2026-09-03 판정이 상한값 재조정으로 확정됐으므로 이 갈래(1~5)는 열리지 않는다. 6번과 5번의 변경 범위 목록만 상한값 재조정 작업에 쓴다.**

**상한이 걸려 있는 자리는 셋이다.** `MAX_CONVERTIBLE_CHARS`(`core/document/DocumentLimits.kt`)가 업로드를, 계약 `x-input-limits.max_review_chars`가 검수 수정본을 각각 4,000자로 막고, `DEFAULT_MAX_TOKENS`(`core/llm/LlmProvider.kt:7`)가 출력을 16,000토큰으로 막는다. 첫째만 풀면 1만 자 문서를 변환해 놓고 **검수본을 저장할 수 없고**, 셋째를 안 풀면 **변환 자체가 절단 실패로 끝난다** — 셋은 같은 변경 단위다. 추출기 상한(`MAX_EXTRACTED_CHARS` = 500,000)은 별개이며 지금도 훨씬 넓다. **→ 2026-09-03: 셋을 한 변경 단위로 바꿨다 — 업로드·검수 상한 20,000자, 출력 토큰 구성 기본값(`easydoc.llm.max-output-tokens`) 64,000 — 코드 fallback `DEFAULT_MAX_TOKENS`는 16,000 유지(OpenAI gpt-4.1 출력 한도 32,768 안의 provider 안전값), 계약 2.7.0.**

**다만 이 여유는 실문서 기준으로는 이미 좁다(2026-09-02 실측).** `docs/golden/` 공개문서 13건을 제품 추출기로 전수 추출한 결과 **2건이 이 상한을 넘겨 추출 자체가 `DocumentExtractionException`으로 끝났고**(청소년사업 안내 2권·노인실태조사 보고서 — 부분 텍스트도 남지 않는다), 4건이 322,182~489,609자로 상한 근처였다(국민기초생활보장 489,609자는 턱밑). 위 문장을 지우지는 않는다 — 4,000~322,182자 구간에서는 지금도 훨씬 넓지만, 게이트 ⓪이 다루는 4,000자 변환 상한보다 훨씬 위에 **또 하나의 벽**이 있고 실문서가 이미 그 벽에 닿아 있다는 뜻이다(자세한 전수 결과는 `docs/golden-collection-plan.md` 「초안 감사 기록 (2026-09-02)」).

판단이 필요한 것:

1. **분할 단위.** 문단 경계로 자르는 것이 기본이지만, 표·목록·머리글이 경계에 걸릴 때 무엇을 한 조각으로 볼지 정해야 한다. 4단계에서 만든 원본 구조 보존 내보내기가 문단 id 대응에 기대고 있으므로(`d8bfd03`, `48b5643`), 분할이 그 대응을 깨지 않아야 한다.
2. **마스킹 플레이스홀더의 조각 간 일관성.** 마스킹은 LLM 호출 **전에** 끝나야 한다는 것이 타입 경계로 강제돼 있다. 같은 주민등록번호가 두 조각에 나오면 같은 플레이스홀더를 받아야 하고, 원문-플레이스홀더 대응표는 문서 하나로 합쳐져야 한다.
3. **부분 실패.** 조각 5개 중 3번째만 실패하면 무엇을 사용자에게 보이는가. 지금 `conversions.status`는 문서 단위 4값(`pending`·`processing`·`done`·`failed`)뿐이라 "일부 완료"를 표현할 자리가 없다. 상태를 늘릴지, 조각을 전부 성공해야만 `done`으로 볼지 정해야 한다. **재시도 책임은 한 계층만 갖는다**(CLAUDE.md) — 조각 재시도를 큐와 유스케이스가 동시에 하지 않게 한다.
4. **크레딧 환산.** 1,000자 = 1크레딧(master-plan 4.1 P0-7)은 문서 단위 환산이다. 분할하면 조각마다 프롬프트가 다시 실려 실제 토큰은 글자 수에 비례하지 않는다(2026-08-27 실측: 668자 문서의 입력이 6,695토큰이었다 — 대부분이 프롬프트다). 환산을 글자 수 기준으로 유지할지, 조각 수를 반영할지 정해야 한다.
5. **계약 변경 범위.** 상한을 바꾸면 `contracts/easy-doc-v1.yaml`의 `x-input-limits`, 422 detail 문자열, Kotlin 계약 테스트, `frontend/src/api/`와 업로드 화면 안내가 같은 변경 단위다.
6. **새 상한값.** 무제한이 아니라 새 숫자를 정한다. 근거는 골든셋 LLM 평가의 출력 팽창비다(master-plan §3.2가 이미 그 방법을 정해 두었다). **→ 결정됨(2026-09-03): 20,000자.** 근거는 3회차 실측 최대 022(21,926자)까지 절단 0건 — 실측 범위 안의 값이며 22,000자 초과는 측정이 없어 약속하지 않는다.

**절단 방지 규칙은 그대로 유효하다** — 조각 하나라도 토큰 한도에서 잘리면 그 변환은 실패이며 사용자에게 내보내지 않는다(master-plan §3.2).

## 1.3 변환 개선 — 경쟁사 유료 샘플 분석 (2026-08-31 추가)

`docs/유료_샘플/`의 두 PDF를 분석했다: 디자인 완성 브로슈어(「이해하기 쉬운 안산시장애인복지관 기관 소개서」, 본문 약 3,267자 — 현 상한 안)와, 그것을 읽고 사람이 만든 재디자인용 개정 원고. 수정본의 변경 중 상당수는 원본에서 도출 불가능한 사실 갱신(기관 협의 산물)이라 변환 제품의 비교 대상이 아니다. 아래는 그 분석에서 **우리 파이프라인이 닫을 수 있는 것**만 남긴 것이다.

### 핵심 기능 2건 (사용자 확정, 2026-08-31)

1. **레이아웃 인지 PDF 추출.** `PdfExtractor`의 `PDFTextStripper`는 읽기 순서를 모른다. 이 샘플의 2단 카드 레이아웃에서 층별 안내 표가 쪼개지고 나란한 두 정보 박스가 줄 단위로 교차 추출되는 것을 실측으로 확인했다(레이아웃 비인지 추출의 공통 현상 — pdftotext도 동일하게 뒤섞였다). 뒤섞인 입력은 LLM이 복원을 보증할 수 없다. 후보는 PDFBox 정렬 모드·영역 검출·외부 라이브러리이며 선택은 실측으로 한다. **두 번째 실측 사례(2026-09-02, 골든 초안 `107`).** 디자인 레이아웃이 아닌 평범한 채용공고 PDF(한국직업능력연구원 청년인턴 채용 공고, 표는 있으나 2단·카드 레이아웃은 아니다)에서도 같은 유형의 결함이 재현됐다 — 쪽번호(`- 1 -`)가 제목보다 앞서 나오고, 3~4쪽 표 셀이 뒤로 밀려나면서 문서의 실제 마지막 문장이 전체 231줄 중 219번째 줄(끝에서 12줄 앞)로 옮겨 오며, 가점표 셀 하나가 다른 표 뒤로 밀리면서 한 문장이 113번째 줄에서 끊겨 150번째 줄에서 이어진다(자세한 대조는 `docs/golden-collection-plan.md`의 「3차 수집분」 「신규 초안 `107`」). 디자인 브로슈어 한 건에서만 관측되던 결함이 표 위주의 평범한 공고문에서도 재현됐다는 점에서, 이 항목은 **디자인 PDF에 국한된 결함이 아니라 표를 포함한 PDF 전반의 문제일 가능성이 커졌고, 그만큼 우선순위 비중을 올려야 한다.**

**후보 실측(2026-09-02).** 위 두 문서(채용공고 PDF·기관 소개서 PDF)에 대해 C0(제품, `PDFTextStripper` sortByPosition=false) / C1(같은 stripper, sortByPosition=true) / C2·C3(`pdftotext` 기본·`-layout`, 참고용 — 외부 바이너리라 JVM 후보 아님) / C4(recursive XY-cut 스파이크, 깊이·gap 캡 있음)를 실측했다.

| 조건 | 문서1(채용공고) `끝.` 거리 / `5·18`↔`유족` 간격 | 문서2(소개서) 층별 안내 표 | 문서2 좌우 정보 상자 |
|---|---|---|---|
| C0 (기준) | 12줄 / 37줄 | 라벨 5개가 먼저 뭉치고 내용이 따로 뭉침(분리) | 상자 단위로는 붙어 있음 |
| C1 sortByPosition=true | **1줄 / 2줄**(해소) | 라벨·내용·다른 블록이 뒤섞임 | **줄 단위로 심하게 교차**(2026-08 spike 결과 이 문서로도 확인됨) |
| C2 pdftotext 기본(참고) | 3줄 / 3줄 | 라벨+첫 줄만 붙고 둘째 줄은 표 끝으로 밀림 | 미측정 |
| C3 pdftotext -layout(참고) | 4줄 / 4줄 | 라벨+전체 내용이 거의 완전히 붙음(정렬 공백으로 글자수 +53~167%) | 상자 2개가 나란히 보존됨(`지하` 행은 무관한 문장과 합쳐짐) |
| C4 XY-cut 스파이크 | **51줄 / 145줄(둘 다 악화)**, 제목이 61번째 줄로 밀리고 쪽번호가 한 글자씩 세 줄로 쪼개짐 | 라벨·내용 페어링은 개선되나 순서가 역전(1층→4층) | 상자 순서 역전, 쪽번호가 최상단으로 튐 |

`sortByPosition=true`는 문서 의존적이다 — 표 위주 단일 단 문서(채용공고)의 절단·재배치 결함은 사실상 없애지만, 2단 카드 레이아웃(기관 소개서)에서는 줄 단위 교차가 그대로 재현된다. **2026-08 spike의 "다단 PDF에서 줄 순서가 갈린다"는 결론은 이 실제 문서로도 확인됐다** — `PdfExtractor`의 `sortByPosition=false` 고정을 뒤집을 근거는 아직 없다. `pdftotext -layout`은 두 문서 모두에서 표 행 페어링을 가장 잘 보존했지만 외부 바이너리라 채택 대상이 아니며, "가로 위치가 겹치는 텍스트를 같은 행으로 묶는" 접근이 표에 유효할 수 있다는 데이터 포인트로만 남긴다. recursive XY-cut 스파이크는 이번 캡(깊이 6, gap 20pt/3pt) 안에서는 채용공고 문서의 모든 측정 지표를 기준보다 악화시켰고 헤더·푸터를 구분하지 못해 새 결함(쪽번호 글자 단위 분할, 제목 위치 이동)까지 냈다 — 부정 결과다. 결론: 이번 실측 범위에서 **두 문서 유형(표 위주 단일 단 vs. 다단 카드)에 공통으로 통하는 단일 설정은 없다.** 문서 유형별 분기 없이는 어느 후보를 골라도 한쪽 문서를 반드시 악화시킨다.

   **결정(2026-09-02, PR #12 Codex 승인 뒤 확정): 현행 유지.** `sortByPosition=false`를 그대로 두고 문서 유형 분기는 만들지 않는다. 근거는 위 실측 그대로다 — 어떤 단일 설정도 두 문서 유형(표 위주 단일 단 vs. 다단 카드) 모두에 통하지 않고, 분기를 만들려면 그 자체가 아직 측정되지 않은 컬럼(단) 검출이 선행돼야 한다. **결과가 그대로 남기는 것:** `107` 유형(표 위주 PDF)의 추출 순서는 제품에서 계속 뒤섞인 채로 나온다 — 게이트 ⓪ 측정에 `107`을 표본으로 쓸 때는 이 상태를 전제로 읽는다(§1.2 「확정에 필요한 것」 ⑴ 참고).
2. **PDF 원본의 파일 내보내기 경로.** `ExportFormat.ofSource(PDF) = null`이 의도적으로 막혀 있다(렌더러 없음, `DESIGN.md` §6.5 「우회 다운로드 금지」). **방향(사용자 결정, 2026-08-31): 라이브러리 조사가 선행이다.** PDF 생성은 잘 구현된 무료 JVM 라이브러리가 드물다 — iText는 AGPL·상용 이중 라이선스라 SaaS에 부담이고, PDFBox(Apache-2.0)는 생성이 저수준이라 레이아웃 엔진이 없다. 조사 후보: OpenPDF(LGPL/MPL), openhtmltopdf(Apache-2.0, HTML/CSS→PDF), Apache FOP(Apache-2.0, XSL-FO). 한글 폰트 임베딩과 표 레이아웃까지 실측으로 검증한 뒤 판정한다. **렌더러가 어렵다고 판정되면 PDF 렌더러 없이 간다**: PDF 입력 건을 TXT 또는 사용자가 고르는 DOCX·HWPX **신문서 조립**으로 내보낸다. 단, 이 폴백은 현재 런타임 경로에 그대로 얹히지 않는다 — 오늘 PDF 건의 export는 `ConversionExportService.agreedFormat`에서 `ofSource(PDF) = null`로 **409**가 나고, 신문서 조립 갈래(`rendering.exporter`)는 「원본이 없는 문서(붙여넣기)」에서만 돌며, PDF 건은 원본 바이트가 있어도 반영기가 없으므로 **소스 형식 기준의 새 분기**로 조립 갈래를 태워야 한다. 형식도 오늘은 선택이 아니다 — 계약 `x-export-format-derivation`이 「형식은 서버가 정한다」이고 요청 형식은 주장일 뿐이라(불일치 409), 「사용자가 고른다」를 열려면 그 enforcement의 재결정과 `agreedFormat`, 조회 응답의 `export_format`(`ConversionQueryService` — 같은 `ofSource`를 쓴다)이 계약 문구·Kotlin 계약 테스트·프런트 안내와 함께 같은 변경 단위다. §6.5의 「텍스트 대체 금지」는 **조용한** 대체가 금지 사유였으므로, 명시적 선택으로 여는 것은 그 조항의 재결정으로 기록한다. 조립기 자체는 재사용한다(`PackagedDocumentExporter` — TXT·DOCX·HWPX). 다만 현재 조립은 제목+평문 본문뿐이라 **제목·표·목록 레이아웃을 살려 조립하도록 작성기를 확장하는 것이 요구사항이다** — 아래 「표·목록 구조 보존」과 같은 변경 단위가 된다.

   **라이브러리 조사·실측(2026-09-02).** 후보 4종을 Maven Central 메타데이터·GitHub로 조사했다: PDFBox 3.x(baseline, Apache-2.0, `3.0.8`/2026-07-15, 저수준 프로그래밍 API·레이아웃 엔진 없음) / OpenPDF(LGPL-2.1+MPL-2.0 듀얼, `3.0.5`/Central 마지막 갱신 2026-05-22, 활발, iText4 계열 프로그래밍 API — HTML 입력 아님) / **openhtmltopdf 유지보수 fork**(LGPL-2.1-or-later — **정정:** 위 8-31 문장의 「openhtmltopdf(Apache-2.0)」은 오기였다, Flying Saucer 계열이라 원본·fork 모두 LGPL이다; `io.github.openhtmltopdf:openhtmltopdf-pdfbox:1.1.85`/Central `lastUpdated` 2026-09-01, **매우 활발** — 원본 `danfickle/openhtmltopdf`는 아카이브됐고 이 groupId가 그 뒤를 이어 거의 매일 패치를 낸다, HTML/CSS→PDF, PDFBox 3.x 기반) / Apache FOP(Apache-2.0, `2.11`/2025-05-06로 16개월째 무갱신, XSL-FO 입력이라 별도 변환 단계 필요). openhtmltopdf가 유지보수 신호와 입력 모델(우리 변환 출력과 결합하기 쉬운 HTML/CSS) 양쪽에서 가장 유리해 1순위로 판단, 단독 Gradle/Kotlin 프로젝트로 스파이크했다(OpenPDF·FOP는 이번 조사에서 스파이크하지 않음 — 한글 임베딩·표 헤더 반복 미확인).
   스파이크 결과: 한글 H1·문단 4개(각~300자)·불릿 3개·헤더 1행+본문 6행 표(숫자 컬럼 포함, 페이지 경계를 걸치도록 구성)를 렌더링. **지시된 `AppleGothic.ttf`/`AppleMyungjo.ttf`(macOS 번들 클래식 한글 폰트)는 임베딩에 실패했다** — 정확한 예외는 `java.io.IOException: os2 table is missing in font AppleGothic`(OS/2 메트릭 테이블이 없는 구형 Mac TrueType이라 PDFBox 3.x 폰트 로더가 거부, 라이브러리 결함이 아니라 폰트 파일의 한계로 판단) — 반면 현대적 TTF인 `NanumGothic.ttf`로는 정상 임베딩됐다. 임베딩 성공 건 기준 `pdftotext -enc UTF-8` 검증: 한글 음절 1068자 입력 대 1068자 출력으로 완전 일치, tofu/`?` 0건. 표는 2쪽에 걸쳐 1~3행/4~6행으로 정확히 분할되고 행 순서가 보존됐으나, **`<thead>{display:table-header-group}` 표준 CSS만으로는 2쪽에 헤더 행이 반복되지 않았다**(GitHub 이슈 `danfickle/openhtmltopdf` #229·#457이 보고하는 것과 같은 현상의 실측 재현). **재측정(2026-09-02):** 같은 표에 openhtmltopdf 고유 확장 속성 `-fs-table-paginate: paginate;`를 추가하자 2쪽 첫 줄에 헤더 행("순번/신청 구분/담당 부서/처리 건수")이 그대로 반복됐다 — `pdftotext`에서 "순번" 매치가 1쪽·2쪽 각 1회씩 총 2회로 늘었고, 반복된 헤더 텍스트만큼(음절 14자) 전체 한글 음절 수도 1068→1082로 늘어 반복이 실제로 렌더링됐음을 뒷받침한다. 즉 이것은 라이브러리 결함이 아니라 표준 CSS만으로는 안 되고 이 벤더 속성을 명시해야 동작하는 openhtmltopdf 고유 동작이었다(공식 문서: `https://github.com/danfickle/openhtmltopdf/wiki/Page-features`, 확인일 2026-09-02). `tfoot` 반복 여부는 이번 테스트 문서에 `tfoot`이 없어 미확인이다. 산출 PDF는 (헤더 반복 없음) 58,531바이트 / (헤더 반복 있음) 58,850바이트. `gradle dependencies`로 확인한 런타임 의존성은 openhtmltopdf-core/-pdfbox, PDFBox/fontbox/pdfbox-io/xmpbox 3.0.7, commons-logging 1.3.5, graphics2d(de.rototor.pdfbox) 3.0.1의 8개 jar·총 5.36MB로, 전 계열이 Apache-2.0/LGPL이라 iText·AGPL 오염이 트리에 없다.
   **판정 재료(판정 자체 아님):** openhtmltopdf 유지보수 fork는 유지보수 신호·의존성 라이선스·한글 임베딩·표 페이지 분할(행 순서 보존)·표 헤더 반복 모두 실측으로 통과했다 — 단, 헤더 반복은 표준 CSS(`display:table-header-group`)만으로는 안 되고 벤더 확장 속성 `-fs-table-paginate: paginate` 한 줄을 별도로 명시해야 동작한다는 제약이 실측으로 확인됐다(결함이 아니라 설정 누락이었다). 폰트 자산도 클래식 macOS 시스템 폰트가 아니라 현대적 TTF(예: 나눔고딕)로 별도 조달해야 한다는 제약이 함께 드러났다. OpenPDF·Apache FOP는 이번 조사에서 스파이크하지 않아 같은 항목(한글 임베딩·표 헤더 반복)이 미확인이다.

   **제약 해소 실측(2026-09-02).** 제약 1(벤더 CSS)은 사소하다 — `table { -fs-table-paginate: paginate; }`를 변환기(exporter) 자체 스타일시트의 기본값으로 넣으면 되고, 사용자 원문서가 이 속성을 알 필요는 없다.
   제약 2(폰트 자산)는 실측이 필요했다. 후보는 NanumGothic Regular/Bold(OFL-1.1, RFN 있음 — 이름 변경 재배포만 제한, 무수정 임베딩은 허용)와 Noto Sans KR(OFL-1.1, RFN 없음), Noto Sans Symbols 2(OFL-1.1, RFN 없음)다. 출처는 `~/Library/Fonts`가 아니라 `github.com/google/fonts`(각 폰트의 `upstream_info.md`가 원본을 NanumGothic은 `hangeul.naver.com`/Sandoll 제작으로, Noto Sans KR은 `github.com/notofonts/noto-cjk` `Sans2.004`로, Noto Sans Symbols 2는 `github.com/notofonts/symbols` v2.008로 문서화한다 — `github.com/naver/nanumfont`는 확인해보니 releases에 코딩 전용 변형만 있고 일반 NanumGothic 바이너리가 없다)에서 받았다. **새로 드러난 결함:** Noto Sans KR의 공식 정적 배포(`NotoSansKR-Regular.otf`, CFF 윤곽)는 `java.io.IOException: True Type fonts using CFF outlines are not supported`로 openhtmltopdf에 임베딩되지 않는다 — PDFBox 3.x 자체는 CFF OTF 임베딩을 지원하지만 openhtmltopdf의 폰트 로딩 경로가 이를 지원하지 않는다(openhtmltopdf 우호 평가에 추가할 결점). 대안으로 Google Fonts가 배포하는 가변(variable) TTF(`glyf`+`gvar` 윤곽, 9.93MB)는 정상 임베딩된다.
   글리프 커버리지는 `data/golden/documents/*.json`·`docs/golden-drafts/*.json`(83개 파일 `source_text`) 전체에서 ASCII·한글 음절을 뺀 고유 문자 123종(누적 4,306회, 전체 172,804자 중)을 fontbox cmap 조회로 실측했다: NanumGothic 단독 72/123 미포함(1,082회, 25.1%), Noto Sans KR 단독 24/123(451회, 10.5%), Noto Sans Symbols 2 단독 87/123(2,778회 — 심볼 전용이라 예상된 결과), **세 폰트 폴백 체인(`font-family: 'NanumGothic', 'NotoSansKR', 'NotoSansSymbols2'`) 합집합 17/123(271회, 전체 말뭉치의 0.16%)**. 남는 17종 중 ~~11종(259회)~~ **정정(2026-09-02, Codex 정지 시점 리뷰가 산술 불일치를 지적해 재확인): 13종(187회)**은 원본 PDF의 딩벳 폰트가 남긴 사용자 영역(PUA, `U+E000–F8FF`·`U+F0000–FFFFD`) 코드포인트라 렌더러 선택과 무관하고, 진짜 갭은 4종·84회(온점 리더 U+2024 65회, 하이픈 불릿 U+2043 10회, 물결 연산자 U+223C 8회, 수식 아포스트로피 U+02BC 1회)뿐이다 — PUA 13종/187회 + 진짜 갭 4종/84회 = 17종/271회로 합집합 총계와 정확히 일치한다(전체 목록·재현 가능한 실측 출력은 스크래치 `RESULT.md`/`coverage-full.txt`).
   골든 초안 105·107 전문(각 6,712자·5,599자)을 실제 렌더링해 `pdftotext`로 왕복 검증한 결과 openhtmltopdf의 결측 글리프 처리 방식이 세 가지로 갈렸다: ⑴ 폰트가 글리프를 아예 못 가진 경우 화면에 보이는 `#`로 대체된다(빈칸·두부박스·`?` 아님 — `pdftoppm` 이미지로 시각 확인, NanumGothic 단독 렌더에서 105는 38회·107은 52회 `#` 출현). ⑵ **폰트가 글리프를 갖고 화면에는 정확히 렌더링되는데(➊➋➌➍ 딩벳을 이미지로 확인) `pdftotext` 텍스트 추출에서는 빠지는 경우**를 Noto Sans KR 가변 폰트에서 발견했다 — 시각 결함이 아니라 텍스트 계층(복사·스크린리더·전문검색) 추출 결함으로, 근본 원인은 이번 조사에서 규명하지 못했다(→ 같은 날 아래 「텍스트 계층 누락 규명」에서 규명됨 — 누락이 아니라 U+2776 계열로의 치환). ⑶ 소프트 하이픈(U+00AD) 같은 원래 안 보여야 하는 서식 문자는 정상적으로 빠진다. **폴백 체인으로 105·107을 렌더링하면 두 문서 모두 `#` 치환이 0건이다** — 실제 공고문 두 건에서는 눈에 보이는 글리프 결손이 없었다.
   H1 볼드는 Regular 파일만 등록해도 openhtmltopdf가 가짜(faux) 볼드를 합성해 시각적으로 티가 나지 않는다(`pdffonts`로 서브셋 1개만 임베딩됨을 확인, `pdftoppm` 이미지 비교로도 진짜 Bold 파일 등록본과 구별이 어려움) — Bold 자산(NanumGothic Bold, 1.98MB)은 필수가 아니라 선택이다.
   **판정 재료로 정리:** 권장 폴백 체인(NanumGothic Regular + Noto Sans KR 가변 TTF + Noto Sans Symbols 2, Bold 제외)은 원본 자산 기준 13.16MB(라이브러리 의존성 5.36MB와 별개)이고, 실제 PDF에는 문서별 서브셋만 실리므로 훨씬 작다(105·107 렌더 기준 76~77KB). 커버하지 못하는 것은 전체 말뭉치의 0.16%(대부분 PUA 잔재)와, ~~근본 원인 미규명 상태인~~(→ 아래 「텍스트 계층 누락 규명」으로 규명됨)  가변 폰트 특정 글리프의 텍스트 추출 결함(눈에는 보이나 복사·검색에서 빠짐)이다. 이 두 가지가 판정에 남는 미해결 조건이다.

   **텍스트 계층 누락 규명(2026-09-02).** 위 두 문단이 "근본 원인 미규명"이라고 적어 둔 ➊➋➌➍(U+278A–278D) 텍스트 추출 결함을 소스까지 추적해 확정했다. **결론: 「누락」이 아니라 「치환」이었다** — 텍스트 계층에 문자가 없는 게 아니라 시각적으로 구별되지 않는 다른 코드포인트(U+2776–2779, ❶❷❸❹)로 들어가 있었다. 최소 재현 HTML(`한글단어 ➊➋➌➍`)로 변수를 하나씩 격리한 결과: 체인에서 이 글자를 그린 것은 항상 Noto Sans KR이고(Noto Sans Symbols 2는 이 글리프 자체가 없다, fontTools 확인), poppler `pdftotext`(`-raw` 포함)와 PDFBox 자체 `PDFTextStripper` 추출이 동일하게 U+2776을 보고했으며(poppler 고유 문제 아님), `fonttools varLib.instancer`로 만든 정적 인스턴스(wght=400, 6.22MB)로 다시 렌더링해도 같은 치환이 재현됐다(가변 폰트 특유의 문제 아님) — 코드포인트도 둘 다 BMP 안에 있고 cmap format 4·12 양쪽에서 동일하게 풀린다(서브테이블 포맷 문제 아님). 근본 원인은 PDFBox 3.0.7 소스(`pdfbox/src/main/java/org/apache/pdfbox/pdmodel/font/PDCIDFontType2Embedder.java` 155행 부근, `github.com/apache/pdfbox` `3.0.7` 태그, 확인일 2026-09-02)에 있다: NotoSansKR 폰트가 U+2776과 U+278A를 같은 글리프(글리프명 `uni2776`)로 공유해 두었는데, `buildToUnicodeCMap()`이 임베딩되는 각 글리프에 대해 `cmapLookup.getCharCodes(cid)`로 그 글리프가 매핑되는 **모든** 코드포인트를 역조회한 뒤 소스 주석 그대로 **"use the first entry even for ambiguous mappings"** 정책으로 `codes.get(0)`만 ToUnicode에 쓴다. 그 리스트는 fontbox `CmapSubtable.getCharCodes()`(`fontbox/src/main/java/org/apache/fontbox/ttf/CmapSubtable.java` 669행 부근)가 `Collections.sort(codes)`로 오름차순 정렬해 반환하므로 결과는 항상 그 글리프를 공유하는 코드포인트 중 수치가 가장 작은 쪽이다(U+2776 < U+278A). 즉 openhtmltopdf가 무엇을 그리라고 요청했는지와 무관하게 PDFBox의 서브셋 임베더가 GID→유니코드 역조회만으로 ToUnicode를 재구성하며 원래 요청 코드포인트를 버리는 것이 원인이다 — 이는 PDFBox가 알고 있는 문서화된 단순화이지 숨은 버그가 아니며, 같은 결함 계열이 `github.com/typst/typst` 이슈 #4582("`/ToUnicode` in PDF can be wrong if a glyph is mapped from multiple code points", 확인일 2026-09-02)에도 보고돼 있어 openhtmltopdf 고유 결함이 아니라 CID 서브셋 임베딩을 쓰는 PDFBox 3.x 기반 렌더러 전반의 일반적 한계로 판단한다. **반증 조건(미확인):** PDFBox 3.0.8 이상에서 이 로직이 바뀌었는지, 또는 글리프를 공유하지 않는 다른 한글 폰트에서도 같은 치환이 재현되는지는 이번 조사에서 확인하지 않았다. **수정 후보:** 정적 인스턴싱은 해결책이 아님이 이미 반증됐다(축 3에서 재현됨) — 이번 조사 범위에서 검증된 해결책은 없다(PDFBox 업스트림 수정 대기, 글리프 중복 없는 폰트로 교체, 또는 ToUnicode 후처리 보정이 남은 완화 경로이나 셋 다 미검증). **접근성 결론:** 시각적으로는 문제없지만(사람 눈에는 ➊과 ❶이 동일) 복사·붙여넣기·전문검색·스크린리더가 읽는 텍스트는 원문과 다른(의미상 근접한) 코드포인트를 얻으며, 원문 대비 문자 단위로 정확히 대조하는 파이프라인에서는 이 치환이 실제 결함으로 잡힐 수 있다. 전체 격리 표와 재현 절차는 스크래치 `RESULT.md` Part 4·`src/main/kotlin/IsolateGlyph.kt`·`DumpToUnicode.kt`에 있다.

   **결정(2026-09-02, PR #12 Codex 승인 뒤 확정): PDF 렌더러는 도입하지 않는다.** 위 조사가 남긴 한계 — PDFBox 3.x 기반 CID 서브셋 임베딩의 ToUnicode 치환(위 「텍스트 계층 누락 규명」) 등 — 을 인정하고 이 항목을 닫는다. **대신 PDF 원본 문서는 사용자가 DOCX 또는 HWPX 중 하나를 골라 신문서 조립으로 내보낸다** — 이 항목이 처음부터 적어 둔 폴백("렌더러가 어렵다고 판정되면 …")이 그대로 트리거된 것이며, 새로운 방향이 아니다. 이 결정이 여는 변경 단위도 항목이 이미 적어 둔 그대로다: `DESIGN.md` §6.5 「텍스트 대체 금지」 재결정(사용자가 명시적으로 고르는 것은 그 조항이 금지한 **조용한** 대체가 아니다), 계약 `x-export-format-derivation`(서버가 정한다 → PDF 소스는 사용자가 고른다로 변경), `ConversionExportService.agreedFormat`, `ConversionQueryService`의 `export_format`(같은 `ofSource`를 쓴다), Kotlin 계약 테스트, 프런트 형식 선택 패널. ~~**구현은 다음 순서로 예정돼 있다 — 아직 하지 않았다.**~~ **→ 구현됨(2026-09-02, 같은 날 — 계약 2.6.0 `choices`/`export_format_choices`, `ExportFormat.choicesFor`, `ConversionExportService`의 타입화된 `reflectOriginal` 경로, `ReviewEditor`의 선택지 버튼).** 제목·표·목록 구조 보존 확장은 그대로 후속이다. 위 「제목·표·목록 구조를 살려 조립하도록 작성기를 확장하는 것」은 이 결정 단위에 포함되지 않는다 — 후속 작업으로 남긴다(현재 조립기는 제목+평문 본문만 낸다). **라이선스 메모:** 렌더러를 채택하지 않으므로 openhtmltopdf(LGPL-2.1-or-later)의 라이선스 적합성 질문은 더 이상 다루지 않는다.

### 변환 품질 개선

- **사실 보존의 기계 검증(숫자·연락처·조건 문구).** 현재 `checkStyle`(`core/easyread/StyleRules.kt`)은 문장 길이·쉼표 수·이중 피동·어려운 표현·치환 비문만 본다 — **표적 보정을 트리거하는 기계 검출에 사실 훼손이 없다.** 이 샘플류 안내문은 전화번호·시각·가격·연령·자격 조건("\*활동지원 등급을 받은 사람만")이 본문 가치의 대부분이다. 변환 전후 숫자·연락처 집합 비교는 싸고 결정적이며, 위반을 표적 보정 1회의 트리거로 넣을 수 있다. `testLlm`의 충실성 평가(유료·opt-in)와 달리 런타임에 항상 도는 검사라는 점이 핵심이다.
  **→ 구현됨(2026-09-04):** `core/easyread/FactPreservation.kt`의 `findMissingFacts`가 숫자·전화번호·시각·날짜·금액·백분율·이메일/URL을 규칙 기반으로 추출해 원문·변환문을 비교하고, `ConvertDocumentUseCase.finish`가 `checkStyle` 위반과 나란히 표적 보정 1회의 트리거로 쓴다(호출 상한은 그대로 최대 2회). 날짜·시각은 문자열이 아니라 구성요소(연·월·일, 자정 기준 분)로 비교해 `2026.09.04`↔`2026년 9월 4일`·`오후 3시`↔`15시` 같은 표기 차이를 오탐하지 않는다. 한글 수사는 **1~10(고유어·한자어)과 천/만/억 배수만** 등가로 인정한다(`3개월`↔`세 달`, `1,000원`↔`천 원`) — **11 이상의 합성 수사(예: `12명`↔`열두 명`)는 다루지 않아 여전히 오탐(불필요한 보정 호출) 가능성으로 남는다**, `FactPreservation.kt`의 `NATIVE_ONES`/`WORD_NUMBER` KDoc에 그 경계를 적어 뒀다.
- **표·목록 구조 보존.** 층별 안내·학대 유형처럼 표가 본문인 문서에서 추출이 표를 평문으로 눕히고 변환 출력도 문단 텍스트뿐이다. 경쟁 원고는 표를 표로 유지했다. 추출 → 변환 → 출력에서 구조를 어디까지 실어 나를지 판단이 필요하다(문단 id 대응 기반 원본 반영과 접점이 있다 — 구조를 늘리면 그 대응도 함께 바뀐다). **→ 결정(2026-09-06, 사용자): 구조 유지가 가능한 형식(DOCX·HWPX·TXT)에서만 진행한다 — 표·목록 구조는 원본 것을 그대로 두고 그 안의 문장만 교체한다.** PDF는 렌더러 미도입 결정(§1.3)대로 범위 밖. **현재 상태(2026-09-06 확인):** 출력 쪽은 이미 이 모양이다 — S6(PR #45)의 원본 반영이 DOCX `w:tc`·HWPX 셀 안 문단을 `TextUnit`으로 같은 자리에 갈아 끼우고, 셀 합침(N:1)·셀 안 나눔(1:N)에도 표 구조가 유지됨을 `PackagedOriginalReflectorTest`가 고정한다. 추출도 셀 문단을 본문 문단과 같은 줄로 뽑는다. **빈 곳은 가운데(변환)다** — LLM은 어느 줄이 표 셀·목록 항목인지 모른 채 줄을 합치거나 셀 머리말(「구분」·「금액」)을 문장으로 늘리고, 그 결과가 정렬 지도의 `low`와 `partial` 반영으로 새어 나온다. **스팩: `docs/plans/2026-09-06-p0-4-structure-hints.md`(S8, 2026-09-06 작성).** 원본 단위마다 종류(`BODY`·`TABLE_CELL`·`LIST_ITEM`) 하나를 추출 시점에 정해 `documents.source_unit_kinds`(V11)에 저장하고, 프롬프트에 「이 구간은 표의 칸/목록 항목 — 합치거나 나누지 말고 안의 문장만」 절을 붙이며, 화면은 run 묶음과 배지를 단다(계약 2.19.0). 정렬 사전확률(S8-4)은 유료 측정(표·목록형 골든 30건 on/off) 뒤에 켠다. **→ S8-1(PR #59, V13)·S8-2(PR #62)·S8-3(계약 2.23.0, 2026-09-07) 구현.** 종류는 추출 시점에 저장되고, 프롬프트는 run마다 「합치거나 나누지 말고 안의 문장만」 절을 받으며, 검수 화면은 표 칸·목록 run을 묶어 배지를 단다. S8-4와 유료 측정(승인됨)이 남았다.
- **이미 쉬운 글인 입력의 저변경 보장.** 이 원본은 이미 쉬운 글 규격이다. 그런 입력을 전면 재작성하면 개악 위험이 있다(변환 출력의 회차 간 잡음은 A/B 실측에서 확인 — `dictionary/docs/consumer-overlap-policy.md` §5.1). 검토할 것: ⑴ 스타일 게이트를 이미 통과하는 문단은 최소 수정으로 두는 정책 ⑵ 쉬운 글을 넣으면 저변경·저팽창이어야 한다는 보존성 회귀(골든 수정본을 입력으로 쓰는 레인 — 추가 유료 호출이므로 게이트 ⓪ 측정과 같은 승인 규칙을 따른다).
- **표기 규칙 확장 검토(저우선).** 개정 원고는 연령을 '60살'로 적는다 — 쉬운 글 관행(나이 '살', 시각 오전/오후 명시)이 현 스타일 규칙에 없다. 규칙 추가는 보정 트리거가 늘어나는 비용이 있으므로, 골든셋에서 위반 빈도를 실측한 뒤 결정한다.
- **평가 자료 활용 판단.** 이 원본·수정본 쌍은 드문 「사람이 만든 정답 쌍」이지만 경쟁사 저작물이다. 골든셋 편입이 아니라 내부 참고 기준으로만 쓸지, 쓴다면 어떤 범위인지 판단이 필요하다. **결정(2026-09-04, 사용자):** 골든셋(`data/golden`)에는 편입하지 않는다. 저작권 있는 제3자 저작물이라 편입해서 얻는 것에 비해 위험이 크다. 위 항목들에서처럼 분석 문서에 내부 참고 기준으로 인용하는 것은 계속 허용한다 — 코드 변경 없음.
- **사전 컨텍스트 주입과 스타일 통과율(2026-09-01, 게이트 ⓪ 측정에서 관측).** 장문 4건 스타일 통과율이 1차(dictContext=off) 3/4(75%)에서 2차(dictContext=product, 제품 조립 경로) 1/4(25%)로 떨어졌다. 두 회차 사이에 바뀐 변수는 사전 컨텍스트 주입(off → product) 하나뿐이다. **그러나 「사전 주입이 스타일을 떨어뜨린다」고 쓰지 않는다** — n=4 각 1회는 눈금이고 검정력이 없다. 이 저장소는 같은 실수를 한 번 되돌린 적이 있다 — 커밋 `7818097`이 dictionary A/B의 rate 차이를 전부 잡음으로 판정했다. 지금 말할 수 있는 것은 **「방향이 보이므로 재현이 필요하다」**까지다. **이 관찰은 「분할 변환으로 풀리지 않는다」는 뜻이지, 게이트 ⓪ 판정 기준(`docs/master-plan.md` §9)에서 스타일 통과율을 빼도 된다는 뜻은 아니다** — 문서를 조각내도 스타일 규칙 위반은 그대로 남으므로 분할 변환이 이 문제의 답은 아니다. 다만 스타일 통과율을 판정 입력에서 어떻게 다룰지는 §1.2 판정이 아직 결정하지 못한 채로 남아 있다(위 「판정 — 상한값 재조정 쪽이 유력하나 확정은 보류」 참고). 판단이 필요한 것: ⑴ 재현 측정을 할 것인가(유료 — 반복 회차를 늘려야 눈금을 벗어난다) ⑵ 어떤 스타일 규칙이 걸렸는지 레인이 남기지 않는 계측 공백을 먼저 닫을 것인가(지금 리포트는 통과/미통과만 내고 **어느 규칙이 걸렸는지 적지 않는다** — 원인 조사에 이것이 선행이다).

  **→ 조사 결과(2026-09-02, 무료 — 유료 재측정 없이 진행).** 위 §1.2 「레인 변환문 보존 노브」(`EASYDOC_LANE_TRANSCRIPT_DIR`)가 2차 측정(dictContext=product)의 변환문 4건을 저장해 둔 덕에, 재측정 없이 그 변환문에 제품의 `checkStyle`(`core/easyread/StyleRules.kt`)을 그대로 돌려 ⑵의 계측 공백(어느 규칙이 걸렸는지)을 채울 수 있었다 — 변환문을 보존하게 만든 그 조치가 없었으면 이 조사도 재측정을 사야 했다.

  ⑴ **`checkStyle`은 문서 단위 all-or-nothing 이진 판정이다** — `passed = issues.isEmpty()`, 임계값이나 허용 개수 개념이 아예 없다. 문장 단위로 5개 규칙(LENGTH >50자·COMMA >2개·DOUBLE_PASSIVE·DIFFICULT_WORD·GLOSS_COLLISION)을 검사하고 위반이 하나라도 있으면 문서 전체가 불통과다.

  ⑵ **문서별 결과**(2차 측정, dictContext=product): `022`(11,455자·395문장·위반 9·0.786/1,000자·**불통과**), `023`(5,961자·243문장·위반 0·0.000·**통과**), `047`(5,709자·194문장·위반 4·0.701·**불통과**), `050`(5,042자·179문장·위반 7·1.388·**불통과**). 규칙별: 022는 LENGTH 4·COMMA 2·DIFFICULT_WORD 3, 047은 LENGTH 3·DIFFICULT_WORD 1, 050은 **DIFFICULT_WORD 7(전부)**, 023은 0. DOUBLE_PASSIVE·GLOSS_COLLISION은 네 문서 모두 0건.

  ⑶ **정정(2026-09-02): 「길이 인공물 가설은 부분 기각됐다」의 근거 절반이 무너져 다시 쓴다.** 원래 논증은 「022는 길이 인공물, 047·050은 진짜 품질 미스」였다 — 아래 ⑷의 정정으로 047·050의 「진짜 품질 미스」가 전부 오탐으로 밝혀져 그 절반이 성립하지 않는다. 지금 데이터가 말하는 것은 이렇다: **길이도 단독 원인이 아니고**(유일한 통과 문서 023이 047·050보다 **글자·문장 모두 더 많은데**(5,961자·243문장) 통과했다), **변환 품질 저하도 원인이 아니다**(인용된 위반이 전부 오탐이었다 — 아래 ⑷). 이 네 문서의 통과/불통과를 가른 것은 오히려 **문서가 법령 인용·시스템 라벨·특정 복합어(`시행령`·`게시판`·`연계대상자의뢰등록`·`상병보상연금`·`명의` 등)를 담고 있는가**에 가까워 보인다 — 023은 그런 항목이 없었을 뿐이다. **다만 이것도 n=4 각 1회의 관찰이다 — 단정하지 않는다.** 022 자체에 대한 관측(위반 밀도 0.786이 047의 0.701과 거의 같은데 문장이 두 배(395 vs 194)라 같은 품질을 유지해도 위반이 누적돼 all-or-nothing 게이트를 통과할 수 없었다)은 오탐 여부와 무관하게 그대로 유효하다 — 022는 LENGTH 4·COMMA 2도 함께 걸려 DIFFICULT_WORD 하나로 설명되는 사례가 아니다.

  ⑷ **정정(2026-09-02): 「047·050의 불통과는 진짜 품질 미스다 — 사전 치환 누락이다」는 철회한다.** 변환문 실물을 다시 보면 인용된 사례가 전부 **규칙의 과잉 발화(오탐)**이고 변환 자체는 옳았다. 「사전 주입이 켜져 있었는데도(dictContext=product) 그 낱말들이 안 바뀌었다」도 함께 철회한다 — **바뀌지 않은 것이 아니라 바꾸면 안 되는 것이었다.**
  - **050 `시행` ×6** — 전부 `시행령`(법령 이름) 안이고, 변환문이 매번 풀이를 붙였다: 「청소년복지 지원법 **시행령** 제4조제2항에 따라 실제로 합니다. **시행령이란 법을 자세히 정한 대통령 규정을 말합니다.**」, 「같은 법 **시행령** 제6조도 근거입니다. **시행령은 법을 실제로 할 때 필요한 내용을 정한 규정입니다.**」 — 법령 이름은 그대로 두고 쉬운 말로 설명하는 것이 이 제품이 해야 할 일이다.
  - **050 `연계` ×1** — `[연계대상자의뢰등록]`, 시스템 버튼 이름이다. 주변 문장은 「사례관리에서 연결 부탁 화면 아래 …을 누릅니다」로 쉽게 바꿨고 버튼 이름만 원문을 유지했다 — 바꾸면 사용자가 화면에서 버튼을 찾지 못한다.
  - **050 `게시` ×1** — `게시판`. 일상어 복합어다.
  - **047 `상병` ×1** — 「**질병으로 받는 연금인** 상병보상연금을 받는 분도 마찬가지입니다.」 급여의 공식 명칭을 유지하고 앞에 풀이를 붙였다.
  - **022 `명의`** — 「**명의(이름)**가 개인인 리스 차량」으로 이미 괄호 풀이가 있고, 나머지는 `공동명의자`(복합 법률용어)다.

  즉 **050의 DIFFICULT_WORD 위반 7건은 전부 오탐이고, 047·022의 인용 사례를 포함해 진짜 치환 누락은 하나도 확인되지 않았다.** 원인은 규칙 쪽이다 — `DIFFICULT_WORD`가 **낱말 시작 위치의 접두 일치**만 보고 복합어 경계를 고려하지 않아 `시행령`·`게시판`·`연계대상자의뢰등록` 같은 것에 걸린다(참고: `PROMPT_ONLY_WORDS`로 2개를 이미 제외하는 장치가 있으나 이 경우들은 다루지 못한다). 이 규칙 결함 자체는 아래 ⑹에 판단이 필요한 항목으로 남긴다.

  ⑸ **LENGTH 위반의 성격**: 실측 51~67자로 상한(50자) 소폭 초과이지 오탐이 아니다. 다만 `⑤ `·`** `·`- ` 같은 목록 마커가 문장에 붙어 50자 예산에 함께 들어간다(`LIST_MARKER`가 「숫자/한글/원문자 + . 또는 )」 패턴만 걸러 이 형태는 남는다) — 규칙 버그는 아니지만 순수 본문 길이보다 살짝 부풀려진다. **⑷의 정정과는 별개다 — LENGTH의 실측 초과와 DIFFICULT_WORD의 오탐을 뭉뚱그리지 않는다.**

  ⑹ **판단이 필요한 것 — `DIFFICULT_WORD` 규칙 결함(2026-09-02 확인).** ⑷에서 드러난 접두 일치 문제는 측정 해석이 아니라 제품 규칙 자체의 결함이다. 판단할 것: ⑴ 복합어를 어떻게 다룰지(어미·조사 경계 확인, 예외 목록, 아니면 다른 방법) ⑵ 이미 풀이가 붙은 용어(`명의(이름)`처럼 바로 뒤·괄호에서 설명된 경우)를 어떻게 볼지. **해법은 여기서 정하지 않는다** — 결함과 선택지만 남긴다. **결정 보류(2026-09-02, PR #12 Codex 승인 뒤 사용자 결정) — B3(위반 밀도) 측정 뒤에 다룬다.** 수정안은 아직 고르지 않았다. **→ 정정(2026-09-06): 이 문장은 낡았다.** 2026-09-04 커밋 `43eef53`(fix(style): DIFFICULT_WORD matches whole words only)이 선택지 ⑴ 중 「어미·조사 경계 확인」을 골라 이미 구현했다 — 낱말 뒤가 텍스트 끝·비한글·**한정된 조사/하다·되다·시키다 어미 표(축약 음절 포함)** 중 하나여야 위반으로 세고, 바로 뒤 괄호가 사전 뜻풀이와 정확히 같으면 억누른다(⑵도 함께 닫힘). `StyleRulesTest`가 ⑷의 사례(시행령·연계대상자의뢰등록·게시판·상병보상연금·공동명의자·명의(이름))를 전부 고정하고 골든 스타일 기준선은 변화 없음. **남은 것은 결함이 아니라 잔여 한계**다: ㉠ 뒤 형태소의 첫 음절이 조사 표와 우연히 겹치는 복합어(예: 「명의**이**전」·「정액**이**체」의 「이」)는 여전히 오탐, ㉡ 괄호 풀이는 사전 값과 정확히 같을 때만 인정. 선택지는 아래 「복합어 처리 선택지(2026-09-06)」.

  **복합어 처리 선택지(2026-09-06, 사용자 요청으로 정리 — 결정은 사용자).**
  - **A. 현행 유지 + 실측 관문.** 경계 표 방식을 그대로 두고, 잔여 오탐 ㉠은 골든 변환문에서 실제로 관측될 때만 다룬다. 비용 0. 근거: 2026-09-02 확인된 오탐 전부가 이미 닫혔고 ㉠은 이론상 사례라 실측 0건이다.
  - **B. 복합어 예외 목록(데이터).** `DifficultWords.kt` 옆에 「사전 낱말로 시작하지만 한 낱말인 복합어」 목록(명의이전·정액이체·…)을 두고 `appearsAsWholeWord`가 그 목록에 일치하면 건너뛴다. 비용 소(코드 10줄 + 스냅샷 테스트). 단점: 목록은 사후적으로만 자라고, `PROMPT_ONLY_WORDS`·사전 소유권 정책(consumer-overlap-policy)과 세 번째 목록이 된다.
  - **C. 형태소 분석기 도입.** Komoran·OpenKoreanText 등으로 명사 경계를 얻어 조사 표를 대체. 정확도는 가장 높지만 core에 수 MB 사전 의존성이 들어오고, 분석기 버전이 바뀌면 골든 스냅샷이 흔들리며 `checkStyle`이 조회마다 도는 경로(S7 `compliant_source_units`, ≈24ms/20,000자)의 비용이 수 배 는다. Lean MVP 범위 밖으로 본다.
  - **권고: A.** B는 ㉠이 실측되면 그때 켠다.

  **함의 — 게이트 ⓪ 판정에 걸리는 것:** 스타일 통과율은 길이에 구조적으로 편향된 지표다. 문장 단위 규칙 + 문서 단위 all-or-nothing이면, 품질(위반 밀도)이 같아도 문서가 길수록 통과 확률이 떨어진다 — 022가 그 사례다. 그런데 **게이트 ⓪이 묻는 것이 바로 「길이가 늘면 품질이 무너지나」**다 — 길이에 편향된 지표를 길이 판정의 입력으로 쓰고 있는 셈이다. **정정(2026-09-02)으로 갱신:** 이 편향은 022 사례로 여전히 유효하다. 여기에 **두 번째, 독립적인 편향**이 더해진다 — 위 ⑷에서 드러났듯 `DIFFICULT_WORD` 규칙이 법령 인용·공식 명칭·복합어에 과잉 발화하므로, **그런 용어를 담은 문서는 변환 품질과 무관하게 떨어진다.** 이진 통과율을 게이트 ⓪ 입력으로 쓰지 말아야 할 이유가 이제 둘이다: 길이 편향과 용어-과잉발화 편향. 이 조사는 §1.2 「확정에 필요한 것」 ⑶(「스타일 통과율을 판정 입력에서 어떻게 다룰지 명시적 재결정」)에 실질적 근거를 준다 — 지표를 빼자는 것이 아니라, **이진 통과율 대신 위반 밀도로 읽으면 「분량 페널티」와 「진짜 품질 문제」가 갈린다**는 것이다(다만 위 ⑹이 남기듯 「진짜 품질 문제」로 셀 수 있는 DIFFICULT_WORD 사례는 이번 4건 조사에서 하나도 확인되지 않았다).

  **한계(그대로 남는다):** 1차(dictContext=off) 변환문은 저장되지 않아 직접 비교가 불가능하다 — 이 조사는 「2차에서 무엇이 걸렸나」에만 답한다. **「사전 주입이 원인인가」는 여전히 미답이고 재현 측정이 필요하다** — 위 「방향이 보이므로 재현이 필요하다」는 그대로 유효하다. n=4 각 1회라는 한계, 밀도 수치가 표본 4건의 것이라는 한계도 그대로다.

  **철회(2026-09-02):** 이전에 여기 있던 「관찰 하나(단정 아님)」 문단 — 「`docs/golden-collection-plan.md`의 같은 날 머리·꼬리 재확인 결과와 겹치는 정렬이 있다: 머리·꼬리가 깨끗한 022·023은 통과하거나 길이 인공물로 떨어졌고, 조각 오염이 있는 047·050이 진짜 치환 누락을 냈다」 — 는 철회한다. 그 정렬의 절반을 이루던 「047·050의 진짜 치환 누락」이 위 ⑷의 정정으로 무효가 되어 정렬 자체가 성립하지 않는다. `docs/golden-collection-plan.md`의 머리·꼬리 오염 기록 자체는 그대로 유효하지만, 그것과 스타일 통과/불통과 사이의 정렬을 가설로 제시할 근거는 이제 없다.

## 1.4 메일 발송 서비스·소셜 로그인 조사 (2026-09-04)

### 메일 발송 서비스

전제: 자체 호스팅 SMTP가 아니라 **외부 발송 서비스**를 쓴다(결정 완료). 용도는 회원가입 인증코드·변환 완료 알림 등 **트랜잭션 메일만**이며 정보통신망법의 광고성 정보 규제(수신동의·야간 발송 제한) 대상이 아니다 — 단 추후 마케팅 메일을 얹으면 채널/템플릿을 분리해야 한다. 물량은 월 수백~수천 통(저볼륨), 발신 도메인은 `easydoc.kr`, API 키 등 비밀값은 환경변수/secret manager로만 주입하며 local/CI는 fake sender로 동작한다.

| 서비스 | 월 5천통 기준가 | API | Java/Kotlin SDK | 한국 리전 | 비고 |
|---|---|---|---|---|---|
| AWS SES | $0.50~$0.80 추정(2026 가격 구조가 단일 요율/볼륨 티어 중 어느 쪽인지 소스 간 불일치 — **미확인**, 확인일 2026-09-04) | REST(SESv2) + SMTP relay | AWS SDK for Java v2 / Kotlin 공식 지원 | 서울(ap-northeast-2) 가능 | 신규 계정은 sandbox 상태로 시작, Production Access 승인 필요. SDK 자체 재시도를 끄고 써야 함(재시도 한 계층 원칙과 충돌) |
| SendGrid(Twilio) | Essentials $19.95/월(5만통 포함, 확인일 2026-09-04) | REST + SMTP relay | 공식 Java SDK(`sendgrid-java`) | 명시적 리전 옵션 없음(미국 처리 추정) | 마케팅 기능·요금이 섞여 있어 트랜잭션 전용엔 과잉 스펙 |
| Mailgun | Foundation $35/월(5만통 포함, 확인일 2026-09-04) | REST + SMTP relay | 공식 Java SDK(`mailgun-java`) | US/EU 선택(EU=독일), 한국 없음 | 저볼륨엔 기본 요금이 과함 |
| Postmark | Basic $15/월(1만통 포함, 초과 $1.80/1000, 확인일 2026-09-04) | REST + SMTP relay | 공식 Java SDK(`postmark-java`) | 명시 안 됨(미국 추정) | 트랜잭션 전용 설계, 전달성 평판 상위권으로 언급됨, Sandbox Mode 공식 지원 |
| Resend | Pro $20/월(5만통, 확인일 2026-09-04) | REST(HTTP)만 | **미확인**(공식 SDK 없이 HTTP 직접 호출 가능성) | 명시 안 됨 | 가격 정책이 자주 바뀌는 편(2024 Scale 티어 인상 사례) |
| Brevo(구 Sendinblue) | $9~$25/월 구간 — 자료마다 상이, **미확인** | REST + SMTP relay | 공식 SDK 존재 주장(`sib-api-v3-sdk`)이나 GitHub 원본 미확인 | EU(프랑스), 한국 없음 | 공식 가격 페이지가 JS 렌더링이라 3차 소스 의존, 채택 전 재확인 필요 |
| NHN Cloud Notification(Email) | **미확인**(콘솔 로그인 필요) | REST(SMS·알림톡과 통합 Notification API) | **미확인** | 국내(판교 등) | 완전한 한국어 콘솔, SMS/알림톡 확장 시 통합 이점 |
| Naver Cloud Cloud Outbound Mailer | **미확인**(가격 계산기 로그인 필요) | REST(GET/POST/DELETE) | 공식 Java SDK 있음 | 국내(리전별 DKIM selector 분리) | 기본 발송 한도 월 100만 건. `naver.com`/`navercorp.com`/`ncloud.com`은 발신 도메인 등록 불가(`easydoc.kr`은 무관). DKIM 키 392자로 DNS TXT 255자 제한 초과 — 분할 등록 필요 |

권고: **1순위 AWS SES** — 서울 리전, 공식 Kotlin SDK, 저볼륨 구간 최저가 축이나 sandbox 상태라 Production Access 승인이 선행돼야 한다. **2순위 Postmark** — 트랜잭션 전용이라 마케팅 오발송 위험이 구조적으로 없고 전달성 평판이 좋다. 국내 리전이 반드시 필요해지면 Naver Cloud가 유력하나 정확한 원화 단가는 미확인. 이번 조사로 확보하지 못한 항목: SES의 2026년 정확한 요금 구조, NHN Cloud·Naver Cloud의 원화 단가, naver.com/daum.net/kakao.com 수신함에서 특정 벤더가 스팸 처리되는 실증 사례(실제 발송 테스트 필요, 유료·실발송이라 사용자 승인 하 별도 진행).

통합 형태(CLAUDE.md 포트/어댑터 원칙): `application`에 `MailSender` 포트(`send(OutboundMail): MailSendResult`), `infrastructure/mail`에 벤더별 어댑터(`SesMailSender` 등)와 local/CI용 `FakeMailSender`, `@ConfigurationProperties` 기반 `MailProperties`(provider·from-address·api-key 참조·timeout-ms). `LlmProvider`→`MetricsLlmProviderDecorator`와 같은 패턴으로 관측 decorator를 얹을 수 있다. 벤더 SDK 자체 재시도는 끄고 재시도 책임은 큐 쪽 한 계층만 갖는다. **어느 서비스를 쓸지는 아직 미정 — 결정은 사용자 몫이다.**

### 소셜 로그인(카카오·네이버·구글)

플로우 권고: Authorization Code 방식에서 **SPA가 code를 받아 백엔드 계약 오퍼레이션 `oauthCallback`으로 중계**하고, 백엔드가 code→token→profile 교환 뒤 자체 JWT를 발급한다(client secret은 항상 백엔드에만 존재). Spring Security의 OAuth2 Client 세션 필터체인(`CommonOAuth2Provider`)은 카카오·네이버를 내장 지원하지 않고 이 프로젝트는 세션 없는 JWT API이므로 채택하지 않는다 — 대신 `core`에 `SocialLoginProvider` 포트를 정의하고 `infrastructure`에 제공자별 HTTP 어댑터(`KakaoOAuthAdapter`/`NaverOAuthAdapter`/`GoogleOAuthAdapter`) 3개로 통일한다.

| 제공자 | 안정 식별자 | 이메일 보장 | 필요 절차 |
|---|---|---|---|
| 구글 | `sub`(OIDC id_token claim) | `email_verified=true`로 신뢰도 있게 제공 | Cloud Console에서 OAuth 클라이언트 생성, 동의 화면 설정만 — 심사는 브랜딩 확인 수준 |
| 카카오 | `id`(카카오 회원번호) | 기본은 닉네임만 필수 동의, 이메일은 **비즈 앱 전환 + 동의항목 심사(3~5영업일)** 뒤에야 필수 동의 가능 — 개인 개발자도 본인인증 완료 시 가능 | 비즈 앱 전환 신청을 최대한 빨리 시작, `is_email_valid`/`is_email_verified` null 가능성을 코드로 항상 확인 |
| 네이버 | `response.id` | 이메일은 "제공 정보" 선택 항목이라 미보장(계정에 이메일이 없거나 비공개면 값이 비거나 거부 가능) | 개발자센터에서 제공 정보 항목 선택 + 서비스 검수. **공식 문서(`developers.naver.com`)는 이번 조사의 WebFetch에서 접근 차단됨 — 2차 출처 기반, 착수 전 재확인 필수** |

계정 모델: `user_identities(provider, provider_user_id, user_id)` 연결 테이블 신설, 유니크 제약 `(provider, provider_user_id)`. `users.password_hash`는 nullable로 변경(소셜 전용 계정은 비밀번호 없음). **동일 이메일 자동 연결은 금지** — 이메일 검증 수준이 제공자마다 달라 보안 위험이며, 로그인 후 사용자가 명시적으로 연결하는 흐름을 권장(MVP엔 구현 부담도 적음). 기존 `signup`/`login` 계약은 유지하고 `oauthStart`/`oauthCallback` 오퍼레이션을 추가한다. 제공자 access/refresh token은 저장하지 않는다(로그인 목적엔 불필요, unlink 등 1회성 호출엔 요청 처리 중 메모리에서만 사용).

보안: state는 세 제공자 모두 필수 발급·서버 대조(CSRF 방지). redirect URI는 local/prod 각각 콘솔에 등록하는 allowlist 방식이며 와일드카드 미지원이 일반적이다.

권고 순서: **구글 → 카카오 → 네이버.** 구글은 문서가 가장 명확하고 리스크가 낮아 공용 포트·계약·스키마를 먼저 검증하는 데 쓰고, 카카오는 비즈 앱 심사(3~5영업일)에 리드타임이 있으니 신청을 최대한 빨리 시작한다. 사용자 준비물: 각 콘솔 앱 등록, client id/secret 발급, local/prod redirect URI 등록(전부 env/secret manager로 주입) — 구글은 Cloud Console, 카카오는 카카오디벨로퍼스(+비즈 앱 전환), 네이버는 개발자센터(+서비스 검수).

신뢰도: 카카오·구글은 공식 문서 직접 열람으로 확인됨. 네이버는 공식 문서 접근이 막혀 2차 출처 교차검증에 그친 부분확인이며, PKCE 지원 여부(카카오·네이버)와 구글 revoke 엔드포인트의 신구 URL 우선순위는 미확인으로 남는다.

**→ 구글 구현 완료(2026-09-04, `feat/google-login` 브랜치, 계약 2.8.0).** 위 권고 순서대로 첫 제공자(구글)를 이 절이 적은 설계 그대로 구현했다 — `SocialLoginProvider` 포트(`application`) + `infrastructure/auth/google`의 HTTP 어댑터, `user_identities` 연결 테이블(`V6__user_identities.sql`, 유니크 `(provider, provider_user_id)`), `users.password_hash` nullable, 계약에 `oauthStart`/`oauthCallback` 오퍼레이션 추가(기존 `signup`/`login`은 무변경). **동일 이메일 자동 연결 금지** 규칙도 그대로 지켰다 — 신원은 새로운데 같은 **검증된** 이메일의 계정이 이미 있으면 자동으로 잇지 않고 409(`email_already_linked`, "이미 같은 이메일로 가입된 계정이 있습니다. 이메일로 로그인한 뒤 연결해 주세요.")를 낸다. **로그인 후 사용자가 명시적으로 연결하는 흐름은 아직 구현하지 않았다 — 다음 작업 단위다.** 카카오·네이버는 미구현(포트는 재사용 가능하나 어댑터·비즈 앱 심사·서비스 검수는 착수 전이다).

**→ 네이버는 미검증 이메일로도 가입을 연다(2026-09-05, `feat/naver-login` 브랜치, 계약 2.15.0, option 2).** 네이버엔 `email_verified` 개념이 없어(위 표) 다른 제공자와 같은 "이메일이 없거나 검증되지 않으면 422" 규칙을 그대로 적용하면 네이버 신원으로는 영원히 새 계정을 만들 수 없었다. 그래서 네이버만 예외를 둔다 — 이메일이 있으면 미검증인 채로도 계정(`email_verified_at IS NULL`)과 기본 작업 공간을 만들고, 트랜잭션 커밋 뒤 비밀번호 가입(`signup`)과 같은 best-effort로 `EmailVerificationService`가 이메일 인증 코드를 발급한다(프런트도 `SignupPage`와 같은 `/verify-email` 화면으로 보낸다). 이메일 자체가 없으면 여전히 422이되 네이버 전용 문구를 쓴다. 이메일이 이미 다른 계정에 등록돼 있으면 다른 제공자와 같은 규칙대로 여전히 409(자동 연결 안 함).

**남은 후속 과제(2026-09-05, 의도적으로 미구현) — 네이버 옵션 2가 남긴 결함 둘.**

⑴ 409 화면(`OAuthCallbackPage`)이 "이메일로 로그인하세요"를 안내하는데, 소셜 전용 계정(`users.password_hash IS NULL`)은 애초에 비밀번호가 없어 그 안내대로 로그인할 수 없다 — 원래 가입한 제공자로 다시 로그인하거나 계정 설정에서 비밀번호를 설정하는 경로가 필요하다. 이 405 안내는 구글·카카오(2.8.0·2.13.0)부터 있던 문제이나 네이버(미검증 계정도 이 화면을 거친다)가 겹치며 노출 빈도가 늘었다.

⑵ 옵션 2는 미검증 네이버 이메일이 `ix_users_email`을 그대로 점유하게 둔다 — 그 이메일의 진짜 주인이 나중에 비밀번호로 가입하면 409를 본다. 네이버가 반환하는 `response.email`이 실제로 그 이메일 소유자만 받는 값(계정에 등록된 본인 이메일)이라는 전제가 성립해야만 안전하다 — 공식 문서가 이 조사 환경에서 접근 차단돼(backlog §1.4) 그 전제를 1차 출처로 확인하지 못했다. 운영자 확인 또는 공식 문서 재확인이 먼저다. **→ 사용자 확인(2026-09-06): 네이버는 이메일 소유를 검증하지 않는다.** 전제가 무너졌으므로 ⑵는 실재하는 결함이다. 다만 **네이버만의 결함은 아니다** — `AuthService.signup`(비밀번호 가입)도 `users.create`가 미검증 상태로 행을 만들어 `ix_users_email`을 즉시 점유하고, 검증 전 계정을 지우는 경로가 없다. 누구든 남의 이메일을 적어 가입하면 진짜 주인은 같은 409를 본다. 즉 문제의 본질은 「미검증 계정이 이메일을 무기한 선점한다」이고 네이버는 그 입구 하나를 더한 것이다. 선택지: **ⓐ 미검증 계정 TTL 파기** — 가입(비밀번호·소셜 공통) 후 N시간 안에 검증되지 않은 계정을 `RetentionPurgeScheduler`와 같은 배치로 지운다(신원·작업 공간 포함). 단순하고 두 입구를 한 번에 닫으나, 그 창 안에서는 여전히 409. **ⓑ 미검증 점유의 인계** — 가입 시 이메일이 미검증 계정에 잡혀 있으면 409 대신 새 검증 코드를 그 이메일로 보내고, 코드 검증에 성공한 쪽이 계정을 가져간다(기존 미검증 행의 이메일을 비우거나 행을 지움). 창이 없지만 계정 교체 트랜잭션·소셜 신원 재연결·감사 로그가 얽혀 비용이 크다. **ⓒ 네이버만 이메일 없이 가입** — 네이버 신원은 `users.email`을 `null`로 만들고 검증 뒤에 채운다. 네이버 입구만 닫고 비밀번호 입구는 그대로라 반쪽이다. **권고: ⓐ(TTL 24시간)를 먼저, ⓑ는 파일럿에서 실제 충돌이 관측되면.** **→ 결정(2026-09-07, 사용자): ⓐ 채택.** 미검증 계정(비밀번호·소셜 공통, `email_verified_at IS NULL`)이 만든 지 24시간(구성값 `easydoc.auth.unverified-purge.ttl-hours`)을 넘기면 일일 파기 배치가 지운다 — 기존 피드백 의견 파기와 같은 `FOR UPDATE SKIP LOCKED` 배치·관측 모양, 삭제는 FK cascade(작업 공간·신원·인증 코드·재설정 코드). 문서를 가진 계정은 건너뛰고 개수만 센다(미검증 계정은 문서를 만들 수 없지만 규칙에 기대지 않는다). 로그·관측에는 개수만 남긴다.

**남은 결함(2026-09-04, 의도적으로 미해결) — 동시 최초 콜백 경쟁이 오도하는 409를 낼 수 있다.** `SocialLoginService.callback`은 신원 미연결·새 이메일 갈래에서 ⑴ `users.findByEmail`로 이메일 중복을 트랜잭션 **밖**에서 먼저 보고 ⑵ 트랜잭션 안에서 `createWithoutPassword` + `user_identities.link`를 한다. 같은 사람이 같은 제공자 콜백을 (예: 브라우저 탭 두 개로) 동시에 두 번 보내면, 두 요청 모두 ⑴에서 "아직 없음"을 보고 통과한 뒤 ⑵의 `users.email` 유일 인덱스(`ix_users_email`, V1)에서 하나만 성공하고 나머지는 `EmailAlreadyRegisteredException` → **409 `duplicate_email`**(사용자가 자기 자신과 충돌한 것뿐인데 "이미 가입된 계정" 문구를 본다 — `email_already_linked`가 아니라 가입 경로의 일반 문구다, `JdbcUserRepository.createWithoutPassword`가 그 예외를 던진다). **재시도하면 성공한다** — 실패한 요청이 다시 콜백을 밟으면 이번에는 ⑴에서 방금 만들어진 신원이 아니라 이메일이 걸리므로 여전히 409지만, `user_identities.findByProviderIdentity`를 먼저 보는 정상 경로(이미 연결된 신원 → 로그인)를 다시 타면 통과한다 — 즉 요청 자체는 안전하고(중복 계정이 생기지 않는다), 사용자 경험만 나쁘다(드문 경쟁에서 한 번 실패 문구를 본다). 고칠 후보 둘: ⑴ `user_identities`에 먼저 upsert(멱등)하고 `users` 생성은 그 결과로 갈리게 하기 — 경쟁을 신원 유일 제약(이미 있는 제약, `(provider, provider_user_id)`) 쪽으로 옮긴다. ⑵ `users` 유일 제약 위반을 잡은 뒤 `findByProviderIdentity`를 **한 번 더** 재조회해, 그사이 다른 요청이 신원을 연결했으면 그 결과로 로그인 처리하기(현재는 위반을 잡아 곧바로 409로 옮긴다 — 재조회 없이). 어느 쪽도 구현하지 않았다 — 발생 빈도가 낮고(같은 사람의 동시 두 콜백) 재시도로 복구되므로 이번 작업 단위의 판단 밖으로 남겼다. **→ 해결(2026-09-06, 후보 ⑵).** `SocialLoginService.callback`이 계정 생성 트랜잭션에서 `EmailAlreadyRegisteredException`을 잡으면(롤백이 끝난 뒤) `findByProviderIdentity`를 한 번 더 본다 — 그사이 다른 요청이 같은 신원을 연결했으면 그 사용자로 로그인 처리하고(작업 공간 생성·가입 메일 없음), 여전히 없으면 진짜 중복 이메일이라 같은 예외를 그대로 던진다(409 의미 불변). 사전 검사(`requireEmailNotAlreadyLinked`)는 흔한 갈래를 커넥션 없이 끝내는 용도로 그대로 두고, 유일 인덱스가 마지막 방어선이라는 KDoc에 「그 뒤 신원 재조회가 자기 경쟁과 진짜 중복을 가른다」를 더했다. 검증: `SocialLoginServiceTest`(자기 경쟁 → 승자 토큰·부수 효과 없음, 진짜 중복 → 409 유지)와 실제 PostgreSQL 두 스레드 `SocialLoginCallbackConcurrencyTest`(둘 다 같은 사용자 토큰, `users` 1행·신원 1행). **`oauthLinkCallback`의 동시 연결 경쟁은 별개이며 손대지 않았다.** **→ 해결(2026-09-07).** `linkCallback`도 같은 모양으로 닫았다 — `identities.link`의 `ConflictException`을 잡아 재조회하고, 같은 사용자·같은 신원이면 멱등 성공(이메일 검증 표시도 그대로), 다른 사용자면 신원 충돌 409, 같은 사용자의 다른 신원이면 제공자 중복 409, 설명되지 않으면 원래 예외. 실제 PostgreSQL 두 스레드 `SocialLinkCallbackConcurrencyTest`가 「둘 다 성공, 신원 1행」을 잰다. 함께, `callback`의 진짜 중복 갈래가 저장소 문구(「이미 가입된 이메일입니다」)를 그대로 올리던 것을 계약 `oauthCallback` 409 예시 문구(`EMAIL_ALREADY_LINKED_MESSAGE`)로 맞췄다.

**→ 카카오 구현 완료(2026-09-05, PR #31 백엔드·#32 프런트).** 권고 순서의 두 번째 제공자(카카오)를
구글과 같은 `SocialLoginProvider` 포트로 구현했다 — 백엔드 어댑터·계약 오퍼레이션 확장이 PR #31,
프런트 소셜 로그인 컴포넌트의 provider 매개변수화가 PR #32다. **네이버는 아직 진행 중이다**(포트는
재사용 가능하나 어댑터·서비스 검수는 착수 전이다).

**→ 연결 해제 구현 완료(2026-09-06, 계약 2.17.0) — 명시적 연결(2.10.0)의 반대 방향이 열렸다.**
`DELETE /auth/oauth/{provider}/link`(`oauthUnlink`, Bearer 필요)가 호출자 계정에서 신원 연결을
끊는다. 코드 교환도 state 도 없는 순수 DB 조작이다. 판정 셋: 연결이 없으면 404(존재 은닉, 반복
호출도 멱등), 비밀번호가 없고 그 신원이 유일한 연결이면 409(마지막 로그인 수단), 그 밖은 해제된다.
`UserResponse.has_password`(신규 required 필드)가 화면이 그 판정을 미리 가늠하는 재료다 —
`SocialLinkStatus`가 「연결 해제」 버튼을 두고, 마지막 로그인 수단일 때는 비활성화하며 사유를
`aria-describedby`로 잇는다. `ModalDialog`로 먼저 확인한 뒤 보낸다(`window.confirm` 미사용).

**동시 해제 경쟁은 `UserRepository.lockForUpdate`(`SELECT … FOR UPDATE`)로 막는다(같은 날 리뷰
지적 H1).** 비밀번호 없는 계정에서 서로 다른 제공자를 동시에 해제하면, 신원 개수만 보는 낙관적
판정은(각 트랜잭션이 자기 시작 시점의 스냅샷만 보므로 조건부 `DELETE`의 개수 서브쿼리로도 못
막는다) 둘 다 통과시켜 로그인 수단이 0개로 떨어질 수 있다. `unlink`가 트랜잭션 맨 앞에서 사용자
행을 잠가 두 번째 요청을 첫 번째의 커밋까지 대기시킨다 — `SocialUnlinkConcurrencyTest`(실제
PostgreSQL, `EnvelopeRotationConcurrencyTest`와 같은 방식)가 "정확히 하나만 성공하고 하나는
409, 신원이 하나 남는다"를 잰다.

**409 문구는 비밀번호 설정을 안내하지 않는다(같은 리뷰 M4).** 소셜 전용 계정에 비밀번호를 붙이는
엔드포인트가 아직 없어 "먼저 비밀번호를 설정하거나"는 갈 곳 없는 안내였다 — 지금은 "마지막 로그인
수단은 해제할 수 없습니다. 먼저 다른 소셜 계정을 연결하세요."로 실제로 열려 있는 탈출구만
안내한다. **후속 과제로 남는다**: 소셜 전용 계정을 위한 비밀번호 설정/재설정 엔드포인트가 없다 —
비밀번호도 없고 다른 소셜 계정도 없는 사용자가 마지막 신원을 정말 해제하고 싶다면(예: 그 제공자
계정을 잃어버리기 전에) 지금은 방법이 없다. 이 엔드포인트가 생기기 전까지 그 경우는 막다른
길이다.

**→ 위 「남은 후속 과제 ⑴」의 409 문구도 함께 고쳤다(2026-09-06).** `OAuthCallbackPage`의 409
안내가 "이메일로 로그인하면 연결해 드립니다"였는데, 그 문구는 기존 계정에 비밀번호가 있다고
전제했다 — 소셜 전용 계정(비밀번호 없음)이 걸리면 안내대로 할 수 없었다. 이제는
"이미 이 이메일로 가입된 계정이 있습니다. 그 계정에 로그인한 뒤 계정 설정에서 {provider} 계정을
연결하세요."로 비밀번호를 전제하지 않는다. **⑵(미검증 네이버 이메일이 `ix_users_email`을 선점하는
문제)는 이번 작업 단위 밖으로 그대로 남는다.**

**→ 비밀번호 설정·재설정 구현 완료(2026-09-06, 계약 2.19.0) — 위 「소셜 전용 계정을 위한 비밀번호
설정/재설정 엔드포인트가 없다」 후속 과제가 닫혔다.** 세 오퍼레이션이 열렸다.

- `POST /auth/password`(`setPassword`, Bearer 필요, 204) — 비밀번호가 없는 계정만 새 비밀번호를
  만든다(이미 있으면 409, `PasswordService`). 이메일 검증을 요구하지 않는다 — 소셜 로그인으로
  이미 인증된 상태가 소유를 증명했다.
- `POST /auth/password-reset/request`·`POST /auth/password-reset/confirm`(`security: []`,
  `PasswordResetService`) — 이메일 코드로 비밀번호를 (재)설정한다. `request`는 이메일 존재·
  재요청 빈도와 무관하게 **항상 202**다(존재 은닉, 이메일당 60초 쿨다운을 조용히 흡수한다).
  `confirm`은 코드 확인 성공 시 비밀번호를 바꾸고(비밀번호 없던 계정에도 새로 생긴다) 코드가
  이메일 소유를 증명했다는 근거로 `readMe.email_verified`도 함께 참으로 표시한 뒤 `login`과 같은
  `TokenResponse`를 낸다. 이메일 부재·오답·만료·시도 소진(5회)은 전부 같은 401로 묶는다.
- 코드 메커니즘(6자리, salt+SHA-256 해시, 10분 TTL, 60초 쿨다운, 5회 시도 상한)은
  `EmailVerificationService`/`email_verification_codes`(V7)와 완전히 같다 — 공통부를
  `JdbcOneTimeCodeStore`로 추출해 `password_reset_codes`(신규 migration V11)와 공유한다.
- `oauthUnlink` 409 안내가 다시 갱신됐다 — "먼저 비밀번호를 만들거나 다른 소셜 계정을
  연결하세요."(탈출구가 둘이 됐다).

**의도적으로 미구현으로 남긴 것 — IP 단위 요청 빈도 제한.** `passwordResetRequest`의 남용 방지는
이메일당 60초 재발송 쿨다운 하나뿐이다. 같은 IP가 서로 다른 이메일 여러 개로 재설정 코드 발송을
반복해 메일 발송량을 소진시키는 시나리오는 이번 작업 단위 밖이다 — 필요해지면 별도 과제로 IP
버킷 rate limiter(`LookupRateLimiter`와 같은 결의 포트)를 얹는다.

**독립 리뷰가 지적한 잔여 위험 둘(2026-09-06, 의도적으로 미해결).** ⑴ **요청 처리 시간
side-channel** — 존재하는 이메일은 동기 SMTP 발송까지 치르고 응답하고, 모르는 이메일은 DB
조회 한 번으로 끝나 응답 시간이 갈릴 수 있다(엄밀한 상수 시간 보장은 아니다). 지금은 큰 위험이
아니다 — `signup`이 중복 이메일에 409를 내어 "이 이메일에 계정이 있는가"를 이미 다른 경로로
알 수 있으므로 이 side-channel이 새로 여는 정보가 아니다. 고치려면 메일 발송을 비동기 디스패치로
옮겨 요청 처리 시간에서 떼어내야 한다. ⑵ **재요청이 매번 이전 코드를 무효화한다** —
`JdbcOneTimeCodeStore.issue`의 "활성 코드는 최대 하나" 불변식(이메일 인증과 공유하는 메커니즘)
때문에, 남의 이메일 주소를 아는 제3자가 60초마다 재설정을 요청하면 진짜 소유자가 막 받은 코드를
계속 무효화시켜 재설정을 사실상 무기한 막을 수 있다(서비스 거부). 60초 쿨다운은 메일 발송
**양**을 막을 뿐 이 거부 시나리오를 막지 않는다 — 애초에 쿨다운이 "몇 초 안에 재요청했는가"만
보고 "직전 코드가 아직 안 만료됐는가"는 보지 않기 때문이다. 고칠 후보: 쿨다운 안에서도
재요청이 오면 새 코드로 무효화하는 대신 **아직 만료 전인 기존 코드를 그대로 재발송**한다(같은
코드를 담은 메일을 한 통 더 보낼 뿐 활성 코드를 바꾸지 않는다) — 이러면 제3자의 반복 요청이
진짜 소유자의 코드를 죽이지 못한다.

## 2. 구현 시 반드시 지킬 요구사항

### 2.1 저장 암호화

- **표준 AEAD**를 사용한다. 이미 `infrastructure.crypto.CryptoConfiguration`과 현재 Flyway schema로 구현돼 있다.
- 요구 성질: round-trip, 변조 거부(HMAC/AEAD 태그 검증을 복호화보다 먼저), 키 회전 지원(`encryption_scheme`/`key_version` 컬럼), nonce 재사용 금지.
- 판정 기준은 round-trip, 변조 거부, 키 회전, nonce 재사용 금지 성질이다.

### 2.2 Argon2 (비밀번호 해시)

- **재해시 판정은 전체 파라미터 동등성으로 구현한다.** Spring Security `Argon2PasswordEncoder.upgradeEncoding`의 기본 동작(`memory`·`iterations`의 "미만"만 비교)은 부족하다 — parallelism 변경, hash_len 변경 등에서 재해시 필요 여부를 놓친다. 파라미터 기준(spike 시점): `Argon2PasswordEncoder(16, 32, 4, 65536, 3)`.
- 이미 `infrastructure.auth.Argon2PasswordHasher`/`Argon2Phc`로 구현돼 있음 — 재해시 판정 로직이 전체 파라미터 동등성을 쓰는지 재검토 대상.

### 2.3 JWT

- **clock skew는 0으로 명시한다.** 라이브러리 기본값(예: Nimbus `JWTClaimsVerifier`·Spring `JwtTimestampValidator`의 기본 60초 leeway)을 그대로 두면 만료 토큰이 최대 59초까지 통과한다.
- 경계 케이스(`exp == now`, `exp == now - 1`) 회귀 테스트 유지.
- 이미 `infrastructure.auth.JwtAccessTokens`로 구현돼 있음 — skew 설정이 0으로 명시돼 있는지 재검토 대상.

### 2.4 문서 파싱 (DOCX/PDF/HWPX)

- **DOCX**: Apache POI를 usermodel이 아니라 **OOXML DOM 순회**로 쓰면 블록 추출 결과가 안정적이다(표·텍스트박스·SDT·`w:ins`/`w:delText`·`mc:Fallback`·`a:t`/`m:t`·linked 머리글 처리 필요).
- **HWPX**: DTD/UTF-16 DTD/XXE 차단 필수(StAX 파서 설정). zip bomb 방어 필수(압축 해제 크기 상한 — spike 기준 1GiB 입력을 힙 256MB에서 거부). mimetype 항목이 STORED로 zip 첫 번째여야 한다(개방형 HWPX 스펙). **내보내기는 `hwpxlib` BlankFileMaker로 header.xml·manifest·spine을 채우고**, mimetype만 첫 STORED 항목으로 다시 얹는다.
- **PDF**: PDFBox 사용. `MAX_EXTRACTED_CHARS`·업로드 크기 상한 경계 확인 필요. 암호 걸린 PDF/DOCX 처리 정책 미정 — 결정 필요.
- **DOCX 내보내기 템플릿**: POI로 생성한 DOCX에는 `styles.xml`/theme가 기본으로 없어 Heading 1 등 서식이 사라진다. 템플릿을 저장소에 동봉할지 결정 필요.
- 내보내기 zip 컨테이너의 바이트 단위 동일성은 애초에 불가능한 목표다(압축기 차이) — 비교는 정규화된 텍스트/구조로 한다.

### 2.5 ~~마스킹·개인정보~~ → 제거됨 (2026-09-07)

- 개인정보 마스킹은 하지 않는다 — master-plan 3.2 「제품 전제」(공공 배포 문서, 개인정보 없음). 2종 자동 마스킹·자리표시자·`masked_items`는 2026-09-07에 코드·계약·화면에서 제거했다(`docs/plans/2026-09-07-remove-masking.md`). 이 문서의 다른 절에 남은 「마스킹」 서술은 그 날짜 이전의 역사다.
- 남는 규칙은 하나다: 사용자 문서 본문을 로그·메트릭·분석 이벤트에 남기지 않는다(CLAUDE.md 아키텍처 규칙).

## 3. 언어 독립 데이터 위치

- golden JSON 56건, `required_facts` 253개: `data/golden/documents/`
- 프롬프트·스타일 규칙 기준: `backend-kotlin/core/src/main/kotlin/kr/easydoc/core/easyread/` 및 같은 모듈의 스냅샷 테스트
- DOCX/PDF/HWPX 보안 fixture: `backend-kotlin/infrastructure/src/testFixtures/resources/fixtures/ingest/`

## 4. 역사 자료

- Python 시대 스프린트와 CI 기록은 `docs/plans/archive/python-era/`에 보관한다.
- Python 제거 범위와 결정은 `docs/plans/archive/transition/2026-08-24-python-removal-for-kotlin-redevelopment.md`에 보관한다.
- 역사 자료의 완료 표시는 현재 Kotlin backlog를 닫지 않는다.
