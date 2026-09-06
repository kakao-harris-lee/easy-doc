-- 비밀번호 재설정 코드(backlog §1.4 다음 조각 — 소셜 전용 계정의 비밀번호 설정/재설정).
--
-- `email_verification_codes`(V7)와 **같은 모양·같은 의미론**이다 — 6자리 숫자 코드를
-- salt를 곁들인 해시로만 저장하고, TTL·재발송 쿨다운·시도 상한을 같은 규칙으로 지킨다.
-- 애플리케이션 쪽 공통 메커니즘은 `JdbcOneTimeCodeStore`(구 `JdbcVerificationCodeStore`
-- 본문을 옮긴 것)로 추출했고, 이 테이블과 `email_verification_codes`는 그 위에 테이블
-- 이름만 다르게 얹은 두 어댑터(`JdbcVerificationCodeStore`·`JdbcPasswordResetCodeStore`)가
-- 각각 접근한다.
--
-- 별도 테이블을 쓰는 이유: 이메일 인증 코드와 비밀번호 재설정 코드는 발급 계기와 소비
-- 시점이 다르다(가입 직후 자동 발급 대 사용자가 원할 때 요청) — 같은 테이블에 용도
-- 컬럼을 추가해 나누면 "이 사용자의 활성 코드"를 구하는 질의마다 용도 조건을 잊지 않아야
-- 하고, 한쪽 재발급이 다른 쪽 활성 코드를 실수로 함께 무효화할 위험이 생긴다.
CREATE TABLE password_reset_codes (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    code_hash text NOT NULL,
    salt text NOT NULL,
    expires_at timestamptz NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    -- `email_verification_codes.consumed_at`과 같은 규약이다. `NULL` = 아직 유효(활성).
    consumed_at timestamptz NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_password_reset_codes PRIMARY KEY (id),
    CONSTRAINT fk_password_reset_codes_user_id_users FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);

-- 재요청 쿨다운 조회와 활성 코드 조회가 둘 다 (user_id, created_at 최신순)으로 들어온다
-- — `email_verification_codes`와 같은 인덱스 근거.
CREATE INDEX ix_password_reset_codes_user_id_created_at
    ON password_reset_codes USING btree (user_id, created_at DESC);
