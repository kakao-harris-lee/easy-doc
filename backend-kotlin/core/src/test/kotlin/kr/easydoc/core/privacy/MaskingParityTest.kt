package kr.easydoc.core.privacy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kr.easydoc.core.parity.ParityActual
import kr.easydoc.core.parity.ParityCase
import kr.easydoc.core.parity.ParityFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** `masking` 도메인 parity 산출물 생산자. */
class MaskingParityTest {
    private companion object {
        const val DOMAIN = "masking"
    }

    @Test
    @Tag("parity")
    @DisplayName("masking fixture 전건을 돌려 parity/actual 에 산출물을 쓴다")
    fun `산출물을 만든다`() {
        val cases = ParityFixtures.cases(DOMAIN)

        val produced =
            cases.map { case ->
                val text =
                    case.input["text"]?.jsonPrimitive?.content
                        ?: error("케이스 ${case.id} 의 input.text 가 없다 — fixture 형식이 바뀌었는지 확인하라")
                ParityCase(id = case.id, actual = maskingActual(text))
            }

        val written = ParityActual.write(DOMAIN, "$DOMAIN.json", produced)

        assertThat(produced).hasSameSizeAs(cases)
        assertThat(written.fileName.toString()).isEqualTo("$DOMAIN.json")
    }

    /** 비교기가 읽는 모양으로 마스킹 결과를 적는다. */
    private fun maskingActual(text: String): JsonObject {
        val result = maskText(text)
        return JsonObject(
            mapOf(
                "masked_text" to JsonPrimitive(result.maskedText.value),
                "items" to
                    JsonArray(
                        result.items.map { item ->
                            JsonObject(
                                mapOf(
                                    "category" to JsonPrimitive(item.category.label),
                                    "placeholder" to JsonPrimitive(item.placeholder),
                                    "original" to JsonPrimitive(item.original.reveal()),
                                ),
                            )
                        },
                    ),
            ),
        )
    }
}
