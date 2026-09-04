-- 이메일 인증(backlog §1.4 P0-1/P0-3) — 이메일/비밀번호 가입의 소유 확인.
--
-- ## `users.email_verified_at` 이 grandfather(소급 인증)되는 이유
--
-- 이 기능이 생기기 전에 이미 가입해 쓰던 계정이 배포 직후 전원 문서 변환을 막히면 안 된다.
-- 그래서 V7 적용 시점에 존재하던 모든 행은 지금 시각으로 인증 완료 처리한다. 이후 만드는
-- 계정(이메일/비밀번호 가입)은 컬럼 기본값이 없어 `NULL`(미인증)로 시작하고,
-- `AuthService.signup`이 커밋 뒤 인증 코드를 발급·발송한다. 소셜 로그인 가입은
-- `UserRepository.createWithoutPassword`가 생성 시점에 이미 채운다(제공자가 이미 검증한
-- 이메일이라 우리 쪽 코드가 또 필요하지 않다).
ALTER TABLE users
    ADD COLUMN email_verified_at timestamptz NULL;

UPDATE users
SET email_verified_at = now()
WHERE email_verified_at IS NULL;

-- --- email_verification_codes ------------------------------------------------
-- 이메일당(=사용자당) **활성 코드는 최대 하나** — `JdbcVerificationCodeStore.issue`가 새
-- 코드를 발급할 때 이전 행을 소비 처리(`consumed_at`)해 이 불변식을 지킨다. 코드는 평문이
-- 아니라 salt 를 곁들인 해시로만 저장한다 — 6자리 숫자는 정보 엔트로피가 낮아(최대
-- 100만 가지) DB 를 그대로 읽을 수 있는 공격자에게 평문 저장은 사실상 무방비다. 해시
-- 알고리즘은 Argon2(비밀번호용, 메모리 하드) 가 아니라 SHA-256 + 행마다 다른 salt 다 —
-- 10분 TTL·5회 시도 제한이 있는 일회성 코드에는 계산 비용이 높은 해시가 필요하지
-- 않고(브루트포스 방어는 시도 횟수 제한이 진다), 인증 코드 확인은 요청마다 도는 경로라
-- Argon2 를 쓰면 그 비용을 사용자가 그대로 체감한다.
CREATE TABLE email_verification_codes (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    code_hash text NOT NULL,
    salt text NOT NULL,
    expires_at timestamptz NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    -- 단발 소비 표식과 같은 규약이다(`oauth_states.consumed_at`, `V6__user_identities.sql`).
    -- `NULL` = 아직 유효(활성). 값이 있으면 확인 성공으로 소비됐거나 재발급으로 무효화됐다 —
    -- 이 표만으로는 둘을 가르지 않는다. 어느 쪽이든 다시 쓸 수 없다는 결론이 같기 때문이다.
    consumed_at timestamptz NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_email_verification_codes PRIMARY KEY (id),
    CONSTRAINT fk_email_verification_codes_user_id_users FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);

-- 재발송 쿨다운 조회("이 사용자의 마지막 발급이 언제였나")와 활성 코드 조회
-- ("이 사용자의 아직 유효한 코드")가 둘 다 (user_id, created_at 최신순)로 들어온다.
CREATE INDEX ix_email_verification_codes_user_id_created_at
    ON email_verification_codes USING btree (user_id, created_at DESC);
