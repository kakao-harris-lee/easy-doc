-- 대기 중인 번호 지문이 어느 인증 코드 발급에서 왔는지 식별한다.
ALTER TABLE users
    ADD COLUMN pending_phone_verification_id uuid NULL;
