-- LLM 호출 원장(U1, docs/plans/2026-09-07-usage-ledger-and-report.md §2 결정 1).
--
-- 호출 1건 = 행 1건. 지금은 provider·model·토큰·지연·예상 비용을 구조화 로그로만 내고
-- 어디에도 저장하지 않는다(`MetricsLlmProviderDecorator`) — 이 표가 그 값을 남긴다.
--
-- **암호화 대상이 아니다.** 담는 열은 숫자와 이름(provider·model 식별자)뿐이고 본문·
-- 프롬프트·응답·마스킹 항목은 **절대 넣지 않는다**. 그래서 이 표는
-- `kr.easydoc.core.crypto.EncryptedField` 가 아는 표 목록에 들지 않고,
-- `OwnershipPredicateGuardTest`·`EnvelopeColumnWriteGuardTest`(둘 다 그 목록만 훑는다)
-- 인구조사 대상도 아니다 — 두 가드 모두 이 표를 알 필요가 없다(볼 것이 암호문뿐이 아니기
-- 때문이 아니라, 애초에 이 표에 가릴 것이 없기 때문이다).
--
-- **보존 결정의 정정(계획 §2 결정 1, 리뷰로 2차 정정).** 사용자에게 권고했던 「문서 삭제 시
-- CASCADE」는 `RetentionPurge` 가 문서를 지우면 지난달 청구 근거가 함께 사라지는 문제가
-- 있었다. 원장은 본문을 담지 않으므로 보존 정책의 이유(개인정보)가 이 표에는 적용되지
-- 않는다 — 그래서 `conversion_id`·`document_id` 는 대상이 지워져도 참조만 끊고 행은 남기는
-- `SET NULL` 이다. **`workspace_id` 도 같은 이유로 `SET NULL` 이다(1차 결정의 CASCADE 를
-- 뒤집는다)** — 워크스페이스 삭제(문서를 먼저 지운 뒤에만 가능하다,
-- `fk_documents_workspace_id_workspaces` 가 `NO ACTION`)가 그 워크스페이스에 쌓인 청구
-- 근거까지 지우면 안 된다. `user_id` 만 `CASCADE` 로 남는다 — 계정 자체가 없으면 청구
-- 대상도 없다.
CREATE TABLE llm_calls (
    id uuid NOT NULL,
    conversion_id uuid NULL,
    document_id uuid NULL,
    workspace_id uuid NULL,
    user_id uuid NOT NULL,
    purpose character varying(16) NOT NULL,
    provider character varying(32) NOT NULL,
    model character varying(128) NOT NULL,
    input_tokens integer NOT NULL,
    output_tokens integer NOT NULL,
    latency_ms integer NULL,
    -- 단가 미설정은 0달러가 아니라 NULL 이다(프로젝트 CLAUDE.md 「미설정은 null」) — 집계가
    -- 이 NULL 건수를 cost_unknown_calls 로 따로 센다(U2).
    estimated_cost_usd numeric(12, 6) NULL,
    -- 호출 시점 단가 스냅샷. 이후 설정이 바뀌어도 이미 쓴 행은 다시 계산하지 않는다.
    pricing_input_usd_per_mtok numeric(12, 6) NULL,
    pricing_output_usd_per_mtok numeric(12, 6) NULL,
    -- 그 호출이 실제로 받은 **프롬프트 입력**(마스킹된 본문)의 문자 수다 —
    -- `documents.char_count`(원문, 마스킹 전, 등록 시 확정)가 **아니다**. 변환·보정 두 행은
    -- 같은 문서를 대상으로 **각자 전체 마스킹 본문의 길이**를 그대로 담으므로(문단 하나만
    -- 처리하는 것이 아니다), 문서 1건의 크레딧·비용을 이 열의 합으로 구하면 안 된다 — 문서
    -- 단위 크레딧은 `documents.char_count` 에서 파생한다(계획 §2 결정 5 「크레딧」). 재변환은
    -- 문서 전체가 아니라 그 단위(`maskedUnitOf`)만의 길이다.
    char_count integer NOT NULL,
    called_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_llm_calls PRIMARY KEY (id),
    CONSTRAINT fk_llm_calls_conversion_id_conversions FOREIGN KEY (conversion_id)
        REFERENCES conversions (id) ON DELETE SET NULL,
    CONSTRAINT fk_llm_calls_document_id_documents FOREIGN KEY (document_id)
        REFERENCES documents (id) ON DELETE SET NULL,
    CONSTRAINT fk_llm_calls_workspace_id_workspaces FOREIGN KEY (workspace_id)
        REFERENCES workspaces (id) ON DELETE SET NULL,
    CONSTRAINT fk_llm_calls_user_id_users FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    -- purpose 값 목록을 애플리케이션 상수에서 가져오지 않고 SQL로 직접 적는다 — 다른
    -- CHECK 제약(예: conversions.status)과 같은 이유(V1 머리주석 참고).
    CONSTRAINT ck_llm_calls_purpose_valid
        CHECK (purpose IN ('convert', 'repair', 'reconvert')),
    CONSTRAINT ck_llm_calls_input_tokens_non_negative CHECK (input_tokens >= 0),
    CONSTRAINT ck_llm_calls_output_tokens_non_negative CHECK (output_tokens >= 0),
    CONSTRAINT ck_llm_calls_char_count_non_negative CHECK (char_count >= 0)
);

-- 워크스페이스·기간별 집계(U2)가 이 두 열로 범위를 좁힌다.
CREATE INDEX ix_llm_calls_workspace_id_called_at ON llm_calls USING btree (workspace_id, called_at);
