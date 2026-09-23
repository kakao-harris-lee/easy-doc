package kr.easydoc.core.illustration

import kr.easydoc.core.exceptions.InvalidInputException

// ER-16 「그림 배치」— 변환 한 건의 본문 줄(easy_unit_index, 0-based)에 그림 자산을
// 붙이는 좌표 집합. `IllustrationPlacements`는 저장 시점의 요청 payload 그대로다 —
// 좌표만으로 본문 내용을 유추할 수 있는 데이터라 toString은 개수만 낸다.

/**
 * 본문 한 줄에 그림 한 장을 붙이는 좌표 한 건.
 *
 * **[toString] 은 값을 찍지 않는다** — 이 파일 머리 주석대로 좌표(`easyUnitIndex`)와
 * `assetId`는 그 자체로 본문 내용을 유추할 수 있는 데이터다(privacy-gate 판정, §10
 * 「좌표만으로 본문 유추 가능한 데이터도 본문 취급」). `@UserContent`는 달지 않는다 —
 * `assetId`(`IllustrationAssetId`)는 `SensitiveToStringReachTest`의
 * `GeneratedToStringProbes.INERT_VALUES`에 이미 등록돼 있어(ER-15,
 * `Illustration.assetId` 로 닿는 경로) 그 하네스가 `carriesText = false`로 고정한다 —
 * `@UserContent`를 달아도 심을 표식 자리가 없어 표본 자체가 생성되지 않는다(자동 게이트가
 * 닿지 않는다는 뜻). 그래서 이 재정의와 [IllustrationPlacementTest] 의 직접 단언이
 * 유일한 방어선이다.
 */
data class IllustrationPlacement(
    val easyUnitIndex: Int,
    val assetId: IllustrationAssetId,
) {
    init {
        require(easyUnitIndex >= 0) { "easyUnitIndex 는 0 이상이어야 한다: $easyUnitIndex" }
    }

    override fun toString(): String = "IllustrationPlacement(masked)"
}

/**
 * 변환 한 건의 그림 배치 집합 — 현재 집합만 있고 이력은 없다. 최대 [MAX_ENTRIES]개,
 * `easyUnitIndex` 는 유일해야 하며 오름차순으로 정규화한다.
 *
 * 생성자가 던지는 [InvalidInputException] 은 이 타입 자체의 불변식(개수·유일성)이고,
 * [validateAgainst] 가 던지는 것은 저장 시점의 외부 맥락(본문 줄 수·카탈로그)에 대한
 * 검증이다 — 후자는 변환마다 다르므로 생성자에 넣을 수 없다.
 */
class IllustrationPlacements(entries: List<IllustrationPlacement>) {
    val entries: List<IllustrationPlacement> = entries.sortedBy { it.easyUnitIndex }

    init {
        if (entries.size > MAX_ENTRIES) throw InvalidInputException(PLACEMENT_TOO_MANY_MESSAGE)
        val duplicated = entries.groupBy { it.easyUnitIndex }.any { it.value.size > 1 }
        if (duplicated) throw InvalidInputException(PLACEMENT_DUPLICATE_UNIT_MESSAGE)
    }

    /**
     * 저장 시점의 본문 줄 수·카탈로그에 대한 검증. [assetId] 가 카탈로그에 없는 것과 검수되지
     * 않은 것을 **구분하지 않는 같은 문구**로 거부한다(존재 은닉, `IllustrationsService` 와
     * 같은 규약).
     */
    fun validateAgainst(
        unitCount: Int,
        catalog: IllustrationCatalog,
    ) {
        entries.forEach { entry ->
            if (entry.easyUnitIndex >= unitCount) throw InvalidInputException(PLACEMENT_UNIT_OUT_OF_RANGE_MESSAGE)
            val selectable = catalog.find(entry.assetId)?.selectable == true
            if (!selectable) throw InvalidInputException(PLACEMENT_ASSET_NOT_SELECTABLE_MESSAGE)
        }
    }

    /** 개수만 남긴다 — 좌표(`easyUnitIndex`)와 `assetId`는 본문 내용을 유추할 수 있는 데이터다. */
    override fun toString(): String = "IllustrationPlacements(${entries.size}개)"

    /** 값 동등성 — 정규화된 [entries] 만 비교한다(테스트·mock 스텁이 값으로 비교할 수 있게). */
    override fun equals(other: Any?): Boolean = other is IllustrationPlacements && entries == other.entries

    override fun hashCode(): Int = entries.hashCode()

    companion object {
        const val MAX_ENTRIES = 10
    }
}

/** [IllustrationPlacements] 생성자가 개수 상한을 어겼을 때. */
const val PLACEMENT_TOO_MANY_MESSAGE: String = "그림 배치는 최대 10개까지 저장할 수 있습니다"

/** [IllustrationPlacements] 생성자가 `easyUnitIndex` 중복을 발견했을 때. */
const val PLACEMENT_DUPLICATE_UNIT_MESSAGE: String = "같은 줄에 그림을 두 번 배치할 수 없습니다"

/** [IllustrationPlacements.validateAgainst] — `easyUnitIndex` 가 본문 줄 수 밖일 때. */
const val PLACEMENT_UNIT_OUT_OF_RANGE_MESSAGE: String = "본문에 없는 줄입니다"

/**
 * [IllustrationPlacements.validateAgainst] — `assetId` 가 카탈로그에 없거나 검수되지 않았을 때.
 * 둘을 구분해 알리지 않는다.
 */
const val PLACEMENT_ASSET_NOT_SELECTABLE_MESSAGE: String = "선택할 수 없는 그림입니다"
