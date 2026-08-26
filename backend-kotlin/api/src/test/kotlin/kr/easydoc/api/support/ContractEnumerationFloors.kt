package kr.easydoc.api.support

const val MIN_GLOBAL_RESPONSE_HEADERS = 2

/** 1.5.0 에서 `PUT /conversions/{conversion_id}/feedback` 이 더해져 10 에서 올렸다. */
const val MIN_PRIVATE_HEADER_TARGETS = 11

const val MIN_RETIRED_RESPONSES = 1

const val MIN_EXTENSION_NODES = 15

const val MIN_CONTAINER_REJECTED_CASES = 6

/** 계약에서 읽은 열거가 하한 아래로 깎였으면 끊는다. 분모를 소비자가 아니라 접근자에 둔다. */
internal fun <T : Collection<*>> atLeastFloor(
    values: T,
    floor: Int,
): T {
    require(values.size >= floor) { floorMessage(values.size, floor) }
    return values
}

internal fun atLeastFloor(
    values: Map<*, *>,
    floor: Int,
): Map<*, *> {
    require(values.size >= floor) { floorMessage(values.size, floor) }
    return values
}

private fun floorMessage(
    actual: Int,
    floor: Int,
): String =
    "계약에서 읽은 열거가 $actual 개다 — 하한 $floor 아래다. 이 집합을 분모로 쓰는 대조가 전부 " +
        "함께 좁아지고 그 감소를 재는 것이 없다"
