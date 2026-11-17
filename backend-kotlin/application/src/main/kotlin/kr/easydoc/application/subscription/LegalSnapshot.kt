package kr.easydoc.application.subscription

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 유료 Start 약관·개인정보처리방침 스냅샷 버전과 그 시행 시각의 한 정의.
 *
 * [TERMS_VERSIONS] 는 게시한 약관 판을 시행 순서대로 적은 이력이고, 각 항목은
 * `infrastructure` 가 동의 기록에 담는 `legal/terms-<버전>.md` 리소스 이름이다. 약관도 방침처럼
 * 여러 번 개정되므로 [termsVersionAt] 으로 **동의 시점에 시행 중이던** 판을 고른다. 동의 증거에
 * 지금 판을 박으면 과거의 동의가 보지도 못한 약관에 동의한 것처럼 기록된다.
 *
 * [EFFECTIVE_AT] 은 **첫** 약관의 시행 시각이다 — 유료 실결제를 연 시각이지 마지막 개정일이
 * 아니다. 개정할 때마다 이 관문이 따라 움직이면 이미 결제를 받고 있는 서비스가 개정일마다 다시
 * 닫힌다. 그래서 버전 하나를 가리키던 옛 `VERSION` 을 이력으로 바꾸면서, 현재 판([TERMS_VERSION])
 * 과 시행 관문의 근거(`TERMS_VERSIONS.first()`)를 이름으로 갈라 두었다.
 *
 * [PRIVACY_VERSION] 은 `legal/privacy-<PRIVACY_VERSION>.md` 리소스 이름이다. **약관 버전과 따로
 * 움직인다** — 개인정보처리방침 개정은 유료 약관의 내용도 시행 시각도 바꾸지 않으므로, 방침을
 * 고치려고 [EFFECTIVE_AT] 을 옮기면 실결제 관문이 공개된 약관과 어긋난다.
 *
 * 코드와 함께 바뀌어야 하는 불변식이라 구성값이 아니라 상수로 둔다 — 약관 본문을 저장소에
 * 함께 올리지 않고는 바꿀 수 없다.
 */
object LegalSnapshot {
    /** 게시한 약관 버전을 게시 순서대로 적은 이력. 각 항목은 그날 자정부터 시행한다. */
    val TERMS_VERSIONS: List<String> = listOf("2026-11-01", "2026-11-17")

    /** `legal/terms-<TERMS_VERSION>.md` 의 현재 버전. 약관 개정은 유료 시행 시각을 옮기지 않는다. */
    val TERMS_VERSION: String = TERMS_VERSIONS.last()

    /** 게시한 개인정보처리방침 버전을 게시 순서대로 적은 이력. 각 항목은 그날 자정부터 시행한다. */
    val PRIVACY_VERSIONS: List<String> = listOf("2026-11-01", "2026-11-10")

    /** `legal/privacy-<PRIVACY_VERSION>.md` 의 현재 버전. 방침 개정은 유료 시행일을 옮기지 않는다. */
    val PRIVACY_VERSION: String = PRIVACY_VERSIONS.last()

    /** 약관 본문이 적은 시행 시점의 기준 시간대. */
    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    /** 유료 실결제 시행 시각. 이 시각 전의 실결제는 공개된 약관 없이 돈을 받는 것이다. */
    val EFFECTIVE_AT: Instant = startOfVersion(TERMS_VERSIONS.first())

    /**
     * [instant] 에 시행 중이던 약관 버전. 첫 약관 시행 전이면 가리킬 약관이 없어 `null` 이다.
     *
     * 버전 날짜의 한국 시간 자정부터 시행한다 — [EFFECTIVE_AT] 과 같은 유도다.
     */
    fun termsVersionAt(instant: Instant): String? = versionAt(TERMS_VERSIONS, instant)

    /**
     * [instant] 에 시행 중이던 방침 버전. 첫 방침 시행 전이면 가리킬 방침이 없어 `null` 이다.
     *
     * 버전 날짜의 한국 시간 자정부터 시행한다 — [EFFECTIVE_AT] 과 같은 유도다.
     */
    fun privacyVersionAt(instant: Instant): String? = versionAt(PRIVACY_VERSIONS, instant)

    private fun versionAt(
        versions: List<String>,
        instant: Instant,
    ): String? = versions.lastOrNull { !instant.isBefore(startOfVersion(it)) }

    private fun startOfVersion(version: String): Instant = LocalDate.parse(version).atStartOfDay(ZONE).toInstant()
}
