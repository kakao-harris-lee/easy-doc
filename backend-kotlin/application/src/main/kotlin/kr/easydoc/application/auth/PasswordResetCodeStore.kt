package kr.easydoc.application.auth

/**
 * 비밀번호 재설정 코드 저장소 — [OneTimeCodeStore]와 계약이 같다(발급 · 확인). 스키마는
 * `V11__password_reset_codes.sql`(`password_reset_codes`).
 *
 * [VerificationCodeStore]와 나란한 별도 타입이다 — 두 유스케이스(이메일 인증 · 비밀번호
 * 재설정)가 서로 다른 테이블에 각자의 코드를 발급·확인하므로, 같은 인터페이스 타입 하나만
 * 있으면 Spring DI가 두 빈 중 어느 것을 주입할지 이름(`@Qualifier`)에 의존해야 한다.
 * 이름 있는 타입 둘이 그 판단을 컴파일 시점 타입으로 대신한다([OneTimeCodeStore] KDoc).
 */
interface PasswordResetCodeStore : OneTimeCodeStore
