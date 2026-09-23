package kr.easydoc.application.illustration

import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.illustration.IllustrationAssetId
import kr.easydoc.core.illustration.IllustrationPlacement
import kr.easydoc.core.illustration.IllustrationPlacements

/**
 * 그림 배치(ER-16) 저장 평문 직렬화. Jackson 없이 줄 단위 텍스트로 적는다 — 봉인 대상이
 * `easyUnitIndex`·`assetId` 쌍뿐이라 JSON을 끌어올 값어치가 없다.
 *
 * 한 줄은 `"<easy_unit_index>\t<asset_id>\n"`(UTF-8) — [IllustrationAssetId.PATTERN] 이
 * 탭·개행을 포함하지 않으므로 구분자 충돌이 없다.
 */
object IllustrationPlacementCodec {
    /** 최대 10개 항목 * (인덱스 최대 몇 자 + 탭 + asset id 최대 40자 + 개행) 을 넉넉히 덮는 상한. */
    const val MAX_PLAINTEXT_BYTES: Int = 4096

    /** 초과하면 저장하지 않는다는 뜻으로 `null` — 호출자가 [kr.easydoc.core.exceptions.StorageException] 등으로 매핑한다. */
    fun encode(placements: IllustrationPlacements): ByteArray? {
        val text =
            buildString {
                placements.entries.forEach { entry ->
                    append(entry.easyUnitIndex)
                    append('\t')
                    append(entry.assetId.value)
                    append('\n')
                }
            }
        val bytes = text.toByteArray(Charsets.UTF_8)
        return bytes.takeIf { it.size <= MAX_PLAINTEXT_BYTES }
    }

    fun decode(bytes: ByteArray): IllustrationPlacements {
        if (bytes.size > MAX_PLAINTEXT_BYTES) throw InvalidInputException(CORRUPT_PLACEMENTS_MESSAGE)
        val text = String(bytes, Charsets.UTF_8)
        if (text.isEmpty()) return IllustrationPlacements(emptyList())
        val lines = text.removeSuffix("\n").split("\n")
        val entries = lines.map(::parseLine)
        return IllustrationPlacements(entries)
    }

    /** 손상 사유(구분자 없음·인덱스 파싱 실패·음수·asset id 형식 위반)를 구분하지 않고 같은 문구로 끊는다. */
    private fun parseLine(line: String): IllustrationPlacement {
        val separator = line.indexOf('\t')
        val index = if (separator >= 0) line.substring(0, separator).toIntOrNull()?.takeIf { it >= 0 } else null
        val assetId = if (separator >= 0) IllustrationAssetId.parse(line.substring(separator + 1)) else null
        if (index == null || assetId == null) throw InvalidInputException(CORRUPT_PLACEMENTS_MESSAGE)
        return IllustrationPlacement(index, assetId)
    }

    const val CORRUPT_PLACEMENTS_MESSAGE: String = "저장된 그림 배치를 읽을 수 없습니다"
}
