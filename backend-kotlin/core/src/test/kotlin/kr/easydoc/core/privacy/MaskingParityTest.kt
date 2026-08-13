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

/**
 * `masking` 도메인 parity 산출물 생산자.
 *
 * ## 이 파일이 닫는 것 (교차 종합 X-1)
 *
 * fixture 31건은 오래전부터 있었지만 **Kotlin 쪽 판정 건수가 0이었다** — 산출물을 만드는
 * 코드가 없어 게이트가 "결과 파일 없음"으로 계속 미가동이었다. 이 테스트가 그 자리다.
 *
 * ## 값을 판정하지 않는다
 *
 * 여기서 하는 일은 fixture 의 `input` 을 [maskText] 에 넣고 결과를 비교기가 읽는 모양으로
 * 적는 것뿐이다. `absent`·`present`·`restores_input`·`placeholder_scheme` 판정은 전부
 * `compare_parity.py` 가 한다. **기대값을 여기서 알면 그것에 맞추는 코드를 쓰게 된다.**
 *
 * 값 자체의 성질은 `MaskingTest` 가 따로 단언한다 — 두 장치의 역할이 다르다. parity 는
 * "요구가 못박은 성질을 fixture 전건에서 만족하는가", 단위 테스트는 "왜 그런가"다.
 *
 * ## 산출물 모양
 *
 * `restores_input`·`placeholder_scheme` 이 `masked_text`(문자열)와 `items`(배열: `category`
 * 한국어 라벨 · `placeholder` · `original`)를 요구한다. `category` 에 enum 이름(`RRN`)을
 * 실으면 안 된다 — 계약이 못박은 것은 한국어 라벨이고 비교기가 계약을 직접 읽어 대조한다.
 */
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

        // fixture 건수와 산출 건수가 갈리면 비교기는 "짝 없는 케이스"로 잡지만, 여기서 먼저
        // 막는 편이 원인 추적이 쉽다. 특히 0건 산출은 게이트에서 "미실행"과 구분되지 않는다.
        assertThat(produced).hasSameSizeAs(cases)
        assertThat(written.fileName.toString()).isEqualTo("$DOMAIN.json")
    }

    /**
     * 비교기가 읽는 모양으로 마스킹 결과를 적는다.
     *
     * `original` 에 원문 조각이 실린다. fixture 입력이 전부 **합성값**이라 실제 개인정보가
     * 아니지만, 이 산출물이 `parity/actual/` 에 남는다는 사실 자체는 기록해 둔다 —
     * 실문서를 fixture 로 넣는 순간 이 파일이 평문 개인정보 저장소가 된다.
     */
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
                                    // 계약(easy-doc-v1.yaml::MaskedItemResponse)이 못박은 한국어 라벨이다.
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
