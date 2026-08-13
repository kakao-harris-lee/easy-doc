package kr.easydoc.core.privacy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * [MaskedText] 를 만드는 통로가 **마스킹을 반드시 수행하는 것 하나뿐**인지 상시 확인한다.
 *
 * ## 왜 이 테스트가 따로 있나 (교차 리뷰 X-7)
 *
 * "감싸기만 하는 통로가 없다"는 컴파일러가 강제하지 않는다 — 통로를 하나 더 만들면 컴파일은
 * 그대로 통과하고 불변식만 조용히 사라진다. 그리고 **이 자리의 회귀는 이미 한 번 일어났다.**
 * `1ffaf93` 이전 판은 `internal fun wrap(masked: String): MaskedText` 를 열어 두었고,
 * KDoc 은 "이 파일의 maskText 만 만들 수 있다"고 적혀 있었지만 `internal` 은 모듈 전체라
 * core 안 아무 파일에서나 `MaskedText.wrap(임의_문자열)` 이 컴파일됐다. 선언한 범위(파일)와
 * 실제 도달 범위(모듈)가 달랐고, 그것을 잡아 줄 상시 장치가 없었다.
 *
 * 형제 불변식인 `LlmPrompt` 는 같은 형태의 가드를 이미 갖고 있다(`LlmPromptTest`
 * 「생성자가 열려 있지 않다」). 이 파일은 그것을 [MaskedText] 쪽에 대칭으로 세운 것이다.
 *
 * ## `LlmPrompt` 와 검사 대상이 다른 이유
 *
 * [MaskedText] 는 `@JvmInline value class` 라 **소스의 생성자가 JVM 생성자로 남지 않는다.**
 * `declaredConstructors` 는 합성(synthetic) 항목 하나뿐이고, 실제 생성 진입점은 정적 메서드
 * `constructor-impl` 이며 **그 가시성이 소스 생성자의 가시성을 그대로 반영한다**(실측:
 * `private static java.lang.String constructor-impl(java.lang.String)`). 그래서 여기서는
 * 생성자 대신 그 메서드를 본다. `LlmPrompt` 쪽 검사를 그대로 복사하면 대상이 없어 항상
 * 통과하는 빈 검사가 된다 — 도달 0인 가드다.
 *
 * ## 이 가드가 막지 **못하는** 것
 *
 * - 리플렉션·바이트코드 조작. 어떤 가시성으로도 막지 못한다.
 * - Java 호출자. `@JvmInline value class` 는 JVM 에서 `String` 으로 지워지고,
 *   합성 `box-impl` 은 public 이다.
 * - [maskText] 자체가 마스킹을 그만두는 회귀. 그것은 `MaskingTest` 와 masking parity
 *   fixture 가 값으로 잡는다. 여기서 보는 것은 **통로의 모양**뿐이다.
 */
class MaskedTextGatewayTest {
    /**
     * value class 의 실제 생성 진입점. Kotlin 인라인 클래스 ABI 의 일부라 이름이 고정돼 있다.
     * 찾지 못하면 통과시키지 않고 **실패**시킨다 — 대상이 사라진 가드는 통과가 아니라 미검사다.
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
        // `internal` 함수는 JVM 에서 `mask$core` 로 이름이 뭉개진다. 뭉갠 접미사는 컴파일러
        // 구현 세부라 판정 기준으로 삼지 않고, `$` 앞의 본디 이름만 본다.
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
        // 교차 종합 C-18. 위 두 단언은 **companion 표면**만 보는데 클래스 KDoc 은 클래스
        // 전체를 선언한다 — 선언한 범위와 실제 도달 범위가 어긋난 자리다. `wrap` 을
        // companion 이 아니라 클래스 본체의 함수로 만들면 위 검사를 그대로 빠져나간다.
        //
        // 허용목록에 담은 것은 **Kotlin 인라인 클래스 ABI**가 만드는 이름과 이 타입이
        // 스스로 재정의한 것뿐이다. `-impl` 접미사 계열은 컴파일러 산물이고,
        // `constructor-impl` 의 가시성은 위 첫 번째 테스트가 따로 본다.
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
