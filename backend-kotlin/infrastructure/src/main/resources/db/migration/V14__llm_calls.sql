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
-- **보존 결정의 정정 — 3차(2026-09-08 리뷰, U1이 아직 병합되지 않아 이 표를 직접 고친다).**
-- 1차 결정(`conversion_id`·`document_id`·`workspace_id` 모두 CASCADE)과 2차 정정
-- (셋 다 SET NULL)은 둘 다 "참조를 끊어도 행은 남는다"는 목표는 맞았지만, **SET NULL
-- 자체가 청구 근거를 깎는다**는 것을 놓쳤다. U2가 워크스페이스 사용량의 `documents`·
-- `characters`·`credits`를 `documents` 표가 아니라 **이 원장**에서 유도하도록 다시
-- 설계됐다(`JdbcUsageReadRepository`, document_id 로 distinct 해서 문서 수·문자 수를
-- 센다) — 그런데 `document_id ON DELETE SET NULL` 이면 문서가 보존 만료로 지워지는
-- 순간 그 문서가 낸 지난달 청구 근거(문자 수·크레딧)가 원장에서까지 함께 사라진다.
-- **청구 근거를 남기려고 만든 원장이 청구 근거를 스스로 지우는 모순이다.**
--
-- 그래서 `conversion_id`·`document_id`는 **FK 자체를 두지 않는다.** 이 표는 append-only
-- 감사 로그로 다룬다 — 쓸 때는 항상 실제 대상을 가리키지만, 그 대상이 나중에 지워져도
-- (보존 파기·즉시 삭제) 열의 값은 그대로 남는다("고아 참조"가 곧 의도다. 없는 대상을
-- 다시 조인해 보여줄 필요가 없으므로 FK 부재가 무결성 결함이 아니다). 문서 단위 청구
-- 근거(문자 수)도 같은 이유로 `document_char_count`에 **원장 행 자체에** 스냅샷을
-- 남긴다 — `documents` 표가 사라져도 이 열만으로 집계가 선다.
--
-- **`workspace_id`는 여전히 `SET NULL`이다** — 워크스페이스 단위 조회
-- (`readWorkspaceUsage`)는 그 워크스페이스가 없어지면 조회 대상 자체가 없으므로 참조를
-- 끊어도 청구 근거가 사라지지 않는다(사용자 단위 리포트, U3가 그 행을 다룬다).
-- `user_id`만 `CASCADE`다 — 계정 자체가 없으면 청구 대상도 없다.
CREATE TABLE llm_calls (
    id uuid NOT NULL,
    -- FK 없음(위 3차 정정) — 참조 대상이 지워져도 값은 그대로 남는다.
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
    -- 그 호출이 실제로 받은 **프롬프트 입력**의 문자 수다 — `documents.char_count`
    -- (원문, 등록 시 확정)가 **아니다**. 변환·보정 두 행은 같은 문서를 대상으로 **각자
    -- 문서 전체 본문의 길이**를 그대로 담으므로(문단 하나만 처리하는 것이 아니다), 문서
    -- 1건의 크레딧·비용을 이 열의 합으로 구하면 안 된다 — 문서 단위 크레딧은 아래
    -- `document_char_count` 에서 파생한다(계획 §2 결정 5 「크레딧」, 2026-09-08 리뷰로
    -- `documents` 표 대신 이 열을 정본으로 삼도록 정정). 재변환은 문서 전체가 아니라
    -- 그 단위 하나만의 길이다. **2026-09-07 정정: 개인정보 마스킹이 제거돼(PR #58)
    -- 이 값은 더 이상 마스킹된 입력이 아니라 평문 프롬프트 입력 그대로의 길이다.**
    char_count integer NOT NULL,
    -- 이 호출이 속한 **문서**의 `documents.char_count` 스냅샷(2026-09-08 리뷰 신설) —
    -- 위 `char_count`(호출별 프롬프트 입력 길이)와는 다른 축이다. U2 집계가 문서 수·문자
    -- 수·크레딧을 구하는 유일한 자리이며, `documents` 표를 참조하지 않는다 — 문서가
    -- 지워져도(보존 파기) 이 값은 원장에 남아 청구 근거가 유지된다. 같은 문서를 대상으로
    -- 하는 여러 행(변환·보정·재시도)이 같은 값을 반복해 담으므로, 문서 단위 합계를 낼
    -- 때는 `document_id` 로 distinct 한 뒤에만 합한다(`JdbcUsageReadRepository`).
    document_char_count integer NOT NULL,
    called_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_llm_calls PRIMARY KEY (id),
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
    CONSTRAINT ck_llm_calls_char_count_non_negative CHECK (char_count >= 0),
    CONSTRAINT ck_llm_calls_document_char_count_non_negative CHECK (document_char_count >= 0)
);

-- 워크스페이스·기간별 집계(U2)가 이 두 열로 범위를 좁힌다.
CREATE INDEX ix_llm_calls_workspace_id_called_at ON llm_calls USING btree (workspace_id, called_at);

-- U2 문서 단위 집계(distinct document_id)가 이 열로 좁힌다 — workspace_id·called_at
-- 색인은 GROUP/DISTINCT 대상 열(document_id)을 포함하지 않아 그 작업을 돕지 못한다.
CREATE INDEX ix_llm_calls_document_id ON llm_calls USING btree (document_id);

-- U3 운영 리포트(JdbcUsageReportRepository.REPORT_SQL)가 워크스페이스로 좁히지 않고
-- called_at 구간만으로 **소유자 전체**를 훑는다 — ix_llm_calls_workspace_id_called_at는
-- workspace_id가 선두 컬럼이라 이 질의(모든 workspace_id를 대상으로 한 날짜 범위
-- 스캔)를 돕지 못한다.
CREATE INDEX ix_llm_calls_called_at ON llm_calls USING btree (called_at);
