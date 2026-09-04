-- 명시적 계정 연결(backlog §1.4, 소셜 로그인 다음 조각) — 비밀번호로 로그인한 사용자가
-- 그 계정에 소셜 신원을 잇는 흐름의 state 는 "누가 요청했는지"를 실어야 한다.
--
-- `user_id` 가 NULL 이면 로그인 흐름(`oauthStart`/`oauthCallback`, V6)의 state 다 —
-- 그때는 아직 아무도 인증되지 않았다. NULL 이 아니면 연결 흐름(`oauthLinkStart`/
-- `oauthLinkCallback`)의 state 이고, 값은 요청한 사용자의 id 다. `SocialLoginService`
-- (application 계층)가 이 값과 콜백 시점의 Bearer 사용자를 대조한다 — 다르면(로그인
-- state 가 연결 콜백에 왔거나, 다른 사용자의 연결 state 가 왔거나) 400 으로 거절하고
-- 사유를 구분하지 않는다(`x-social-login.state`).
--
-- ondelete=CASCADE: 계정이 지워지면 그 계정이 요청한 연결 state 도 함께 사라진다 —
-- 10분 TTL 인 소량 행이라 방치돼도 해가 없지만, 지워진 사용자를 참조하는 행을 남기지
-- 않는 것이 다른 FK(`user_identities.user_id` 등)와 같은 방침이다.
ALTER TABLE oauth_states
    ADD COLUMN user_id uuid NULL
        CONSTRAINT fk_oauth_states_user_id_users REFERENCES users (id) ON DELETE CASCADE;
