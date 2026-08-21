package kr.easydoc.infrastructure.document

import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.MaskedItemView
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.privacy.MaskCategory
import kr.easydoc.core.privacy.MaskedItem
import kr.easydoc.core.security.Secret
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * 마스킹 대응표의 **저장 형식** — 자리표시자와 원값의 표를 JSON 한 덩어리로 만든다.
 *
 * ## 이 형식을 이 단위가 처음 정한다
 *
 * `conversions.masked_items_encrypted` 에 무엇을 담을지 정한 코드가 지금까지 없었다.
 * 원본(Python)은 Fernet + JSON 이었고 Kotlin 은 대응이 없다. 결정은 계획 §4.6 이다 —
 * **평문 JSON 배열을 만들고 그 바이트를 통째로 AEAD 로 봉인한다.** 열 단위 암호화가 아니라
 * 한 덩어리인 이유: 대응표는 항목 하나만 떼어 읽을 일이 없고, 항목 수 자체가 문서에 든
 * 개인정보의 **개수**라 그것마저 드러내지 않는 편이 낫다.
 *
 * ## 쓰기는 아직 없다 — 그런데도 **양방향**을 여기서 정한다
 *
 * 이 표를 실제로 쓰는 것은 변환 워커(Phase 5)다. 그렇다고 읽기만 만들면, 워커가 다른
 * 형식으로 쓰기 시작하는 순간 **조용히 갈린다**(그 갈림은 첫 검수 화면에서 "가린 항목 없음"
 * 으로 나타난다 — 실패처럼 보이지 않는다). 그래서 인코더·디코더를 한 클래스에 두고 왕복을
 * 테스트로 못박는다.
 *
 * ## 범주는 **저장 키와 화면 문구를 가른다**
 *
 * 계약 `MaskedItemResponse.category` 의 enum 값은 한국어이고 그것이 **그대로 화면 문구**다
 * (React 가 `<td>{item.category}</td>` 로 렌더링한다). 그 값을 저장 형식으로 쓰면 문구를
 * 다듬는 날 **옛 행이 안 읽힌다** — 문구는 제품 결정이고 저장 형식은 데이터 계약인데, 둘을
 * 같은 문자열로 묶으면 앞엣것을 바꿀 수 없게 된다.
 *
 * 그래서 저장에는 [CATEGORY_KEYS] 의 **안정된 키**를 쓰고, 응답에는 [MaskCategory.label] 을
 * 쓴다. 매핑은 이 파일 한 곳에 있다. 키를 `MaskCategory.name` 에서 유도하지 않는 것도 같은
 * 이유다 — enum 상수 이름은 리팩터링 대상이고 저장 형식은 아니다.
 *
 * ## 원값을 꺼내는 자리가 **정확히 한 곳**이다
 *
 * [MaskedItem.original] 은 [Secret] 이라 [Secret.reveal] 없이는 문자열이 되지 않는다.
 * 그 호출은 [encode] 안에 하나뿐이고, 그 함수는 아무것도 로깅하지 않는다. 되읽는 쪽도
 * [Secret] 으로 다시 감싸 내보내므로([MaskedItemView.original]) 평문 `String` 이 이 클래스
 * 밖으로 나가지 않는다.
 */
class MaskedItemCodec {
    private val json = JsonMapper.builder().build()

    /**
     * 저장용 평문 JSON 을 만든다. **결과는 반드시 암호화해서 저장한다** — 여기에는 가려졌던
     * 개인정보가 그대로 들어 있다.
     */
    fun encode(items: List<MaskedItem>): PlainBody {
        val array = json.createArrayNode()
        items.forEach { item ->
            array.addObject().apply {
                put(CATEGORY_FIELD, keyOf(item.category))
                put(PLACEHOLDER_FIELD, item.placeholder)
                put(ORIGINAL_FIELD, item.original.reveal())
            }
        }
        return PlainBody(json.writeValueAsString(array))
    }

    /**
     * 복호화된 JSON 에서 대응표를 되살린다.
     *
     * 형식이 어긋나면 [StorageException] 이다 — 사용자 입력 문제가 아니므로 5xx 로 올린다.
     * **조용히 빈 목록으로 넘기지 않는다**: 검수 화면이 "가린 항목 없음"으로 보여 원문 대조가
     * 무력해지고, 그 상태는 실패처럼 보이지 않는다(원본 `deserialize_masked_items` 와 같은 판단).
     *
     * 파싱 예외의 메시지도 원인도 잇지 않는다 — 그 안에 개인정보가 섞인 JSON 조각이 실린다.
     */
    fun decode(body: PlainBody): List<MaskedItemView> {
        val root =
            try {
                json.readTree(body.value)
            } catch (exc: JacksonException) {
                throw malformed(exc::class.java.simpleName)
            }
        if (!root.isArray) throw malformed("not-an-array")
        // `JsonNode.map(Function)` 이 Kotlin 의 `Iterable.map` 을 가린다(Jackson 3 신설 멤버).
        return root.values().map(::toView)
    }

    private fun toView(node: JsonNode): MaskedItemView {
        if (!node.isObject) throw malformed("element-not-an-object")
        val key = node.path(CATEGORY_FIELD).stringValue("")
        val placeholder = node.path(PLACEHOLDER_FIELD).stringValue("")
        // 원값은 **비어 있을 수 있다**고 보지 않는다 — 가린 것이 빈 문자열이면 애초에 가릴
        // 것이 없었다는 뜻이라, 그런 행은 우리가 쓴 것이 아니다.
        val original = node.path(ORIGINAL_FIELD).stringValue("")
        if (placeholder.isEmpty() || original.isEmpty()) throw malformed("missing-field")
        return MaskedItemView(
            category = categoryOf(key),
            placeholder = placeholder,
            original = Secret(original),
        )
    }

    private fun keyOf(category: MaskCategory): String =
        CATEGORY_KEYS[category]
            // 범주가 늘었는데 이 표를 안 고친 상태다. 저장 형식에 구멍을 내지 않고 끊는다 —
            // `MaskedItemCodecTest` 가 「모든 범주에 키가 있다」를 상시로 확인한다.
            ?: throw malformed("unmapped-category")

    private fun categoryOf(key: String): MaskCategory =
        CATEGORY_KEYS.entries.firstOrNull { it.value == key }?.key ?: throw malformed("unknown-category")

    private fun malformed(reason: String): StorageException {
        DocumentStorageLog.malformedStoredValue(MASKED_ITEMS_COLUMN, reason)
        return StorageException(STORAGE_FAILURE_MESSAGE)
    }

    companion object {
        /** **저장 형식의 범주 키.** 계약 enum 값(한국어)이 아니라 이 값이 컬럼에 들어간다. */
        val CATEGORY_KEYS: Map<MaskCategory, String> =
            mapOf(
                MaskCategory.RRN to "rrn",
                MaskCategory.CARD to "card",
            )

        const val CATEGORY_FIELD: String = "category"
        const val PLACEHOLDER_FIELD: String = "placeholder"
        const val ORIGINAL_FIELD: String = "original"

        private const val MASKED_ITEMS_COLUMN = "conversions.masked_items_encrypted"

        /** 계약 `InternalError` 의 `storage` 갈래. */
        private const val STORAGE_FAILURE_MESSAGE = "저장된 변환 결과를 읽을 수 없습니다"
    }
}
