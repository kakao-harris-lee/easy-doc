-- 실패 호출 원장 추적(백로그 「실패 호출 원장 추적」, 계획
-- `docs/plans/2026-09-07-usage-ledger-and-report.md` §6 리스크 1, 2026-09-08).
--
-- **지금까지 완성 응답을 받지 못한 호출(`LlmProviderException`)은 `llm_calls`에 아무
-- 행도 남기지 않았다** — U1 결정 2 「완료 자체가 없는 provider 예외만 기록하지 않는다」.
-- 그런데 벤더는 실패한 요청의 입력 토큰에도 과금할 수 있어(§6 리스크 1), 그 청구를
-- 이 원장만으로는 대조(reconcile)할 수 없었다. 이 마이그레이션은 그 배제를 뒤집는다 —
-- provider 예외도 이제 원장 행 하나를 남기되(`outcome = 'provider_error'`), 실제 토큰이
-- 없으므로 `input_tokens`·`output_tokens`는 0, `estimated_cost_usd`는 미상(`NULL`)으로
-- 남긴다(0달러가 아니다 — CLAUDE.md 「미설정은 null」).
--
-- **`outcome` 열 신설, 기본값 `completed`.** 지금까지 쌓인 행은 전부 완성 응답을
-- 받은 호출이었으므로(그 경우만 기록했다) `DEFAULT 'completed'`가 과거 행의 실제 값과
-- 정확히 같다 — 백필이 필요 없다.
--
-- **`failure_class` 열 신설, `provider_error`일 때만 값이 있다.** 예외 클래스의 단순
-- 이름만 담는다 — 벤더 응답 문구가 실릴 수 있는 예외 메시지는 절대 넣지 않는다(V14
-- 머리주석 「본문·프롬프트·응답·마스킹 항목은 절대 넣지 않는다」와 같은 경계). **오늘은
-- 항상 `LlmProviderException`이다** — `OpenAiProvider`·`AnthropicProvider` 어느 쪽도
-- 서브타입(`LlmTruncatedException`·`LlmEmptyResultException`)을 던지지 않는다(둘 다
-- `failure()` 헬퍼에서 기반 클래스만 던진다). 열이 `varchar(64)`인 것은 그 서브타입까지
-- 대비한 여유이지, 오늘 그 값이 실제로 실린다는 뜻이 아니다.
--
-- **`model` 열의 `NOT NULL`을 걷어낸다.** provider 예외는 응답 자체가 없어 어느 모델이
-- 처리했는지 알 수 없다 — 실제로 모르는 값을 빈 문자열 같은 거짓 값으로 채우지 않고
-- `LlmAttribution.model`(완성 요청이 예외로 끝나면 `null`)과 같은 규약을 따른다.
--
-- 집계(U2 `JdbcUsageReadRepository`·U3 `JdbcUsageReportRepository`)는 `outcome =
-- 'completed'` 인 행만 문서·문자·크레딧·토큰·비용에 합산한다 — 실패 호출은 실제로 쓴
-- 자원이 없으므로(토큰 0, 비용 미상) 청구·크레딧 집계를 왜곡하지 않는다. 대신 그
-- 집계가 `failed_calls`로 실패 건수를 따로 낸다 — 이 마이그레이션이 원장에 남긴 값을
-- 화면·리포트가 숨기지 않는다.
ALTER TABLE llm_calls
    ALTER COLUMN model DROP NOT NULL;

ALTER TABLE llm_calls
    ADD COLUMN outcome character varying(16) NOT NULL DEFAULT 'completed';

ALTER TABLE llm_calls
    ADD COLUMN failure_class character varying(64) NULL;

-- outcome 값 목록을 애플리케이션 상수에서 가져오지 않고 SQL로 직접 적는다 — V14
-- ck_llm_calls_purpose_valid 와 같은 이유(V1 머리주석 참고).
ALTER TABLE llm_calls
    ADD CONSTRAINT ck_llm_calls_outcome_valid
        CHECK (outcome IN ('completed', 'provider_error'));
