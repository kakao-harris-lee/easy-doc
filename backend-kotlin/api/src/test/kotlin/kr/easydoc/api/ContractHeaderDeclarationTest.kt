package kr.easydoc.api

import kr.easydoc.api.support.ContractHeaderDeclaration
import kr.easydoc.api.support.ContractSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * **계약이 선언한 응답 헤더가 전부 세어지고, 갈래마다 정본이 있다** (게이트 24 codex X24-5).
 *
 * ## 무엇이 문제였나
 *
 * `ContractSpec` 은 응답 헤더를 `$ref` 로만 읽었고 `$ref` 가 없는 선언은
 * `?: return@forEach` 로 **조용히 버렸다.** 그 상태에서 접근자의 KDoc 은 *"새 헤더가 생겨도
 * 검사 범위가 저절로 는다"* 고 적혀 있었으므로, **선언한 범위와 실제 도달이 어긋났다.**
 * 구현 산출물은 이 자리를 "인라인 헤더가 처음 들어오는 커밋에서 고친다"로 이월했는데,
 * codex 가 **그 커밋을 실패시키는 강제자가 없다**고 지적했다. 이 파일이 그 강제자다.
 *
 * ## 오늘의 실측 — 인라인 헤더는 0건이 아니라 2건이다
 *
 * 리뷰 종합은 "오늘 계약에 인라인 헤더 0건"을 전제로 했으나 실측은 **2건**이다
 * (2026-08-19, `contracts/easy-doc-v1.yaml`):
 *
 * | 헤더 | 자리 | 왜 인라인인가 |
 * |---|---|---|
 * | `Location` | `POST /documents` 202 | 값이 `/conversions/{id}` 로 **계산된다** — `const` 로 못박을 수 없다 |
 * | `Content-Disposition` | `GET /conversions/{id}/export` 200 | 파일명이 문서마다 다르다(RFC 5987) |
 *
 * 둘 다 **Phase 4 의 경로**라 구현이 아직 없다. 그러므로 이 두 헤더에 대해 오늘 확인할 수
 * 있는 것은 「계약이 값의 형식을 `schema` 로 적어 두었다」까지이고, 실제 응답이 그 형식을
 * 지키는지는 Phase 4 가 그 엔드포인트를 만들 때 잰다.
 *
 * ## 이 테스트가 거는 강제
 *
 * - 인라인 갈래의 **집합을 고정한다.** 셋째가 생기면 이 테스트가 빨개진다. 그때 정할 것은
 *   둘 중 하나다 — 값이 고정이면 `components/headers` 로 옮겨 `const` 를 주고, 계산되는
 *   값이면 그 형식을 재는 테스트를 함께 넣고 여기 한 줄을 늘린다. **집합을 늘리는 것 자체가
 *   리뷰에 올라가는 diff** 이므로 면제가 조용히 자라지 않는다.
 * - 컴포넌트 갈래는 **전부 `const` 를 갖는다**는 것을 확인한다. 컴포넌트를 가리키면서 값이
 *   못박히지 않은 헤더가 있으면 그 헤더의 계약 값은 어디에도 없다.
 */
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
        /**
         * `$ref` 없이 경로에 직접 적힌 헤더. **값이 계산되는 것들**이다.
         *
         * 이 목록은 면제가 아니다 — 여기 적힌 헤더도 [ContractSpec.headerDeclarations] 가
         * 세고 `schema` 유무를 검사한다. 고정하는 것은 「이 갈래에 무엇이 들어 있는가」이고,
         * 늘어나는 순간 diff 가 리뷰에 올라가게 하는 것이 목적이다.
         */
        val INLINE_HEADERS = listOf("Location", "Content-Disposition")

        /**
         * 반드시 세어져야 하는 헤더 — **바닥**이다(정확 일치가 아니라 포함).
         *
         * `WWW-Authenticate` 가 여기 있는 이유: 계약이 그것을
         * `components/responses/Unauthorized` 안에 두었고 경로들은 그 응답을 `$ref` 로
         * 가리킨다. 종전 파서는 응답 `$ref` 를 따라가지 않아 **이 헤더를 한 번도 세지 못했다.**
         */
        val EXPECTED_HEADER_NAMES =
            listOf(
                "Cache-Control",
                "X-Content-Type-Options",
                "WWW-Authenticate",
                "Location",
                "Content-Disposition",
            )
    }
}
