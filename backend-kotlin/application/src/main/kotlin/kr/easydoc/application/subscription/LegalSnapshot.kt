package kr.easydoc.application.subscription

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 유료 Start 약관·개인정보처리방침 스냅샷 버전과 그 시행 시각의 한 정의.
 *
 * [VERSION] 은 `infrastructure` 가 동의 기록에 담는 `legal/terms-<VERSION>.md`·
 * `legal/privacy-<VERSION>.md` 리소스 이름이고, 같은 날짜가 공개한 약관의 시행일이다.
 * 버전과 시행 시각을 따로 적으면 스냅샷은 새 약관을 가리키는데 결제는 그 전에 열리는 식으로
 * 어긋나므로 시행 시각을 버전에서 끌어낸다.
 *
 * 코드와 함께 바뀌어야 하는 불변식이라 구성값이 아니라 상수로 둔다 — 약관 본문을 저장소에
 * 함께 올리지 않고는 바꿀 수 없다.
 */
object LegalSnapshot {
    /** `legal/terms-<VERSION>.md`·`legal/privacy-<VERSION>.md` 의 버전이자 시행일(`yyyy-MM-dd`). */
    const val VERSION = "2026-11-01"

    /** 약관 본문이 적은 시행 시점의 기준 시간대. */
    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    /** 시행 시각. 이 시각 전의 실결제는 공개된 약관 없이 돈을 받는 것이다. */
    val EFFECTIVE_AT: Instant = LocalDate.parse(VERSION).atStartOfDay(ZONE).toInstant()
}
