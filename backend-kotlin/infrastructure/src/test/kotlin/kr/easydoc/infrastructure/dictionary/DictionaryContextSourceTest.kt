package kr.easydoc.infrastructure.dictionary

import kr.easydoc.core.dictionary.DictionaryContextPolicy
import kr.easydoc.core.privacy.maskText
import kr.easydoc.infrastructure.queue.ConversionWorkerConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 사전 주입 배선 — 플래그 on/off 와 「매칭 0건이면 싣지 않는다」. */
class DictionaryContextSourceTest {
    private val index = DictionaryIndexJsonReader().readClasspathResource()

    @Test
    @DisplayName("기본값은 켜짐이다 — 사전에서 흡수 단어를 덜어내는 릴리스와 주입이 함께 켜져야 한다")
    fun `기본값은 켜짐이다`() {
        assertThat(DictionaryProperties().enabled).isTrue()
    }

    @Test
    @DisplayName("켜면 문서에 나온 용어로 컨텍스트를 만든다")
    fun `켜면 컨텍스트를 싣는다`() {
        val source = ConversionWorkerConfiguration().dictionaryContextSource(DictionaryProperties())

        val context = source.contextFor(maskText(WITH_TERMS).maskedText)

        assertThat(context).isNotNull
        assertThat(context).contains("### 바꿔 쓰세요")
        assertThat(context).contains("구비서류")

        val rendered = index.renderPromptContext(maskText(WITH_TERMS).maskedText.value, DictionaryProperties().policy())
        assertThat(rendered.renderedTerms).isGreaterThan(0)
        assertThat(rendered.totalTerms).isGreaterThanOrEqualTo(rendered.renderedTerms)
    }

    @Test
    @DisplayName("끄면 아무것도 싣지 않는다 — 색인을 읽지도 않는다")
    fun `끄면 싣지 않는다`() {
        val source = ConversionWorkerConfiguration().dictionaryContextSource(DictionaryProperties(enabled = false))

        assertThat(source.contextFor(maskText(WITH_TERMS).maskedText)).isNull()
    }

    @Test
    @DisplayName("매칭이 0건이면 섹션 골격 대신 null 이다 — 근거 없는 지시문만 늘리지 않는다")
    fun `매칭이 없으면 주입하지 않는다`() {
        val source = IndexedDictionaryContextSource(index, DictionaryProperties().policy())
        val masked = maskText(WITHOUT_TERMS).maskedText

        // 이 문장에 사전 용어가 없다는 것을 먼저 못박는다 — 색인이 바뀌어 매칭이 생기면
        // 아래 단언은 「0건이면 null」이 아니라 다른 것을 재게 된다.
        assertThat(index.findAll(masked.value)).isEmpty()
        assertThat(source.contextFor(masked)).isNull()

        // core 는 참조 구현대로 골격을 돌려준다. 그 차이가 곧 이 어댑터의 책임이다.
        assertThat(index.buildPromptContext(masked.value)).isNotEmpty()
    }

    @Test
    @DisplayName("예산이 항목을 전부 밀어내면 주입하지 않는다 — 매칭 0건과 같은 이유다")
    fun `실린 항목이 없으면 주입하지 않는다`() {
        val source = IndexedDictionaryContextSource(index, DictionaryProperties().policy())
        val masked = maskText(SHORT_WITH_TERMS).maskedText

        // 매칭은 **있다** — 이 경계는 「찾은 게 없다」가 아니라 「찾았는데 예산이 다 밀어냈다」다.
        assertThat(index.findAll(masked.value)).isNotEmpty()

        val rendered = index.renderPromptContext(masked.value, DictionaryProperties().policy())
        assertThat(rendered.totalTerms).isGreaterThan(0)
        assertThat(rendered.renderedTerms).isZero()

        assertThat(source.contextFor(masked)).isNull()
    }

    @Test
    @DisplayName("설정 값이 예산 정책으로 그대로 간다")
    fun `설정이 정책이 된다`() {
        val properties =
            DictionaryProperties(
                maxTerms = 7,
                maxChars = 800,
                maxCharsRatio = null,
                minSubstitute = 2,
                maxExamples = 1,
            )

        assertThat(properties.policy())
            .isEqualTo(
                DictionaryContextPolicy(
                    maxTerms = 7,
                    maxChars = 800,
                    maxCharsRatio = null,
                    minSubstitute = 2,
                    maxExamples = 1,
                ),
            )
    }

    private companion object {
        /**
         * 사전 용어가 실제로 들어 있는 안내문.
         *
         * 한 줄짜리로 두면 안 된다 — 기본 정책의 `maxCharsRatio=1.0` 이 원문 길이를 그대로
         * 상한으로 삼아, 짧은 문서에서는 매칭이 있어도 항목이 전부 잘려 나간다(참조 구현과
         * 같은 동작이다). 그 경계가 아니라 「켜면 실린다」를 재려면 안내문 분량이어야 한다.
         */
        val WITH_TERMS: String =
            """
            차상위계층 지원 안내

            □ 신청 방법
             ○ 신청하실 때에는 구비서류를 지참하여 가까운 주민센터에 방문하여 주시기 바랍니다.
             ○ 제출한 서류의 사본은 반환하지 않으며, 수령 사실을 확인한 뒤 심사를 진행합니다.
             ○ 해당자께서는 신청 기간 안에 접수하여야 하며, 기한이 지나면 소급하여 적용되지 않습니다.

            □ 유의 사항
             ○ 소득인정액이 선정기준액을 넘으면 지원 대상에서 제외될 수 있습니다.
             ○ 신청서에 적은 내용이 사실과 다르면 지원금이 환수될 수 있으니 정확히 적어 주십시오.
             ○ 자세한 내용은 담당 부서로 문의하여 주시기 바랍니다.
            """.trimIndent()

        /**
         * 사전 용어는 있는데 **너무 짧은** 안내문.
         *
         * 기본 정책의 `maxCharsRatio=1.0` 이 원문 길이를 그대로 상한으로 삼는데, 섹션 골격만으로
         * 이미 130자라 이 길이에서는 항목이 한 줄도 살아남지 못한다. 참조 구현과 같은 렌더링
         * 결과이고, 그것을 프롬프트에 실을지가 이 어댑터의 판단이다.
         */
        const val SHORT_WITH_TERMS: String = "차상위계층은 구비서류를 지참하여 방문하십시오."

        /** 사전에 없는 낱말만. 「매칭 0건」 경계를 재는 입력이다. */
        const val WITHOUT_TERMS: String = "가나다라마바사 아자차카타파하"
    }
}
