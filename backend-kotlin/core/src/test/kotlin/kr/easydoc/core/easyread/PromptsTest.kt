package kr.easydoc.core.easyread

import kr.easydoc.core.privacy.ModelDraft
import kr.easydoc.core.segment.SourceStructure
import kr.easydoc.core.segment.UnitKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** 프롬프트 생성의 성질을 고정한다. 문자열 전문 대조는 `PromptTextSnapshotTest` 가 한다. */
class PromptsTest {
    private fun systemPromptOf(text: String): String = buildSystemPrompt(text)

    @Test
    fun `제목과 안내 표식은 변환과 보정에서 같은 항목에 보존한다`() {
        val prompts = listOf(systemPromptOf("○ 안내"), buildRepairPrompt(ModelDraft("안내"), emptyList()).system)
        for (prompt in prompts) {
            assertThat(prompt).contains("○", "※", "같은 항목", "새 표식", "표식은 그대로")
            assertThat(prompt).doesNotContain("설명이 필요한 기호는 말로 풀고")
        }
    }

    @Test
    fun `표식이 바뀐 보정 요청은 원문 기준의 복원을 명시한다`() {
        val prompt = buildRepairPrompt(ModelDraft("결과 안내"), emptyList(), sourceText = "○ 결과 안내")
        assertThat(prompt.user).contains("[표식 복원]", "같은 항목")
        assertThat(buildRepairPrompt(ModelDraft("○ 결과 안내"), emptyList(), sourceText = "○ 결과 안내").user)
            .doesNotContain("[표식 복원]")
    }

    @Test
    fun `기한의 행동과 압축된 비교 기준을 함께 보존하도록 안내한다`() {
        assertThat(systemPromptOf("본문")).contains("작성·제출·도착", "누구의 무엇과 비교")
        assertThat(SPLIT_EXAMPLES).contains("작성하여 제출", "고쳐서 내지 않으면")
    }

    @Test
    fun `본문 표의 출력과 원본 파일 구조의 보존을 구분한다`() {
        assertThat(systemPromptOf("본문")).contains("마크다운 표를 새로 만들지", "[구조] 지시")
    }

    @Test
    fun `문서 어휘를 시스템 지시로 중복 주입하지 않는다`() {
        assertThat(systemPromptOf("금일 지참 동봉 하기 내용"))
            .isEqualTo(systemPromptOf("오늘 신청을 받습니다."))
        assertThat(systemPromptOf("금일 지참"))
            .doesNotContain("- 금일 (뜻:", "- 하기 (뜻:")
    }

    @Test
    fun `변환과 보정에서 편집 기준을 한 번씩 공유한다`() {
        val prompts = listOf(systemPromptOf("본문"), buildRepairPrompt(ModelDraft("초안"), emptyList()).system)
        for (prompt in prompts) {
            for (instruction in listOf(ROLE, SOURCE_FIDELITY_INSTRUCTION, EXPLAIN_INSTRUCTION)) {
                assertThat(prompt.windowed(instruction.length).count { it == instruction }).isEqualTo(1)
            }
            assertThat(prompt).doesNotContain("원문과 사전으로 뜻을 확정할 수 없으면", "각 줄은 그 줄만 읽어도")
        }
    }

    @Nested
    @DisplayName("스타일 규칙 SSOT 참조")
    inner class StyleRuleSource {
        @Test
        @DisplayName("변환·보정 프롬프트가 같은 원칙 목록을 1번부터 싣는다")
        fun `원칙 목록을 공유한다`() {
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

            assertThat(prompt).contains("${MAX_SENTENCE_CHARS}자 안으로")
            assertThat(prompt).contains("쉼표는 가급적 한 문장에 ${MAX_COMMAS_PER_SENTENCE}개 이하로")
        }
    }

    @Nested
    @DisplayName("보정 프롬프트")
    inner class RepairPrompt {
        private val long = "금일 중 서류를 지참하여 방문하시고, 접수 후 결과를 확인하시기 바랍니다."

        @Test
        @DisplayName("한 문장의 여러 위반을 한 블록으로 접는다")
        fun `문장을 되풀이하지 않는다`() {
            val violations = checkStyle(long).issues
            assertThat(violations.map { it.sentence }.distinct()).hasSize(1)
            assertThat(violations.size).isGreaterThan(1)

            val user = buildRepairPrompt(ModelDraft(long), violations).user
            assertThat(user.windowed(long.length).count { it == long }).isEqualTo(2) // 본문 1 + 지적 1
        }

        @Test
        @DisplayName("지적된 낱말의 뜻풀이를 한 줄씩만 준다")
        fun `뜻풀이를 낱말마다 한 번만 싣는다`() {
            val duplicated = List(3) { SentenceIssue(long, StyleRuleKind.DIFFICULT_WORD, "어려운 표현 잔존(금일)", "금일") }
            val user = buildRepairPrompt(ModelDraft(long), duplicated).user

            assertThat(user.windowed(GLOSS_LINE.length).count { it == GLOSS_LINE }).isEqualTo(1)
        }

        @Test
        @DisplayName("사전에 없는 낱말은 뜻풀이 줄을 만들지 않는다")
        fun `사전에 없으면 뜻풀이가 없다`() {
            val user =
                buildRepairPrompt(
                    ModelDraft("본문입니다."),
                    listOf(
                        SentenceIssue(
                            "본문입니다.",
                            StyleRuleKind.DIFFICULT_WORD,
                            "어려운 표현 잔존(없는말)",
                            "없는말",
                        ),
                    ),
                ).user

            assertThat(user).contains("문제: 어려운 표현 잔존(없는말)")
            assertThat(user).doesNotContain("'없는말' (뜻:")
        }

        @Test
        @DisplayName("빠진 사실이 있으면 값을 그대로 나열한 절이 붙는다")
        fun `빠진 사실 절이 붙는다`() {
            val user =
                buildRepairPrompt(
                    ModelDraft("본문입니다."),
                    emptyList(),
                    listOf(FactIssue(FactKind.PHONE, "02-1234-5678"), FactIssue(FactKind.DATE, "9월 4일")),
                ).user

            assertThat(user).contains("[빠진 사실]")
            assertThat(user).contains("- 02-1234-5678")
            assertThat(user).contains("- 9월 4일")
        }

        @Test
        @DisplayName("빠진 사실이 없으면 그 절이 아예 없다 — 기존 출력과 같다")
        fun `빠진 사실이 없으면 절이 없다`() {
            val draft = ModelDraft("본문입니다.")
            val fixedIds = DocumentIdGenerator { "0123456789ab" }
            val withEmptyFacts = buildRepairPrompt(draft, emptyList(), emptyList(), fixedIds).user
            val withoutFactsArg = buildRepairPrompt(draft, emptyList(), documentIds = fixedIds).user

            assertThat(withEmptyFacts).doesNotContain("[빠진 사실]")
            assertThat(withEmptyFacts).isEqualTo(withoutFactsArg)
        }
    }

    @Nested
    @DisplayName("사전 컨텍스트 주입")
    inner class DictionaryContext {
        private val documentText = "금일 서류를 지참하세요."
        private val fixedIds = DocumentIdGenerator { "0123456789ab" }
        private val context = "[문서 사전]\n- 금일: 오늘"

        @Test
        @DisplayName("주지 않으면 기존 사용자 프롬프트와 한 글자도 다르지 않다")
        fun `null 이면 기존 출력이다`() {
            assertThat(buildUserPrompt(documentText, fixedIds, null)).isEqualTo(buildUserPrompt(documentText, fixedIds))
        }

        @Test
        @DisplayName("공백뿐인 컨텍스트는 없는 것으로 본다")
        fun `blank 는 null 과 같다`() {
            assertThat(buildUserPrompt(documentText, fixedIds, "   \n  "))
                .isEqualTo(buildUserPrompt(documentText, fixedIds))
        }

        @Test
        @DisplayName("컨텍스트는 문서 구분자보다 앞에, 빈 줄 하나를 두고 실린다")
        fun `문서보다 앞에 붙인다`() {
            val prompt = buildUserPrompt(documentText, fixedIds, context)

            assertThat(prompt).startsWith("$context\n\n<$DOCUMENT_TAG_NAME id=\"0123456789ab\">")
            assertThat(prompt).isEqualTo("$context\n\n" + buildUserPrompt(documentText, fixedIds))
        }

        @Test
        @DisplayName("파일에서 읽은 컨텍스트의 앞뒤 공백이 이음매를 흔들지 않는다")
        fun `앞뒤 공백은 이음매를 바꾸지 않는다`() {
            assertThat(buildUserPrompt(documentText, fixedIds, "\n$context\n\n"))
                .isEqualTo(buildUserPrompt(documentText, fixedIds, context))
        }
    }

    @Nested
    @DisplayName("구조 절(structureSection) 배치 — P0-4 S8-2")
    inner class StructureSectionPlacement {
        private val fixedIds = DocumentIdGenerator { "0123456789ab" }
        private val documentText = "구분\n금액\n비고"
        private val units = listOf("구분", "금액", "비고")
        private val structure = SourceStructure(List(3) { UnitKind.TABLE_CELL })

        @Test
        @DisplayName("주지 않으면 사용자 프롬프트가 기존과 한 글자도 다르지 않다 — B1")
        fun `null 이면 기존 출력이다`() {
            assertThat(buildUserPrompt(documentText, fixedIds, null, null))
                .isEqualTo(buildUserPrompt(documentText, fixedIds))
        }

        @Test
        @DisplayName("주지 않으면 보정 프롬프트도 기존과 한 글자도 다르지 않다 — B1")
        fun `보정 프롬프트도 null 이면 기존 출력이다`() {
            val draft = ModelDraft("변환문입니다.")
            assertThat(buildRepairPrompt(draft, emptyList(), documentIds = fixedIds, structureSection = null).user)
                .isEqualTo(buildRepairPrompt(draft, emptyList(), documentIds = fixedIds).user)
        }

        @Test
        @DisplayName("표 run 렌더링이 <문서> 구간 뒤, 안내 문구 앞에 붙는다 — B2")
        fun `표 run 이 문서 구간 뒤에 붙는다`() {
            val section = renderStructureSection(structure, units, fixedIds, maxRuns = 40)!!

            val prompt = buildUserPrompt(documentText, fixedIds, null, section)

            assertThat(prompt).contains(section)
            val closeTag = "</$DOCUMENT_TAG_NAME id=\"0123456789ab\">"
            assertThat(prompt.indexOf(closeTag)).isLessThan(prompt.indexOf(section))
            assertThat(prompt).endsWith("위 문서를 쉬운 글로 바꿔 주세요.")
            assertThat(prompt).contains("표:")
            assertThat(prompt).contains("「구분」")
        }

        @Test
        @DisplayName("목록 run 도 같은 자리에 실린다 — B2")
        fun `목록 run 도 문서 구간 뒤에 붙는다`() {
            val listUnits = listOf("① 첫째", "- 둘째")
            val listStructure = SourceStructure(List(2) { UnitKind.LIST_ITEM })
            val section = renderStructureSection(listStructure, listUnits, fixedIds, maxRuns = 40)!!

            val prompt = buildUserPrompt("① 첫째\n- 둘째", fixedIds, null, section)

            assertThat(prompt).contains("목록:")
            assertThat(prompt).contains("「① 첫째」")
        }

        @Test
        @DisplayName("보정 프롬프트도 <변환문> 구간 뒤 [고칠 곳] 앞에 같은 절을 싣는다 — B3")
        fun `보정 프롬프트도 같은 절을 싣는다`() {
            val section = renderStructureSection(structure, units, fixedIds, maxRuns = 40)!!
            val draft = ModelDraft("변환문입니다.")

            val user = buildRepairPrompt(draft, emptyList(), documentIds = fixedIds, structureSection = section).user

            assertThat(user).contains(section)
            val closeTag = "</$CONVERTED_TAG_NAME id=\"0123456789ab\">"
            assertThat(user.indexOf(closeTag)).isLessThan(user.indexOf(section))
            assertThat(user.indexOf(section)).isLessThan(user.indexOf("[고칠 곳]"))
        }

        @Test
        @DisplayName("run 수 상한을 넘으면 접힌 문장이 그대로 두 프롬프트 모두에 실린다 — B3")
        fun `상한 초과 접힘 문장도 그대로 실린다`() {
            val manyKinds = (1..10).flatMap { listOf(UnitKind.TABLE_CELL, UnitKind.BODY) }
            val manyUnits = manyKinds.indices.map { "줄$it" }
            val manyStructure = SourceStructure(manyKinds)
            val section = renderStructureSection(manyStructure, manyUnits, fixedIds, maxRuns = 5)!!

            val userPrompt = buildUserPrompt(documentText, fixedIds, null, section)
            val repairPrompt =
                buildRepairPrompt(ModelDraft("변환문"), emptyList(), documentIds = fixedIds, structureSection = section)
            val repairUser = repairPrompt.user

            assertThat(userPrompt).contains("표·목록이 많습니다")
            assertThat(repairUser).contains("표·목록이 많습니다")
        }
    }

    private companion object {
        val GLOSS_LINE = "   '금일' (뜻: ${DIFFICULT_WORD_REPLACEMENTS.getValue("금일")})"
    }
}
