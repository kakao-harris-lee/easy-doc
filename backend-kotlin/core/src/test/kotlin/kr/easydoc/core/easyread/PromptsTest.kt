package kr.easydoc.core.easyread

import kr.easydoc.core.privacy.ModelDraft
import kr.easydoc.core.privacy.maskText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * 프롬프트 생성의 **성질**을 고정한다. 문자열 전문 대조는 `PromptTextSnapshotTest` 가 한다.
 *
 * 여기서 보는 것은 스냅샷이 잡지 못하는 것들이다 — 스냅샷은 고른 케이스에서 값이 같음을
 * 보이지만, "왜 그 값인가"(어떤 규칙으로 목록이 좁혀지는가, 무엇이 무엇을 참조하는가)는
 * 케이스를 하나 더 늘려도 드러나지 않는다.
 */
class PromptsTest {
    private fun systemPromptOf(text: String): String = buildSystemPrompt(maskText(text).maskedText)

    /** `[어려운 표현 바꾸기]` 절에 실린 낱말만 뽑는다. */
    private fun listedAlways(prompt: String): List<String> = listedWords(prompt, "[어려운 표현 바꾸기]", "[문맥을 보고 판단할 표현]")

    /** `[문맥을 보고 판단할 표현]` 절에 실린 낱말만 뽑는다. */
    private fun listedConditional(prompt: String): List<String> = listedWords(prompt, "[문맥을 보고 판단할 표현]", "[개인정보 표시]")

    private fun listedWords(
        prompt: String,
        from: String,
        to: String,
    ): List<String> =
        prompt
            .substring(prompt.indexOf(from), prompt.indexOf(to))
            .lineSequence()
            .mapNotNull { Regex("""^- (.+) \(뜻: .+\)$""").find(it)?.groupValues?.get(1) }
            .toList()

    @Nested
    @DisplayName("동적 어려운 말 목록")
    inner class DynamicWordList {
        @Test
        @DisplayName("문서에 나온 낱말만 싣는다")
        fun `등장한 낱말만 실린다`() {
            // 246개 전량은 입력과 무관한 고정 비용인데(실측 2026-08-08: 시스템 프롬프트
            // 5,825자 중 2,927자) 문서 한 건이 쓰는 낱말은 그중 소수다.
            val listed = listedAlways(systemPromptOf("금일 중 서류를 지참하세요."))

            assertThat(listed).containsExactly("금일", "지참")
            assertThat(listed).doesNotContain("감면", "별도", "제출")
        }

        @Test
        @DisplayName("아무 낱말도 없으면 목록이 빈다")
        fun `해당 낱말이 없으면 빈 목록이다`() {
            assertThat(listedAlways(systemPromptOf("오늘 신청을 받습니다."))).isEmpty()
        }

        @Test
        @DisplayName("목록 순서는 등장 순서가 아니라 사전 정의 순서다")
        fun `사전 순서로 싣는다`() {
            // 같은 낱말 집합이면 항상 같은 문자열이 나와야 프롬프트가 요청마다 흔들리지 않는다.
            val dictionaryOrder = listedAlways(systemPromptOf("금일 지참 제출"))
            val reversedAppearance = listedAlways(systemPromptOf("제출 지참 금일"))

            assertThat(dictionaryOrder).isEqualTo(reversedAppearance)
            assertThat(dictionaryOrder).containsExactly("금일", "지참", "제출")
        }

        @Test
        @DisplayName("낱말 시작 위치가 아니면 싣지 않는다")
        fun `복합어 안쪽은 싣지 않는다`() {
            // '소득인정액'의 '정액', '대지급금'의 '지급'은 더 긴 낱말의 일부다.
            // 이것을 실으면 모델이 제도 이름을 옳게 지킨 자리를 고치라고 시키게 된다.
            assertThat(listedAlways(systemPromptOf("소득인정액과 대지급금 안내"))).isEmpty()
        }

        @Test
        @DisplayName("문맥 판단 그룹은 입력과 무관하게 항상 싣는다")
        fun `PROMPT_ONLY_WORDS 는 항상 실린다`() {
            // 5개뿐이라 걸러도 이득이 없고, 입력에 원형이 없어도 모델이 활용형으로 만들어 낸다.
            val absent = listedConditional(systemPromptOf("아무 상관 없는 본문입니다."))
            val present = listedConditional(systemPromptOf("상기 내용을 확인하기 바랍니다."))

            assertThat(absent).containsExactlyElementsOf(PROMPT_ONLY_WORDS)
            assertThat(present).containsExactlyElementsOf(PROMPT_ONLY_WORDS)
        }

        @Test
        @DisplayName("문맥 판단 그룹은 치환 목록에 실리지 않는다")
        fun `PROMPT_ONLY_WORDS 는 치환 목록에 없다`() {
            // 두 절이 같은 낱말을 다르게 지시한다("무조건 없애라" vs "문맥을 보고 판단하라").
            // 양쪽에 실리면 모델에게 모순된 지시를 주는 것이다.
            val listed = listedAlways(systemPromptOf("상기 내용을 확인하기 바랍니다. 하자가 있으면 게시하세요."))

            assertThat(listed).doesNotContainAnyElementsOf(PROMPT_ONLY_WORDS)
        }

        @Test
        @DisplayName("목록을 좁혀도 출력 검사는 사전 전량 기준이다")
        fun `필터링이 검출력을 깎지 않는다`() {
            // 입력에 없던 어려운 낱말을 모델이 새로 만들어 내는 경우가 이 최적화의 사각지대인데,
            // checkStyle 이 246개 전체 기준으로 잡아 보정 패스로 넘긴다.
            val input = "오늘 신청을 받습니다."
            assertThat(listedAlways(systemPromptOf(input))).isEmpty()

            val modelOutput = "금일 중 서류를 지참하세요."
            val issues = checkStyle(modelOutput).issues.mapNotNull { it.word }
            assertThat(issues).contains("금일", "지참")
        }
    }

    @Nested
    @DisplayName("스타일 규칙 SSOT 참조")
    inner class StyleRuleSource {
        @Test
        @DisplayName("변환·보정 프롬프트가 같은 원칙 목록을 1번부터 싣는다")
        fun `원칙 목록을 공유한다`() {
            // 프롬프트에 규칙을 하드코딩하면 모델이 지키라고 들은 수치와 채점 수치가 갈라지고,
            // 통과율이 모델 실력이 아니라 두 기준의 차이를 재게 된다(CLAUDE.md 아키텍처 규칙 4).
            val numbered =
                STYLE_PRINCIPLES
                    .mapIndexed { index, principle -> "${index + 1}. $principle" }
                    .joinToString("\n")

            assertThat(systemPromptOf("본문입니다.")).contains(numbered)
            assertThat(buildRepairPrompt(ModelDraft("변환문입니다."), emptyList()).system).contains(numbered)
        }

        @Test
        @DisplayName("임계값이 상수에서 보간된다")
        fun `임계값을 하드코딩하지 않는다`() {
            val prompt = systemPromptOf("본문입니다.")

            assertThat(prompt).contains("${MAX_SENTENCE_CHARS}자를 넘기면 안 됩니다")
            assertThat(prompt).contains("쉼표(,)는 ${MAX_COMMAS_PER_SENTENCE}개까지만 씁니다")
        }
    }

    @Nested
    @DisplayName("보정 프롬프트")
    inner class RepairPrompt {
        private val long = "금일 중 서류를 지참하여 방문하시고, 접수 후 결과를 확인하시기 바랍니다."

        @Test
        @DisplayName("한 문장의 여러 위반을 한 블록으로 접는다")
        fun `문장을 되풀이하지 않는다`() {
            // 위반마다 문장을 되풀이하면 지시가 변환문보다 길어져 입력 토큰이 크게 는다.
            val violations = checkStyle(long).issues
            assertThat(violations.map { it.sentence }.distinct()).hasSize(1)
            assertThat(violations.size).isGreaterThan(1)

            val user = buildRepairPrompt(ModelDraft(long), violations).user
            assertThat(user.windowed(long.length).count { it == long }).isEqualTo(2) // 본문 1 + 지적 1
        }

        @Test
        @DisplayName("지적된 낱말의 뜻풀이를 한 줄씩만 준다")
        fun `뜻풀이를 낱말마다 한 번만 싣는다`() {
            val duplicated = List(3) { SentenceIssue(long, "어려운 표현 잔존(금일)", "금일") }
            val user = buildRepairPrompt(ModelDraft(long), duplicated).user

            assertThat(user.windowed(GLOSS_LINE.length).count { it == GLOSS_LINE }).isEqualTo(1)
        }

        @Test
        @DisplayName("사전에 없는 낱말은 뜻풀이 줄을 만들지 않는다")
        fun `사전에 없으면 뜻풀이가 없다`() {
            val user =
                buildRepairPrompt(
                    ModelDraft("본문입니다."),
                    listOf(SentenceIssue("본문입니다.", "어려운 표현 잔존(없는말)", "없는말")),
                ).user

            assertThat(user).contains("문제: 어려운 표현 잔존(없는말)")
            assertThat(user).doesNotContain("'없는말' (뜻:")
        }
    }

    @Nested
    @DisplayName("마스킹 선행 강제")
    inner class MaskingPrecedence {
        /**
         * 원문 `String` 오버로드가 **생기지 않았는지** 실행으로 확인한다.
         *
         * ## 왜 이런 방식인가
         *
         * `MaskedText` 는 `@JvmInline value class` 라 JVM 시그니처에서는 `String` 으로
         * 지워진다 — 파라미터 타입을 반사(reflection)로 봐도 두 경우를 구별할 수 없다.
         * 대신 Kotlin 이 **인라인 클래스를 받는 함수의 JVM 이름을 변형**한다는 사실을 쓴다
         * (`buildSystemPrompt` → `buildSystemPrompt-<hash>`). 생 `String` 을 받는 오버로드는
         * 변형되지 않으므로 **이름이 그대로인 메서드가 나타나는 것**이 곧 우회의 증거다.
         *
         * ## 이 테스트가 증명하지 못하는 것
         *
         * 이름이 다른 새 함수(`buildSystemPromptRaw(String)`)는 잡지 못한다. 컴파일 자체를
         * 막는 것은 `MaskedText` 의 생성 통로가 마스킹 하나뿐이라는 사실이고(`Masking.kt`),
         * 이 테스트는 **가장 자연스러운 우회 한 가지**를 닫을 뿐이다.
         */
        @Test
        @DisplayName("본문을 받는 함수에 생 String 오버로드가 없다")
        fun `원문 String 오버로드가 없다`() {
            val promptsClass = Class.forName("kr.easydoc.core.easyread.PromptsKt")
            val publicMethods =
                promptsClass.declaredMethods.filter { Modifier.isPublic(it.modifiers) }

            for (base in listOf("buildSystemPrompt", "buildUserPrompt", "buildRepairPrompt")) {
                val related = publicMethods.filter { it.name.startsWith(base) }
                assertThat(related)
                    .withFailMessage("$base 가 사라졌다 — 테스트가 아무것도 지키지 않는 상태다.")
                    .isNotEmpty()
                assertThat(related.map { it.name })
                    .withFailMessage(
                        "%s 에 이름이 변형되지 않은 오버로드가 있다 = 인라인 클래스가 아닌 인자를 받는다. " +
                            "마스킹을 거치지 않은 원문이 LLM 페이로드로 들어가는 경로다.",
                        base,
                    ).allMatch { it != base }
            }
        }
    }

    private companion object {
        val GLOSS_LINE = "   '금일' (뜻: ${DIFFICULT_WORD_REPLACEMENTS.getValue("금일")})"
    }
}
