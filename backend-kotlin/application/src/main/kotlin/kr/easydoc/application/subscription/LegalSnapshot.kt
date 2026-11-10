package kr.easydoc.application.subscription

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 유료 Start 약관·개인정보처리방침 스냅샷 버전과 그 시행 시각의 한 정의.
 *
 * [VERSION] 은 `infrastructure` 가 동의 기록에 담는 `legal/terms-<VERSION>.md` 리소스 이름이고,
 * 같은 날짜가 공개한 약관의 시행일이다. 버전과 시행 시각을 따로 적으면 스냅샷은 새 약관을
 * 가리키는데 결제는 그 전에 열리는 식으로 어긋나므로 시행 시각을 버전에서 끌어낸다.
 *
 * [PRIVACY_VERSION] 은 `legal/privacy-<PRIVACY_VERSION>.md` 리소스 이름이다. **약관 버전과 따로
 * 움직인다** — 개인정보처리방침 개정은 유료 약관의 내용도 시행 시각도 바꾸지 않으므로, 방침을
 * 고치려고 [EFFECTIVE_AT] 을 옮기면 실결제 관문이 공개된 약관과 어긋난다.
 *
 * 방침은 여러 번 개정되므로 [PRIVACY_VERSIONS] 에 게시 순서대로 쌓고 [privacyVersionAt] 으로
 * **동의 시점에 시행 중이던** 버전을 고른다. 동의 증거에 지금 버전을 박으면 과거의 동의가
 * 보지도 못한 방침에 동의한 것처럼 기록된다.
 *
 * 코드와 함께 바뀌어야 하는 불변식이라 구성값이 아니라 상수로 둔다 — 약관 본문을 저장소에
 * 함께 올리지 않고는 바꿀 수 없다.
 */
object LegalSnapshot {
    /** `legal/terms-<VERSION>.md` 의 버전이자 유료 약관 시행일(`yyyy-MM-dd`). */
    const val VERSION = "2026-11-01"

    /** 게시한 개인정보처리방침 버전을 게시 순서대로 적은 이력. 각 항목은 그날 자정부터 시행한다. */
    val PRIVACY_VERSIONS: List<String> = listOf("2026-11-01", "2026-11-10")

    /** `legal/privacy-<PRIVACY_VERSION>.md` 의 현재 버전. 방침 개정은 유료 시행일을 옮기지 않는다. */
    val PRIVACY_VERSION: String = PRIVACY_VERSIONS.last()

    /** 약관 본문이 적은 시행 시점의 기준 시간대. */
    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    /** 시행 시각. 이 시각 전의 실결제는 공개된 약관 없이 돈을 받는 것이다. */
    val EFFECTIVE_AT: Instant = LocalDate.parse(VERSION).atStartOfDay(ZONE).toInstant()

    /**
     * [instant] 에 시행 중이던 방침 버전. 첫 방침 시행 전이면 가리킬 방침이 없어 `null` 이다.
     *
     * 버전 날짜의 한국 시간 자정부터 시행한다 — [EFFECTIVE_AT] 과 같은 유도다.
     */
    fun privacyVersionAt(instant: Instant): String? =
        PRIVACY_VERSIONS.lastOrNull { !instant.isBefore(LocalDate.parse(it).atStartOfDay(ZONE).toInstant()) }
}
