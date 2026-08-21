package kr.easydoc.core.document

import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.exceptions.InvalidInputException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 제목 규칙과 문자 수 세기 — Spring 도 DB 도 없이 돈다(계획 §3.2). */
class TitleRulesTest {
    /** 시그니처 핀 — 제목의 바탕이 될 수 있는 자리가 정확히 하나임을 컴파일러가 진다. */
    private val signaturePin: (String?) -> String = ::resolveTitle

    @Test
    @DisplayName("제목의 바탕을 받는 자리는 **하나**다 — 본문도 파일 이름도 받을 통로가 없다")
    fun `바탕을 받는 자리는 하나다`() {
        assertThat(signaturePin(null)).isEqualTo(FALLBACK_TITLE)
        assertThat(signaturePin("복지 안내")).isEqualTo("복지 안내")
    }

    @Test
    @DisplayName("적어 준 제목이 없으면 **대체 제목**이다 — 본문에서도 파일 이름에서도 만들지 않는다")
    fun `근거가 없으면 대체 제목이다`() {
        assertThat(resolveTitle(null)).isEqualTo(FALLBACK_TITLE)
        assertThat(resolveTitle("")).isEqualTo(FALLBACK_TITLE)
        assertThat(resolveTitle("   ")).isEqualTo(FALLBACK_TITLE)
    }

    @Test
    @DisplayName("제어문자만 적어 준 제목도 대체 제목이다 — 다른 값으로 덮지 않는다")
    fun `제어문자뿐인 제목은 대체 제목이다`() {
        assertThat(resolveTitle("\u0001\u0002\u0003")).isEqualTo(FALLBACK_TITLE)
    }

    @Test
    @DisplayName("사용자가 준 제목은 앞뒤 공백만 털고 그대로 쓴다 — 짧게 줄이지 않는다")
    fun `사용자 제목은 그대로 쓴다`() {
        val given = "  2026년 상반기 복지 안내문 발송 계획 알림 문서입니다  "

        assertThat(resolveTitle(given)).isEqualTo(given.trim())
    }

    @Test
    @DisplayName("제어문자를 자르기 **전에** 걷어낸다 — 잘린 길이가 보이는 글자 수와 맞는다")
    fun `제어문자를 먼저 걷어낸다`() {
        val control = "\u0000".repeat(10)
        val name = "가".repeat(MAX_TITLE_LENGTH)

        val resolved = resolveTitle(control + name)

        assertThat(resolved).isEqualTo(name)
        assertThat(resolved).hasSize(MAX_TITLE_LENGTH)
    }

    @Test
    @DisplayName("제목이 상한을 넘으면 **거절하지 않고 자른다** (계약 x-input-limits.max_title_length)")
    fun `제목은 상한에서 잘린다`() {
        val resolved = resolveTitle("나".repeat(MAX_TITLE_LENGTH + 50))

        assertThat(charCountOf(resolved)).isEqualTo(MAX_TITLE_LENGTH)
    }

    /** K-14 — 계약 `x-title-policy.rule` 이 요구하는 정제. */
    @Test
    @DisplayName("K-14 제목의 짝 없는 서로게이트를 **걷어낸다** — 나머지 글자는 그대로 남는다")
    fun `제목의 짝 없는 서로게이트를 걷어낸다`() {
        assertThat(resolveTitle("복지\uD800안내")).isEqualTo("복지안내")
        assertThat(resolveTitle("안내문\uD83D")).isEqualTo("안내문")
        assertThat(resolveTitle("x\uDC00y")).isEqualTo("xy")
    }

    @Test
    @DisplayName("K-14 정제 후 남는 것이 없으면 **대체 제목**이다 — 거절이 아니다")
    fun `서로게이트만 있는 제목은 대체 제목이다`() {
        assertThat(resolveTitle("\uD800\uD800")).isEqualTo(FALLBACK_TITLE)
    }

    @Test
    @DisplayName("K-14 정제된 제목은 UTF-8 로 왕복한다 — 평문 열에 쓰는 시점에 갈리지 않는다")
    fun `정제된 제목이 UTF-8 로 왕복한다`() {
        val resolved = resolveTitle("복지\uD800안내\uD83D")

        assertThat(String(resolved.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
            .describedAs("UTF-8 왕복에서 값이 바뀌면 짝 없는 서로게이트가 남은 것이다")
            .isEqualTo(resolved)
    }

    @Test
    @DisplayName("K-14 **짝을 이룬 쌍은 그대로 남는다** — 정제가 BMP 밖 문자 전체로 번지지 않았다")
    fun `짝 맞는 서로게이트는 제목에 남는다`() {
        assertThat(resolveTitle("민원 안내 🙂")).isEqualTo("민원 안내 🙂")
        assertThat(resolveTitle("𝓐 서식")).isEqualTo("𝓐 서식")
    }

    /** 본문과 갈리는 축 — 같은 문자에 제목은 정제, 본문은 거절이다. */
    @Test
    @DisplayName("K-14 제목은 **던지지 않는다** — 같은 값이 저장 본문에서는 거절된다")
    fun `제목은 던지지 않고 본문은 거절된다`() {
        val broken = "복지\uD800안내"

        assertThatCode { resolveTitle(broken) }
            .describedAs("제목에서 잃는 것은 라벨 하나다 — 라벨 때문에 문서 접수를 거절하지 않는다")
            .doesNotThrowAnyException()
        assertThatThrownBy { PlainBody(broken) }
            .describedAs("본문에서 잃는 것은 문서다 — 여기는 정제가 아니라 거절이다")
            .isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    @DisplayName("상한에서 자를 때 서로게이트 쌍을 쪼개지 않는다 — 쪼개면 우리가 만든 손상이다")
    fun `자르기가 서로게이트 쌍을 쪼개지 않는다`() {
        val astral = "𝓐"
        val given = astral.repeat(MAX_TITLE_LENGTH + 10)

        val resolved = resolveTitle(given)

        assertThat(charCountOf(resolved)).isEqualTo(MAX_TITLE_LENGTH)
        assertThat(resolved.any { it.isSurrogate() })
            .describedAs("서로게이트가 있어야 이 케이스가 무언가를 재고 있다")
            .isTrue()
        assertThat(resolved.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
            .describedAs("UTF-8 왕복에서 값이 바뀌면 짝 없는 서로게이트가 남은 것이다")
            .isEqualTo(resolved)
    }

    @Test
    @DisplayName("문자 수는 **코드 포인트**로 센다 — UTF-16 코드 단위로 세면 이모지 문서가 두 배로 계산된다")
    fun `문자 수는 코드 포인트다`() {
        val astral = "𝓐"

        assertThat(charCountOf(astral)).isEqualTo(1)
        assertThat(astral.length).describedAs("전제: 이 문자는 코드 단위 둘이다").isEqualTo(2)
        assertThat(charCountOf("가나다 라마")).isEqualTo(6)
    }

    @Test
    @DisplayName("takeCodePoints 는 짧은 입력을 그대로 돌려준다")
    fun `짧은 입력은 그대로다`() {
        assertThat(takeCodePoints("가나다", 10)).isEqualTo("가나다")
        assertThat(takeCodePoints("", 10)).isEmpty()
    }
}
