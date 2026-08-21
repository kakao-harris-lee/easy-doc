package kr.easydoc.core.privacy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** [MaskedText] 를 만드는 통로가 마스킹을 반드시 수행하는 것 하나뿐인지 상시 확인한다. */
class MaskedTextGatewayTest {
    /**
     * value class 의 실제 생성 진입점. Kotlin 인라인 클래스 ABI 의 일부라 이름이 고정돼 있다.
     * 찾지 못하면 통과시키지 않고 실패시킨다 — 대상이 사라진 가드는 통과가 아니라 미검사다.
     */
    private fun constructorImpl(): Method =
        MaskedText::class.java.declaredMethods
            .filterNot { it.isSynthetic }
            .firstOrNull { it.name == "constructor-impl" }
            ?: error(
                "MaskedText 에서 `constructor-impl` 을 찾지 못했다. Kotlin 인라인 클래스 ABI 가 바뀌었을 수 있다 — " +
                    "이 가드는 대상이 없으면 아무것도 검사하지 않으므로, 통과시키지 말고 검사 대상을 다시 정하라.",
            )

    @Test
    @DisplayName("MaskedText 생성 진입점이 private 이다")
    fun `생성자가 열려 있지 않다`() {
        val entry = constructorImpl()

        assertThat(Modifier.isPrivate(entry.modifiers))
            .withFailMessage {
                "MaskedText 생성 진입점이 private 이 아니다 (${entry.toGenericString()}). " +
                    "열리는 순간 마스킹을 거치지 않은 문자열을 MaskedText 로 감쌀 수 있게 되고, " +
                    "CLAUDE.md 아키텍처 규칙 2(마스킹 선행)가 타입 강제에서 주석으로 되돌아간다."
            }.isTrue()
    }

    @Test
    @DisplayName("companion 이 여는 통로는 mask 하나뿐이다 — wrap 류의 재등장을 막는다")
    fun `감싸기만 하는 통로가 없다`() {
        val exposed =
            MaskedText.Companion::class.java.declaredMethods
                .filterNot { it.isSynthetic }
                .map { it.name.substringBefore('$') }
                .sorted()

        assertThat(exposed)
            .withFailMessage {
                "MaskedText.Companion 이 여는 함수가 [mask] 가 아니라 $exposed 다. " +
                    "1ffaf93 에서 되돌린 `wrap(masked: String)` 이 다시 생겼는지 확인하라 — " +
                    "임의 문자열을 감싸기만 하는 통로가 하나라도 있으면 마스킹 선행 불변식은 타입이 아니라 주석이 된다."
            }.containsExactly("mask")
    }

    @Test
    @DisplayName("클래스 표면에도 우회 통로가 없다 — companion 밖까지 본다")
    fun `클래스가 여는 비합성 메서드가 허용목록뿐이다`() {
        val declared =
            MaskedText::class.java.declaredMethods
                .filterNot { it.isSynthetic }
                .map { it.name }
                .toSet()

        val abiOrOverride =
            setOf(
                "constructor-impl",
                "equals",
                "equals-impl",
                "equals-impl0",
                "getValue",
                "hashCode",
                "hashCode-impl",
                "toString",
                "toString-impl",
            )

        assertThat(declared - abiOrOverride)
            .withFailMessage {
                "MaskedText 가 ABI·재정의 밖의 메서드를 연다: ${(declared - abiOrOverride).sorted()}\n" +
                    "  companion 이 아니라 클래스 본체에 감싸기 통로가 생기면 companion 검사를 " +
                    "그대로 빠져나간다. 새 메서드가 마스킹을 수행하는지 확인하고 허용목록에 근거와 함께 더하라."
            }.isEmpty()
    }

    @Test
    @DisplayName("유일한 통로는 감싸기가 아니라 실제 마스킹을 수행한다")
    fun `mask 는 입력을 그대로 통과시키지 않는다`() {
        val result = MaskedText.mask("신청자 900101-1234567 님")

        assertThat(result.maskedText.value)
            .withFailMessage("MaskedText.mask 가 마스킹을 수행하지 않고 입력을 그대로 감쌌다")
            .doesNotContain("900101-1234567")
        assertThat(result.items).hasSize(1)
    }
}
