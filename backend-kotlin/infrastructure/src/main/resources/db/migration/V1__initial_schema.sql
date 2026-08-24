-- 초기 스키마. Kotlin/Spring Boot 가 유일한 제품 런타임이고, 이 파일이 그 스키마의 정본이다.
--
-- 여러 마이그레이션(V1~V5)에 걸쳐 쌓인 이전 이력을 하나로 접었다. 컬럼·제약·인덱스는
-- 그 이력이 만든 **현재 상태**만 담고, 더 이상 존재하지 않는 값(예: 폐기된 `fernet-v1`
-- 암호화 방식 이름)이나 단계적 적용 절차(DEFAULT를 걸었다가 나중에 없애는 등)는 담지
-- 않는다 — 적용 대상이 항상 빈 DB이므로 그 절차가 더는 필요 없다.

-- pgvector. 사전 RAG 등 벡터 컬럼을 나중에 추가할 때 DB 교체 없이 확장하려고
-- 처음부터 이 확장을 보장한다.
CREATE EXTENSION IF NOT EXISTS vector;

-- --- users --------------------------------------------------------------------
CREATE TABLE users (
    id uuid NOT NULL,
    email character varying(255) NOT NULL,
    password_hash character varying(255) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    -- 서비스 코드는 이메일을 정규화하지만, 서비스를 거치지 않는 경로(운영 스크립트,
    -- 데이터 이관)가 대소문자만 다른 값을 넣으면 아래 unique 인덱스가 무력해진다.
    CONSTRAINT ck_users_email_lowercase CHECK (email = lower(email))
);

-- unique 인덱스 하나가 유일성 제약과 조회 인덱스를 겸한다(로그인 시 이메일 조회).
CREATE UNIQUE INDEX ix_users_email ON users USING btree (email);

-- --- workspaces -----------------------------------------------------------
-- documents 가 FK로 참조하므로 documents 보다 먼저 만든다.
CREATE TABLE workspaces (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    name character varying(50) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_workspaces PRIMARY KEY (id),
    -- 같은 사용자 안에서만 이름 중복을 막는다.
    CONSTRAINT uq_workspaces_user_id_name UNIQUE (user_id, name),
    -- ondelete=CASCADE: 계정을 지우면 작업 공간도 함께 사라진다.
    CONSTRAINT fk_workspaces_user_id_users FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);

-- 목록 조회와 소유자 검증이 전부 user_id로 들어온다.
CREATE INDEX ix_workspaces_user_id ON workspaces USING btree (user_id);

-- --- documents ------------------------------------------------------------
-- 원문은 bytea(AEAD 암호문)로 저장한다 — 이 테이블을 직접 조회해도 본문이 보이지
-- 않아야 한다 (master-plan 3.2, 저장 암호화).
CREATE TABLE documents (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    title character varying(255) NOT NULL,
    source_format character varying(16) NOT NULL,
    source_text_encrypted bytea NOT NULL,
    -- 암호문 자체에는 어떤 방식·키 세대로 썼는지가 남지 않는다 — 키 교체 때
    -- 재암호화 대상을 고르고, 복호화 시점에 올바른 키를 찾으려면 별도 컬럼이 필요하다.
    -- 코드 쪽 정본은 `core/crypto/StoredContent.kt`의 `EncryptionScheme` 이다.
    encryption_scheme character varying(16) NOT NULL,
    key_version smallint NOT NULL,
    char_count integer NOT NULL,
    -- 기본 30일 보존 (master-plan 3.2).
    retention_expires_at timestamp with time zone DEFAULT (now() + interval '30 days') NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    workspace_id uuid NOT NULL,
    CONSTRAINT pk_documents PRIMARY KEY (id),
    -- ondelete=CASCADE: 계정을 지우면 문서·변환이 함께 사라진다(개인정보 삭제).
    CONSTRAINT fk_documents_user_id_users FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    -- ondelete 를 주지 않는다(NO ACTION) — 문서가 든 작업 공간은 DB가 지우지 못하게 막는다.
    CONSTRAINT fk_documents_workspace_id_workspaces FOREIGN KEY (workspace_id)
        REFERENCES workspaces (id),
    -- 알 수 없는 방식 이름이 조용히 들어가는 것을 막는다. 새 방식을 도입할 때는 이
    -- 목록을 늘리는 마이그레이션을 쓰게 되므로, 전환 시점이 스키마 이력에 남는다.
    CONSTRAINT ck_documents_encryption_scheme_valid
        CHECK (encryption_scheme IN ('aes256gcm-v1')),
    -- 세대 번호는 associated data 에 들어가고 복호화 시점의 키 조회 키다. 설정에
    -- 있을 수 없는 번호(0 이하)가 적힌 행은 영원히 열리지 않는다 — 상한(32767)은
    -- smallint 타입이 이미 막으므로 하향만 막는다.
    CONSTRAINT ck_documents_key_version_positive CHECK (key_version > 0)
);

-- 목록 조회(내 문서)와 소유자 검증이 전부 user_id로 들어온다.
CREATE INDEX ix_documents_user_id ON documents USING btree (user_id);
CREATE INDEX ix_documents_workspace_id ON documents USING btree (workspace_id);
-- 보존 만료 파기 잡이 retention_expires_at 으로 대상을 고른다. 인덱스가 없으면
-- 전수 스캔 + 정렬이 되고, 문서가 쌓일수록 새벽 잡 한 번이 테이블을 오래 붙잡는다.
-- 부분 인덱스를 쓰지 않는 이유: 기준값이 now() 라 조건이 매일 움직이고, IMMUTABLE 하지
-- 않은 함수는 인덱스 술어에 넣을 수 없다.
CREATE INDEX ix_documents_retention_expires_at ON documents USING btree (retention_expires_at);

-- --- conversions ------------------------------------------------------------
CREATE TABLE conversions (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    status character varying(16) DEFAULT 'pending' NOT NULL,
    easy_text_encrypted bytea,
    masked_items_encrypted bytea,
    encryption_scheme character varying(16) NOT NULL,
    key_version smallint NOT NULL,
    -- 자리표시자 라벨만 담기므로 개인정보가 아니다 — 평문 JSONB로 둔다.
    missing_placeholders jsonb DEFAULT '[]'::jsonb NOT NULL,
    provider_name character varying(32),
    model character varying(128),
    input_tokens integer,
    output_tokens integer,
    -- 예외 클래스명만 담는다(본문·모델 응답 금지).
    failure_code character varying(64),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    -- 담당자 검수 수정본을 AI 초안과 별도 컬럼에 담는다. 초안을 덮어쓰지 않는 이유는
    -- 수정률 KPI의 기준선이 초안이기 때문이다.
    edited_text_encrypted bytea,
    reviewed_at timestamp with time zone,
    CONSTRAINT pk_conversions PRIMARY KEY (id),
    -- status 값 목록을 애플리케이션 상수에서 가져오지 않고 SQL로 직접 적는다 —
    -- 마이그레이션은 그 시점 스키마의 스냅샷이라, 나중에 상태가 추가돼도 이미 적용된
    -- 이 스크립트의 내용이 따라 바뀌면 안 된다.
    CONSTRAINT ck_conversions_status_valid
        CHECK (status IN ('pending', 'processing', 'done', 'failed')),
    CONSTRAINT fk_conversions_document_id_documents FOREIGN KEY (document_id)
        REFERENCES documents (id) ON DELETE CASCADE,
    CONSTRAINT ck_conversions_encryption_scheme_valid
        CHECK (encryption_scheme IN ('aes256gcm-v1')),
    CONSTRAINT ck_conversions_key_version_positive CHECK (key_version > 0)
);

CREATE INDEX ix_conversions_document_id ON conversions USING btree (document_id);

-- --- conversion_jobs ----------------------------------------------------------
-- 변환 작업 큐 — **PostgreSQL 테이블 하나**. Redis·ARQ 를 쓰지 않는다.
--
-- 큐를 위해 두 번째 저장소를 운영하지 않는 것이 스택 결정이고(프로젝트 `CLAUDE.md`
-- 기술 스택), 그 결정이 원자성을 함께 준다 — 문서·변환·작업 행을 같은 트랜잭션에서
-- 저장하면 "DB 커밋 성공, 큐 등록 실패" 간극이 구조적으로 사라진다.
--
-- `FOR UPDATE SKIP LOCKED` 획득, lease 만료 재처리, backoff, 재시도 상한 같은 소비
-- 쪽 규칙은 이 파일이 정하지 않는다 — worker 구현이 정한다. 여기서 만드는 것은 그
-- worker 가 읽을 자리와, 그 자리에 값이 어떤 모양으로 들어갈 수 있는지의 제약뿐이다.
CREATE TABLE conversion_jobs (
    -- 작업 식별자를 변환 식별자로 고정한다. 별도 작업 id 를 두지 않는 것이 등록을
    -- 멱등하게 만드는 방법이다 — 계약 `POST /documents`가 이미 그렇게 적었다
    -- ("등록은 작업 id를 변환 id로 고정해 멱등하다"). 같은 변환을 두 번 등록해도 작업은 하나다.
    conversion_id uuid NOT NULL,

    -- 작업의 처리 상태. `conversions.status`와 다른 축이다: 저쪽은 사용자에게 보이는
    -- 변환 결과의 상태이고, 이쪽은 큐가 이 행을 다시 집어야 하는지의 판정이다. 둘을 한
    -- 컬럼으로 합치면 "사용자에게는 실패인데 큐는 재시도해야 하는" 상태를 표현할 수 없다.
    state character varying(16) NOT NULL,

    -- 시도 횟수. 재시도 상한 판정에 쓴다.
    attempts integer NOT NULL,

    -- 이 시각 이전에는 집지 않는다. backoff 가 이 값을 민다.
    next_attempt_at timestamp with time zone NOT NULL,

    -- lease 를 쥔 worker 의 식별자와 만료 시각. 만료된 lease 는 다른 worker 가 회수한다 —
    -- 그래서 worker 가 죽어도 작업이 영영 묶이지 않는다.
    --
    -- 식별자를 character varying(64) 로 둔다. 호스트명·컨테이너 id 가 들어갈 자리이고
    -- 사용자 데이터가 아니다.
    lease_owner character varying(64),
    lease_until timestamp with time zone,

    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,

    CONSTRAINT pk_conversion_jobs PRIMARY KEY (conversion_id),

    -- 변환이 사라지면 작업도 사라진다. 문서 삭제(즉시 파기)가 documents → conversions
    -- → conversion_jobs 로 이어지도록 CASCADE 를 잇는다. 이것이 없으면 지워진 변환을
    -- 가리키는 작업이 남아 worker 가 매번 없는 행을 읽으러 간다.
    CONSTRAINT fk_conversion_jobs_conversion_id_conversions FOREIGN KEY (conversion_id)
        REFERENCES conversions (id) ON DELETE CASCADE,

    -- 상태 값 목록을 애플리케이션 상수에서 가져오지 않고 SQL 로 직접 적는다(위
    -- ck_conversions_status_valid 와 같은 규칙).
    CONSTRAINT ck_conversion_jobs_state_valid
        CHECK (state IN ('ready', 'leased', 'done', 'failed')),

    CONSTRAINT ck_conversion_jobs_attempts_non_negative CHECK (attempts >= 0),

    -- lease 는 두 값이 함께 있거나 함께 없다. 한쪽만 있으면 회수 판정이 성립하지 않는다
    -- (주인은 있는데 만료가 없으면 영원히 묶이고, 만료만 있으면 누가 쥐었는지 모른다).
    CONSTRAINT ck_conversion_jobs_lease_paired
        CHECK ((lease_owner IS NULL) = (lease_until IS NULL))
);

-- 집을 수 있는 작업을 고르는 인덱스. worker 의 획득 질의가 state = 'ready' 와
-- next_attempt_at <= now() 로 들어온다.
--
-- 부분 인덱스의 술어에 state 만 둔다 — now() 는 IMMUTABLE 이 아니라 술어에 넣을 수 없고,
-- 그 조건은 인덱스 스캔이 정렬된 next_attempt_at 으로 걸러 준다.
CREATE INDEX ix_conversion_jobs_ready ON conversion_jobs USING btree (next_attempt_at)
    WHERE state = 'ready';

-- lease 회수 잡이 만료된 것을 찾는다. 부분 인덱스라 정상 작업이 든 행은 이 인덱스에 없다.
CREATE INDEX ix_conversion_jobs_leased ON conversion_jobs USING btree (lease_until)
    WHERE state = 'leased';
