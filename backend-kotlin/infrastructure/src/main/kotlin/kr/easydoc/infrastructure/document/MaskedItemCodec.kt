package kr.easydoc.infrastructure.document

import kr.easydoc.application.document.MaskedItemReader
import kr.easydoc.application.document.MaskedItemWriter
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.MaskedItemView
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.privacy.MaskCategory
import kr.easydoc.core.privacy.MaskedItem
import kr.easydoc.core.security.Secret
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** 마스킹 대응표의 **저장 형식** — 자리표시자와 원값의 표를 JSON 한 덩어리로 만든다. */
class MaskedItemCodec :
    MaskedItemReader,
    MaskedItemWriter {
    private val json = JsonMapper.builder().build()

    /**
     * 저장용 평문 JSON 을 만든다. **결과는 반드시 암호화해서 저장한다** — 여기에는 가려졌던
     * 개인정보가 그대로 들어 있다.
     */
    override fun encode(items: List<MaskedItem>): PlainBody {
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

    /** 복호화된 JSON 에서 대응표를 되살린다. */
    override fun decode(body: PlainBody): List<MaskedItemView> {
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
