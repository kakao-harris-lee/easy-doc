package kr.easydoc.api

import kr.easydoc.api.support.ContractHeaderDeclaration
import kr.easydoc.api.support.ContractSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 계약이 선언한 응답 헤더가 전부 세어지고, 갈래마다 정본이 있다 (게이트 24 codex X24-5). */
class ContractHeaderDeclarationTest {
    @Test
    @DisplayName("계약이 선언한 응답 헤더가 하나도 빠지지 않고 세어진다")
    fun `선언된 헤더가 전부 세어진다`() {
        val declarations = ContractSpec.headerDeclarations()

        assertThat(declarations.keys)
            .withFailMessage {
                "계약의 응답 선언에서 헤더를 ${declarations.size} 개밖에 찾지 못했다 — " +
                    "파서가 갈래를 버리고 있다면 이 게이트는 통과가 아니라 미검사다."
            }.containsAll(EXPECTED_HEADER_NAMES)
    }

    @Test
    @DisplayName("인라인 헤더 집합이 고정돼 있다 — 새 인라인 헤더 커밋이 실패한다")
    fun `인라인 헤더 집합이 고정돼 있다`() {
        assertThat(ContractSpec.inlineHeaderNames())
            .withFailMessage {
                "계약의 인라인 응답 헤더 집합이 바뀌었다: 기대 $INLINE_HEADERS, 실제 ${ContractSpec.inlineHeaderNames()}\n" +
                    "  **늘었다면**: 값이 고정인 헤더인가? 그러면 `components/headers` 로 옮기고 `const` 를 줘라 — " +
                    "그래야 `globalHeaderValues()`·`headerConst()` 가 값의 정본을 읽는다.\n" +
                    "  계산되는 값이라면 그 형식을 재는 테스트를 함께 넣고 이 목록에 한 줄을 늘려라.\n" +
                    "  **줄었다면**: 계약에서 헤더가 사라졌거나 컴포넌트로 옮겨간 것이다. 그쪽 테스트를 확인하라."
            }.containsExactlyInAnyOrderElementsOf(INLINE_HEADERS)
    }

    @Test
    @DisplayName("컴포넌트 갈래 헤더는 전부 계약이 값을 const 로 못박았다")
    fun `컴포넌트 헤더는 값의 정본을 갖는다`() {
        val components =
            ContractSpec
                .headerDeclarations()
                .values
                .filterIsInstance<ContractHeaderDeclaration.Component>()
                .map { it.component }
                .distinct()

        assertThat(components)
            .withFailMessage { "컴포넌트를 가리키는 헤더 선언이 하나도 없다 — `\$ref` 해석이 죽었다" }
            .isNotEmpty()

        components.forEach { component ->
            assertThat(ContractSpec.headerConst(component))
                .withFailMessage { "헤더 컴포넌트 $component 에 `schema.const` 가 없다 — 이 헤더의 계약 값이 어디에도 없다" }
                .isNotBlank()
        }
    }

    private companion object {
        /** `$ref` 없이 경로에 직접 적힌 헤더. 값이 계산되는 것들이다. */
        val INLINE_HEADERS = listOf("Location", "Content-Disposition", "Retry-After", "X-Remaining-Call-Budget")

        /** 반드시 세어져야 하는 헤더 — 바닥이다(정확 일치가 아니라 포함). */
        val EXPECTED_HEADER_NAMES =
            listOf(
                "Cache-Control",
                "X-Content-Type-Options",
                "WWW-Authenticate",
                "Location",
                "Content-Disposition",
                "Retry-After",
                "X-Remaining-Call-Budget",
            )
    }
}
