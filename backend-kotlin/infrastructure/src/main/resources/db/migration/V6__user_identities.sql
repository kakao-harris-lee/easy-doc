-- 소셜 로그인(Google, backlog §1.4 P0-1) — 사용자 신원 연결과 OAuth state 저장.
--
-- ## `users.password_hash` 가 nullable 이 되는 이유
--
-- 소셜 로그인으로만 가입한 사용자는 비밀번호가 없다. V1 은 `NOT NULL` 로 정의했으므로
-- 여기서 뗀다. **불변식은 코드가 진다**: 비밀번호가 없는 계정은 반드시 `user_identities`에
-- 신원이 최소 하나 있어야 한다 — SQL `CHECK` 로는 다른 테이블을 참조할 수 없어 여기서
-- 표현할 수 없고, `SocialLoginService`(신원 연결 시 비밀번호 없이 생성)와
-- `AuthService.signup`(항상 비밀번호로 생성)이 그 불변식을 함께 지킨다.
ALTER TABLE users
    ALTER COLUMN password_hash DROP NOT NULL;

-- --- user_identities --------------------------------------------------------
-- 사용자 하나가 여러 제공자에 연결될 수 있다(다대일: 여러 신원 → 한 사용자).
CREATE TABLE user_identities (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    provider text NOT NULL,
    provider_user_id text NOT NULL,
    -- 제공자가 준 이메일 스냅샷. 사용자 계정의 로그인 이메일(`users.email`)과 독립이다 —
    -- 신원 연결 시점 이후 어느 한쪽이 바뀌어도 다른 쪽을 되짚어 고치지 않는다.
    email text NULL,
    email_verified boolean NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_user_identities PRIMARY KEY (id),
    -- ondelete=CASCADE: 계정을 지우면 연결된 신원도 함께 사라진다(V1 documents 와 같은 정책).
    CONSTRAINT fk_user_identities_user_id_users FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    -- 같은 제공자 계정을 두 사용자에 중복 연결하지 못한다 —
    -- `SocialLoginService`가 콜백마다 먼저 조회하는 유일성 축이다.
    CONSTRAINT uq_user_identities_provider_provider_user_id UNIQUE (provider, provider_user_id),
    -- 알 수 없는 제공자 이름이 조용히 들어가는 것을 막는다. `kakao`·`naver` 는 예약만
    -- 됐고 아직 어댑터가 없다 — 값이 들어오면 그 자체가 회귀다.
    CONSTRAINT ck_user_identities_provider_valid CHECK (provider IN ('google', 'kakao', 'naver'))
);

-- 계정 삭제·로그인 조회가 user_id 로 들어온다.
CREATE INDEX ix_user_identities_user_id ON user_identities USING btree (user_id);

-- --- oauth_states -------------------------------------------------------
-- Authorization Code 흐름의 CSRF 방지 `state` 와 리플레이 방지 `nonce`. API 가 여러
-- 인스턴스로 뜨므로(무상태 배포) 인메모리가 아니라 여기 저장한다 — `SocialLoginService`
-- `start`가 만들고 `callback`이 단발 소비한다.
CREATE TABLE oauth_states (
    id uuid NOT NULL,
    provider text NOT NULL,
    state character varying(255) NOT NULL,
    nonce character varying(255) NOT NULL,
    redirect_uri text NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    -- 단발 소비 표식. `NULL` = 아직 안 씀. `JdbcOAuthStateStore.consume` 이 이 컬럼을
    -- 원자적으로 검사·기록한다(`UPDATE ... WHERE consumed_at IS NULL ... RETURNING`).
    consumed_at timestamp with time zone NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_oauth_states PRIMARY KEY (id)
);

-- `consume` 조회·유일성이 (provider, state) 로 들어온다. 발급 시점에 같은 제공자
-- 안에서 값이 겹치면(사실상 불가능한 충돌이지만) 재발급하게 만드는 안전망이기도 하다.
CREATE UNIQUE INDEX ix_oauth_states_provider_state ON oauth_states USING btree (provider, state);

-- 만료 파기 잡이 이 컬럼으로 대상을 고른다 — `documents.retention_expires_at` 과 같은
-- 이유로 인덱스를 둔다(부분 인덱스를 쓰지 않는 이유도 같다: `now()` 는 IMMUTABLE 이 아니다).
-- **만료 파기 잡 자체는 이 변경 단위 밖이다** — 10분 TTL 인 소량 행이라 당장 급하지
-- 않고, 다음 작업 단위에서 `retention_expires_at` 파기 잡과 함께 묶는 편이 낫다.
CREATE INDEX ix_oauth_states_expires_at ON oauth_states USING btree (expires_at);
