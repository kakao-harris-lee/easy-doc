-- 가입 크레딧은 계정당(정확히는 이메일당) 한 번(계획
-- `docs/plans/2026-09-09-account-deletion.md` §7~§8, 후속 「가입 크레딧은 계정당 한 번」).
--
-- 회원 탈퇴(V19)는 `users` 행을 지우므로 탈퇴한 이메일로 재가입하면 이메일이 남지
-- 않아 「이 이메일이 전에 가입 부여를 받았는가」를 알 방법이 없다 — 반복 수령 구멍
-- (§2 결정 8이 열어 둔 것)을 이 마이그레이션이 닫는다.

-- --- signup_grant_records ----------------------------------------------------
-- 가입 부여를 받은 이메일의 단방향 해시. **평문 이메일을 담지 않는다** — 사전 공격에
-- 곧바로 뚫리는 단순 SHA-256 대신 비밀 pepper 를 섞은 HMAC-SHA256(64자 16진)을 쓴다
-- (`SignupGrantEmailHasher`, pepper 는 `EASYDOC_CREDITS_SIGNUP_GRANT_PEPPER`).
--
-- `granted_at`은 **부여 시점**에 기록한다(탈퇴 시점이 아니다) — `CreditAccountService.
-- grantSignupBonus`가 부여하기 전에 이 표를 조회하고, 없을 때만 부여한 뒤 기록한다.
-- 그래서 이 행은 `users`·`workspaces` 어디에도 FK 를 걸지 않는다 — 계정이 탈퇴로
-- 사라져도(V19 CASCADE) 이 표는 **일부러** 남아야 목적을 이룬다.
CREATE TABLE signup_grant_records (
    email_hash char(64) NOT NULL,
    granted_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_signup_grant_records PRIMARY KEY (email_hash)
);

-- --- workspace_credit_accounts.signup_grant_skipped --------------------------
-- 그 워크스페이스의 가입 부여가 「이미 받은 이메일이라 건너뛰었다」는 사실을 계정 행에
-- 남긴다 — `GET /workspaces/{workspace_id}/credits`(계약 2.29.0)가 이 열로 안내 문구를
-- 띄운다. `credit_transactions`에는 아무것도 남기지 않는다(부여가 없었으므로) — 이
-- 열이 그 사실을 대신 진다. 기본값 false: 기존 계정·가입 부여를 정상 수령한 계정
-- 전부 이 값이다.
ALTER TABLE workspace_credit_accounts
    ADD COLUMN signup_grant_skipped boolean NOT NULL DEFAULT false;
