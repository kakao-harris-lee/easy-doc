package kr.easydoc.infrastructure.credit

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
 * [signupGrant] 기본값 `0` — 기본 워크스페이스가 만들어질 때(가입) 이 수만큼
 * `grant/signup` 거래를 넣는다. `0`이면 거래를 만들지 않는다.
 */
@ConfigurationProperties(prefix = "easydoc.credits")
data class CreditsProperties(
    val enforced: Boolean = false,
    val signupGrant: Int = 0,
)
