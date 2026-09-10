package kr.easydoc.infrastructure.credit

import kr.easydoc.core.security.Secret
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 크레딧 집행 설정. 바인딩 접두사는 `easydoc.credits`(계획
 * `docs/plans/2026-09-07-credit-accounts.md` §2 결정 4).
 *
 * [enforced] 기본값이 `false` 인 이유: 배포 직후 모든 워크스페이스 잔액이 0이라 곧바로
 * 켜면 모든 등록이 402가 된다. 운영자가 `credit-grant` 프로필로 잔액을 부여한 뒤 켠다
 * (러너북 「크레딧 충전」 절). 꺼져 있어도 예약·소비·거래는 **똑같이 기록된다** — 잔액이
 * 음수가 될 수 있고, 그 사실이 곧 청구 근거다.
 *
 * [signupGrant] 기본값 `0` — 기본 워크스페이스가 만들어질 때(가입) 이 수만큼 `cycle_set`
 * 거래로 비갱신(1개월) 주기를 연다(2026-09-10 사용자 확정 — 「크레딧을 구독 주기에
 * 포함된 이용량으로」, [kr.easydoc.application.credit.CreditAccountService.grantSignupBonus]).
 * `0`이면 거래를 만들지 않는다.
 *
 * [signupGrantValidity] — 가입 크레딧(무료 체험)의 유효기간, ISO-8601 Period 문자열(기본
 * `P1M` = 1개월). [kr.easydoc.application.credit.CreditAccountService] 가 [java.time.Period.parse]
 * 로 읽어 주기 종료일을 계산한다. 코드에 한 달을 박지 않고 구성값으로 받는다(프로젝트
 * `CLAUDE.md` 「상수와 구성 관리」).
 *
 * [signupGrantPepper] — 가입 부여 중복 방지 원장(`signup_grant_records`, V20)의 이메일
 * 해시에 섞는 비밀값(`kr.easydoc.application.credit.SignupGrantEmailHasher`, 가입 크레딧
 * 후속 §7 결정 2). 환경변수 `EASYDOC_CREDITS_SIGNUP_GRANT_PEPPER` 하나로만 주입된다.
 * `signupGrant > 0` 인데 이 값이 비어 있으면 [CreditAccountConfiguration] 의 기동
 * 자기점검이 앱을 띄우지 않는다(§7 결정 3, `signupGrant = 0` 이면 pepper 없이도 뜬다).
 * **회전하지 않는 값이다** — 바꾸면 기존 `signup_grant_records` 행이 새 해시와 매칭되지
 * 않아 그 이메일이 다시 부여받는다(`.env.example`·러너북 「크레딧 충전」에 명시).
 */
@ConfigurationProperties(prefix = "easydoc.credits")
data class CreditsProperties(
    val enforced: Boolean = false,
    val signupGrant: Int = 0,
    val signupGrantValidity: String = "P1M",
    val signupGrantPepper: Secret = Secret.EMPTY,
)
