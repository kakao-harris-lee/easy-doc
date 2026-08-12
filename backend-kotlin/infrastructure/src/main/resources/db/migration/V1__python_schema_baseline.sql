-- Python(Alembic) 스키마 기준선. 계획 §4.2-3 "빈 DB용 V1__python_schema_baseline.sql".
--
-- ## 이 파일이 재현하는 것
--
-- `migrations/versions/0001_users.py` ~ `0006_workspaces.py` 를 실제 PostgreSQL 16에
-- 적용한 결과다. 마이그레이션 파일을 읽어 추론한 것이 아니라, 빈 DB에
-- `uv run alembic upgrade head` 를 돌려 얻은 스키마를 그대로 옮겼다
-- (계획 §4.2-2: "README에 0003 제자리 수정 이력이 있으므로 파일만 믿지 않는다").
--
-- 동일성은 문서가 아니라 테스트가 지킨다 — `PythonSchemaBaselineTest` 가 이 스크립트를
-- 적용한 스키마의 지문을 `db/baseline/python-schema-fingerprint.txt` 와 대조하고,
-- 그 지문은 Alembic 실행 결과에서 뽑았다.
--
-- ## 바꾸지 말 것
--
-- 테이블·컬럼·제약·인덱스 **이름**과 **컬럼 순서**를 바꾸지 않는다(계획 §4.2).
-- 컬럼 순서까지 맞추는 이유는 Alembic이 `0004`·`0006` 에서 `ADD COLUMN` 으로 붙인
-- 컬럼들이 뒤쪽 서수를 갖기 때문이다. 순서가 어긋나면 지문 대조에서 바로 드러난다.
--
-- Kotlin 전용 변경은 **V2부터** 붙인다. 이 파일은 "Python이 만든 스키마"의 정의이므로
-- 신규 컬럼이 들어오면 정의 자체가 흔들리고, 기존 Alembic DB를 V1으로 baseline 할 때
-- 그 신규 컬럼이 영원히 만들어지지 않는다.
--
-- ## alembic_version 을 만들지 않는다
--
-- 계획 §4.2-7: "Python 제거 전까지 alembic_version 은 보존하고 Kotlin이 수정하지 않는다."
-- 만들지도 지우지도 읽지도 않는다. 그 테이블은 Alembic의 소유물이다.

-- pgvector. 0001 이 스키마 이력 맨 앞에서 한 번 보장한다(로컬·CI·운영이 같은 상태가 되도록).
-- 확장은 데이터베이스 전역 자원이라 DROP 하지 않는다.
CREATE EXTENSION IF NOT EXISTS vector;

-- --- users (0001, 0002) ------------------------------------------------------
CREATE TABLE users (
    id uuid NOT NULL,
    email character varying(255) NOT NULL,
    password_hash character varying(255) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    -- 0002: 이메일 정규화는 AuthService가 하지만, 서비스를 거치지 않는 경로(운영 스크립트,
    -- 데이터 이관)가 대소문자만 다른 값을 넣으면 unique 인덱스가 무력해진다.
    CONSTRAINT ck_users_email_lowercase CHECK (email = lower(email))
);

-- unique 인덱스 하나가 유일성 제약과 조회 인덱스를 겸한다(로그인 시 이메일 조회).
-- UNIQUE 제약이 아니라 인덱스인 것이 Alembic 결과와 일치하는 형태다.
CREATE UNIQUE INDEX ix_users_email ON users USING btree (email);

-- --- workspaces (0006) -------------------------------------------------------
-- documents 가 FK로 참조하므로 documents 보다 먼저 만든다. Alembic은 0006에서 나중에
-- 만들었지만, 빈 DB에 한 번에 적용하는 baseline 에서는 참조 순서만 지키면 결과가 같다.
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

-- --- documents (0003, 0005, 0006) --------------------------------------------
-- 원문은 bytea(Fernet 암호문)로 저장한다 — 이 테이블을 직접 조회해도 본문이 보이지 않아야
-- 한다 (master-plan 3.2).
--
-- 컬럼 순서 주의: workspace_id 는 0006 이 ADD COLUMN 으로 붙여 **마지막(서수 10)** 이다.
CREATE TABLE documents (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    title character varying(255) NOT NULL,
    source_format character varying(16) NOT NULL,
    source_text_encrypted bytea NOT NULL,
    -- Fernet 토큰에는 키 식별자가 없다 — 키 교체 때 재암호화 대상을 고르려면 세대 번호를
    -- 함께 저장해 두는 수밖에 없다 (app/privacy/crypto.py).
    key_version smallint DEFAULT 1 NOT NULL,
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
        REFERENCES workspaces (id)
);

-- 목록 조회(내 문서)와 소유자 검증이 전부 user_id로 들어온다.
CREATE INDEX ix_documents_user_id ON documents USING btree (user_id);
CREATE INDEX ix_documents_workspace_id ON documents USING btree (workspace_id);
-- 0005: 보존 만료 파기 잡이 retention_expires_at 으로 대상을 고른다. 인덱스가 없으면
-- 전수 스캔 + 정렬이 되고, 문서가 쌓일수록 새벽 잡 한 번이 테이블을 오래 붙잡는다.
-- 부분 인덱스를 쓰지 않는 이유: 기준값이 now() 라 조건이 매일 움직이고, IMMUTABLE 하지
-- 않은 함수는 인덱스 술어에 넣을 수 없다.
CREATE INDEX ix_documents_retention_expires_at ON documents USING btree (retention_expires_at);

-- --- conversions (0003, 0004) ------------------------------------------------
-- 컬럼 순서 주의: edited_text_encrypted·reviewed_at 은 0004 가 ADD COLUMN 으로 붙여
-- **마지막(서수 15, 16)** 이다.
CREATE TABLE conversions (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    status character varying(16) DEFAULT 'pending' NOT NULL,
    easy_text_encrypted bytea,
    masked_items_encrypted bytea,
    key_version smallint DEFAULT 1 NOT NULL,
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
    -- 0004: 담당자 검수 수정본을 AI 초안과 별도 컬럼에 담는다. 초안을 덮어쓰지 않는 이유는
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
        REFERENCES documents (id) ON DELETE CASCADE
);

CREATE INDEX ix_conversions_document_id ON conversions USING btree (document_id);
