package kr.easydoc.infrastructure.document

import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.privacy.MaskCategory
import kr.easydoc.core.privacy.MaskedItem
import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 마스킹 대응표의 저장 형식 — **양방향**으로 잰다.
 *
 * ## 왜 읽기만 재면 안 되는가
 *
 * 이 표를 실제로 쓰는 것은 변환 워커(Phase 5)다. 지금 디코더만 두면, 워커가 다른 형식으로
 * 쓰기 시작하는 순간 **조용히 갈린다** — 그 갈림은 첫 검수 화면에서 "가린 항목 없음"으로
 * 나타나고, 그것은 실패처럼 보이지 않는다. 그래서 인코더·디코더를 같은 커밋에서 만들고
 * 왕복과 **고정된 바이트 형태**를 함께 못박는다.
 *
 * ## 형태를 문자열로 고정하는 이유
 *
 * 왕복만 재면 인코더와 디코더가 **함께** 바뀔 때 초록으로 남는다(둘이 같은 거짓말을 하면
 * 대조가 아무것도 확인하지 않는다). 그래서 저장 바이트 자체를 한 케이스에서 못박는다 —
 * 그 케이스가 빨개지면 「옛 행이 안 읽히는 변경」을 하고 있다는 신호다.
 */
class MaskedItemCodecTest {
    private val codec = MaskedItemCodec()

    @Test
    @DisplayName("인코딩한 표를 그대로 되읽는다 (왕복)")
    fun `왕복한다`() {
        val items =
            listOf(
                MaskedItem(MaskCategory.RRN, "[[주민등록번호1]]", Secret("900101-1234567")),
                MaskedItem(MaskCategory.CARD, "[[카드번호1]]", Secret("1234-5678-9012-3456")),
            )

        val decoded = codec.decode(codec.encode(items))

        assertThat(decoded).hasSize(2)
        assertThat(decoded.map { it.category }).containsExactly(MaskCategory.RRN, MaskCategory.CARD)
        assertThat(decoded.map { it.placeholder }).containsExactly("[[주민등록번호1]]", "[[카드번호1]]")
        assertThat(decoded.map { it.original.reveal() })
            .containsExactly("900101-1234567", "1234-5678-9012-3456")
    }

    @Test
    @DisplayName("빈 표도 왕복한다 — 가릴 것이 없는 문서가 정상이다")
    fun `빈 표도 왕복한다`() {
        assertThat(codec.decode(codec.encode(emptyList()))).isEmpty()
    }

    @Test
    @DisplayName("저장 형태를 바이트로 못박는다 — 범주는 **안정된 키**이지 화면 문구가 아니다")
    fun `저장 형태를 못박는다`() {
        val items = listOf(MaskedItem(MaskCategory.RRN, "[[주민등록번호1]]", Secret("900101-1234567")))

        val encoded = codec.encode(items).value

        assertThat(encoded).isEqualTo(
            """[{"category":"rrn","placeholder":"[[주민등록번호1]]","original":"900101-1234567"}]""",
        )
    }

    @Test
    @DisplayName("범주 저장 키가 계약 enum 값(한국어)과 **다르다** — 문구를 다듬어도 옛 행이 읽힌다")
    fun `저장 키와 화면 문구가 다르다`() {
        MaskCategory.entries.forEach { category ->
            val key = MaskedItemCodec.CATEGORY_KEYS.getValue(category)
            assertThat(key)
                .describedAs("%s 의 저장 키가 화면 문구와 같다 — 문구를 바꾸는 날 옛 행이 안 읽힌다", category)
                .isNotEqualTo(category.label)
        }
    }

    @Test
    @DisplayName("모든 범주에 저장 키가 있다 — 범주가 늘면 이 표를 함께 늘려야 한다")
    fun `모든 범주에 키가 있다`() {
        assertThat(MaskedItemCodec.CATEGORY_KEYS.keys).containsExactlyInAnyOrderElementsOf(MaskCategory.entries)
        assertThat(MaskedItemCodec.CATEGORY_KEYS.values.toSet())
            .describedAs("두 범주가 같은 저장 키를 쓰면 되읽을 때 갈린다")
            .hasSameSizeAs(MaskCategory.entries)
    }

    @Test
    @DisplayName("고정된 저장 문자열이 기대한 값으로 디코딩된다 — 인코더와 디코더가 함께 바뀌어도 잡힌다")
    fun `고정 문자열을 디코딩한다`() {
        val stored =
            PlainBody("""[{"category":"card","placeholder":"[[카드번호2]]","original":"4111111111111111"}]""")

        val decoded = codec.decode(stored)

        assertThat(decoded.single().category).isEqualTo(MaskCategory.CARD)
        assertThat(decoded.single().placeholder).isEqualTo("[[카드번호2]]")
        assertThat(decoded.single().original.reveal()).isEqualTo("4111111111111111")
    }

    @Test
    @DisplayName("원값은 Secret 으로 나온다 — 로그 한 줄이 곧 개인정보 유출이 되지 않는다")
    fun `원값이 가려진다`() {
        val stored =
            PlainBody("""[{"category":"rrn","placeholder":"[[주민등록번호1]]","original":"900101-1234567"}]""")

        val view = codec.decode(stored).single()

        assertThat(view.toString())
            .describedAs("data class 의 toString 에 원값이 실렸다")
            .doesNotContain("900101-1234567")
        assertThat(view.original.toString()).isEqualTo(Secret.MASK)
    }

    @Test
    @DisplayName("형식이 어긋난 값은 조용히 빈 목록이 아니라 5xx 다 — 「가린 항목 없음」은 실패처럼 보이지 않는다")
    fun `형식 오류는 5xx 다`() {
        listOf(
            PlainBody("not json"),
            PlainBody("""{"category":"rrn"}"""),
            PlainBody("""[{"category":"phone","placeholder":"[[전화번호1]]","original":"010"}]"""),
            PlainBody("""[{"category":"rrn","placeholder":"","original":"900101-1234567"}]"""),
            PlainBody("""[{"category":"rrn","placeholder":"[[주민등록번호1]]","original":""}]"""),
            PlainBody("""["문자열 원소"]"""),
        ).forEach { malformed ->
            assertThatThrownBy { codec.decode(malformed) }
                .describedAs("입력: %s", malformed)
                .isInstanceOf(StorageException::class.java)
        }
    }

    @Test
    @DisplayName("거절 문구에 저장된 값 조각이 실리지 않는다")
    fun `거절 문구가 값을 담지 않는다`() {
        val secretish = "900101-1234567"

        assertThatThrownBy { codec.decode(PlainBody("""[{"category":"rrn","original":"$secretish"}]""")) }
            .isInstanceOf(StorageException::class.java)
            .hasMessageNotContaining(secretish)
    }
}
