# 사용량 가시화 — LLM 호출 원장·워크스페이스 집계·운영 리포트

- 작성 2026-09-07. 사용자 결정(2026-09-07): 「1·2·3 묶음으로 진행, 권고대로 결정」.
- 기준: `docs/master-plan.md` §3.3(호출 상한)·§4.1 P0-11(어드민)·크레딧 정책(1,000자 = 1크레딧, Starter 50 / Pro 200 / Enterprise 500+), 결제 PG는 MVP 밖(청구서 → 계좌이체 → 세금계산서 수동).
- 목표: 지금은 **관측만 하고 남기지 않는** LLM 사용량을 남기고, 워크스페이스·기간별로 집계해 화면과 CSV로 보여 준다. 크레딧 **차감**(잔액·거절)은 이 묶음에 없다 — 집계가 먼저 서야 얹을 수 있다.

## 1. 지금 참인 것

1. `MetricsLlmProviderDecorator`가 호출마다 provider·응답 model·입출력 토큰·지연·예상 비용(`BigDecimal?`, 단가 없으면 `null`)을 계산해 **구조화 로그**로만 낸다. `conversions`에는 provider·model·입출력 토큰만 남고, 재변환은 `reconversion_calls_used` 카운터로만 남는다. 비용은 어디에도 저장되지 않는다.
2. 단가는 `easydoc.llm.pricing.input/output-usd-per-million-tokens` **단일 값**이다(모델별이 아니다).
3. `ConvertDocumentUseCase`는 문서 1건에 호출 1~2회(변환 + 조건부 보정)를 `budget.spend { provider.complete(...) }`로 실행하고 합산 `ConversionUsage(llmCalls, inputTokens, outputTokens)`만 돌려준다. 호출 단위 정보(어느 호출이 보정인지, 각 호출의 비용)는 반환값에 없다. `ProcessConversionJob`(worker)과 `ReconvertUnitService`(api)가 그 결과를 저장한다.
4. 문자 수는 `documents.char_count`(등록 시 확정, 20,000자 상한)에 있다. 크레딧 = `ceil(char_count / 1000)`로 **파생**한다 — 저장하지 않는다.
5. `documents`·`conversions`는 `RetentionPurge`가 보존 기간 뒤 지운다. 운영 진입점은 `rotate-keys`·`migrate` 프로필 패턴이 있다.
6. 계약은 2.19.0, 마이그레이션은 V11까지. 이 묶음은 **2.20.0 · V12**.

## 2. 결정

1. **원장 표 `llm_calls`(V12)** — 호출 1건 = 행 1건. 열: `id uuid`, `conversion_id uuid NULL` (`FK conversions ON DELETE SET NULL`), `document_id uuid NULL` (`SET NULL`), `workspace_id uuid NOT NULL` (`FK workspaces ON DELETE CASCADE`), `user_id uuid NOT NULL` (`FK users ON DELETE CASCADE`), `purpose varchar(16)` (`convert | repair | reconvert`), `provider varchar(32)`, `model varchar(128)`, `input_tokens int`, `output_tokens int`, `latency_ms int NULL`, `estimated_cost_usd numeric(12,6) NULL`, `pricing_input_usd_per_mtok numeric(12,6) NULL`, `pricing_output_usd_per_mtok numeric(12,6) NULL`, `char_count int NOT NULL`(그 호출이 처리한 원문 문자 수 — 변환·보정은 문서 전체, 재변환은 단위 길이), `called_at timestamptz`. 색인 `(workspace_id, called_at)`. **본문·프롬프트·응답·마스킹 항목은 절대 넣지 않는다** — 원장은 숫자와 이름뿐이라 암호화 대상이 아니고 `EncryptedField` 인구조사에 걸리지 않는다.
   - **보존 결정의 정정.** 사용자에게 권고한 「문서 삭제 시 CASCADE」는 `RetentionPurge`가 문서를 지우면 지난달 청구 근거가 함께 사라지는 문제가 있다. 원장은 본문을 담지 않으므로 보존 정책의 이유(개인정보)가 적용되지 않는다. 그래서 문서·변환 참조는 `SET NULL`로 끊고 행은 남긴다. 계정 삭제는 `users` CASCADE로 따라간다(계정이 없으면 청구 대상도 없다). 이 정정은 사용자에게 보고한다.
2. **기록 위치는 application이다** — decorator가 아니다. decorator는 변환 맥락(어느 문서·워크스페이스·목적)을 모른다. `ConvertDocumentUseCase`가 반환하는 `ConversionUsage`에 호출 목록 `calls: List<LlmCallRecord>`(purpose·provider·model·토큰·지연·비용·단가 스냅샷)를 더하고, `ProcessConversionJob`은 완료 저장과 **같은 트랜잭션**에서, `ReconvertUnitService`는 정산 트랜잭션에서 `LlmCallLedger.append(...)` 포트로 기록한다. 실패한 호출(provider 오류)은 토큰이 없으므로 기록하지 않는다 — 벤더가 실패 입력에 과금하는 경우는 §6에 남긴다.
3. **모델별 단가.** `easydoc.llm.pricing.models.<model-id>.input-usd-per-million-tokens / output-…`을 더하고, 기존 단일 값은 **기본값**으로 남긴다. 응답이 보고한 model로 찾고, 없으면 기본값, 그것도 없으면 `null`. 단가는 호출 시점 값을 행에 스냅샷한다 — 단가가 바뀌어도 지난 행의 비용은 다시 계산하지 않는다.
4. **비용 미상은 0이 아니다(권고 ①).** 집계는 `estimated_cost_usd`가 `null`인 호출 수를 `cost_unknown_calls`로 따로 세고, 합계는 알려진 행만 더한다. 화면과 CSV는 미상 건수가 0이 아니면 그 사실을 표시한다.
5. **집계 단위·시간대.** 워크스페이스 × `[from, to]` 날짜 구간(포함), 경계는 `easydoc.usage.zone`(기본 `Asia/Seoul`)의 자정. 항목: `documents`(그 기간에 **등록된** 문서 수 — `documents.created_at`), `characters`(그 문서들의 `char_count` 합), `credits`(문서별 `ceil(char_count/1000)` 합 — 문서마다 올림), `llm_calls`, `input_tokens`, `output_tokens`, `estimated_cost_usd`(문자열 소수, 알려진 행 합), `cost_unknown_calls`, `by_purpose[{purpose, llm_calls, input_tokens, output_tokens, estimated_cost_usd}]`. 재변환은 크레딧을 쓰지 않는다 — 호출 예산이 상한이다(§3.3).
6. **계약 2.20.0.** `GET /workspaces/{workspace_id}/usage?from&to` — 소유자만, 아니면 404. `from`·`to` 는 `YYYY-MM-DD`, 생략 시 이번 달 1일~오늘, `to < from`·범위 366일 초과·형식 오류는 422. 응답 스키마 `WorkspaceUsageResponse`(위 5 항목, snake_case). 비용 필드는 **문자열**(계약의 `x-…` 규칙대로 소수를 float로 싣지 않는다).
7. **운영 리포트 프로필 `usage-report`.** `rotate-keys`와 같은 방식으로 기동해 CSV를 쓰고 종료한다: `--from --to --out` (기본 지난달, `./usage-report.csv`). 행 = 워크스페이스 1건: `workspace_id, workspace_name, owner_email, documents, characters, credits, llm_calls, input_tokens, output_tokens, estimated_cost_usd, cost_unknown_calls`. 소유자 이메일은 청구서 발송에 필요한 운영 출력이므로 싣되, 로그에는 남기지 않는다. 종료 코드 0/1.
8. **프런트.** `/usage` 화면: 워크스페이스 선택기(현재 워크스페이스), 월 선택(이번 달·지난달·직접 입력), 표(문서·문자·크레딧·호출·토큰·예상 비용·미상 건수)와 목적별 표. 비용은 USD 표시에 「예상」 라벨. 접근성: 표는 `<table>`에 캡션, 기간 컨트롤은 라벨.

## 3. 슬라이스

- **U1 원장 (M, core·application·infrastructure·worker).** V12, `LlmCallRecord`·`LlmCallPurpose`(core), `LlmCallLedger` 포트 + Jdbc 어댑터, `ConvertDocumentUseCase`가 호출 목록을 반환(합산 필드는 그대로), `ProcessConversionJob`·`ReconvertUnitService`가 기록, 모델별 단가 + 스냅샷, 기존 로그 관측 불변. 테스트: 변환 1회·보정 포함 2회·재변환 1회 각각 행 수·purpose·비용; 단가 미상 `null`; 실 Postgres에서 문서 삭제 후 행 잔존(`SET NULL`); `SensitiveToStringReachTest` 인구조사; `OwnershipPredicateGuardTest`는 대상이 아님(암호 열 없음)을 KDoc에 적는다.
- **U2 집계·계약·프런트 (M).** `UsageQueryService`(application) + Jdbc 집계 쿼리(경계는 zone 기준), 계약 2.20.0, 컨트롤러(422 규칙은 `x-request-field-constraints`가 아니라 query 규칙 — 계약에 기존 query 검증 표기 방식을 따른다), 프런트 `/usage`. 테스트: 경계 자정, 다른 사용자 404, 빈 기간 0, 비용 미상 표기, Vitest.
- **U3 운영 리포트 (S).** `usage-report` 프로필, CSV 작성, 종료 코드, `docs/pilot-runbook.md`에 청구 절차 한 단락(월초 리포트 → 청구서 → 계좌이체 확인 → 세금계산서 수동). U2의 집계 서비스를 재사용하므로 U2 뒤.

## 4. 수용 기준

- 문서 1건을 변환(보정 포함)하면 `llm_calls`에 `convert`·`repair` 2행, 재변환 1회에 `reconvert` 1행이 남고, 각 행의 비용이 설정 단가와 토큰으로 재계산했을 때 일치한다.
- 문서를 보존 만료로 지운 뒤에도 그 달의 워크스페이스 집계가 같다.
- 단가를 비워 두고 변환하면 `estimated_cost_usd`가 `null`이고 집계의 `cost_unknown_calls`가 1 늘며 합계에 0으로 섞이지 않는다.
- `GET …/usage`는 다른 사용자에게 404, 366일 초과에 422.
- `usage-report` 프로필이 지난달 CSV를 쓰고 0으로 종료한다.

## 5. 범위 밖

크레딧 잔액·차감·거절(다음 묶음), PG 결제, 세금계산서 요청 기록, 어드민 화면, 실패 호출의 벤더 과금.

## 6. 리스크

1. 벤더가 실패 호출의 입력 토큰에 과금하면 원장이 실제보다 적게 센다 — 로그 관측에는 남으므로 차이는 추적 가능하다.
2. 원장 쓰기가 완료 저장 트랜잭션에 들어가므로 원장 실패 = 변환 실패다. 의도한 결합이다(청구 근거 없는 완료를 만들지 않는다).
3. 모델별 단가 키는 응답이 보고한 model 문자열이라 벤더가 표기를 바꾸면 기본값으로 떨어진다 — 스냅샷 열이 그 사실을 드러낸다.
