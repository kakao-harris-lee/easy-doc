package kr.easydoc.core.llm

import kr.easydoc.core.easyread.DICTIONARY_CONTEXT_GUARD
import kr.easydoc.core.easyread.DICTIONARY_CONTEXT_TAG_NAME
import kr.easydoc.core.easyread.DocumentIdGenerator
import kr.easydoc.core.easyread.ExplanationPromptVersion
import kr.easydoc.core.easyread.FactIssue
import kr.easydoc.core.easyread.FactKind
import kr.easydoc.core.easyread.PRIOR_BODY_CONTEXT_GUARD
import kr.easydoc.core.easyread.PRIOR_BODY_CONTEXT_TAG_NAME
import kr.easydoc.core.easyread.SentenceIssue
import kr.easydoc.core.easyread.StyleRuleKind
import kr.easydoc.core.privacy.ModelDraft
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/** [LlmPrompt] 가 생성 통로를 하나로 묶는지, `Prompts.kt` 산출물을 그대로 담는지 확인한다. */
class LlmPromptTest {
    private val fixedIds = DocumentIdGenerator { "0123456789ab" }

    /** 자료 태그에서 난수 구분자만 뽑는다 — 값 자체를 단언할 수 없으니 자리와 일관성만 잰다. */
    private val delimiterPattern = Regex("id=\"([0-9a-f-]{36})\"")

    @Test
    fun `변환은 고학년 독해 수준과 문맥 보존을 함께 지시한다`() {
        val prompt = LlmPrompt.forConversion("기한 내 보완하지 않으면 신청이 취소될 수 있습니다.")
        assertThat(prompt.system).contains("초등학교 5~6학년", "문장 사이", "취소될 수 있습니다")
        assertThat(prompt.system).doesNotContain("이 규칙에는 예외가 없습니다", "그 자리에서 문장을 끝내고")
        assertThat(prompt.system).doesNotContain("신청이 취소됩니다", "숫자 개수가 같아야")
    }

    @Test
    fun `judge는 사실뿐 아니라 독해 수준과 문맥을 평가한다`() {
        val prompt = LlmPrompt.forJudge("원문", "짧지만 이해하기 어려운 변환문", emptyList())
        assertThat(prompt.system).contains("초등학교 5~6학년", "문맥", "조건", "뜻을 설명")
    }

    @Test
    fun `judge는 전체 작성 수준과 국소적인 표현 개선을 구분한다`() {
        val prompt = LlmPrompt.forJudge("원문", "변환문", emptyList())
        assertThat(prompt.system).contains("문서 전반", "국소적인", "의미 오류")
        assertThat(prompt.system).doesNotContain("하나라도 어기면", "모든 단어")
    }

    @Test
    fun `보정에서도 공식 이름 보존과 이해를 돕는 설명을 지시한다`() {
        val prompt = LlmPrompt.forRepair(ModelDraft("초안"), emptyList(), sourceText = "원문")
        assertThat(prompt.system).contains("공식 이름", "원문의 오류", "역할", "서류명")
        assertThat(prompt.system).contains("일상적인 말", "처음 설명한 뜻을 반복")
    }

    @Test
    @DisplayName("LlmPrompt 를 만드는 통로가 하나뿐이다")
    fun `생성자가 열려 있지 않다`() {
        val declared =
            LlmPrompt::class.java.declaredConstructors
                .filterNot { it.isSynthetic }

        assertThat(declared)
            .withFailMessage("LlmPrompt 생성자가 하나가 아니다 — 감싸기만 하는 통로가 생겼는지 확인하라")
            .hasSize(1)
        assertThat(Modifier.isPrivate(declared.single().modifiers))
            .withFailMessage("LlmPrompt 생성자가 private 이 아니다.")
            .isTrue()
    }

    @Test
    @DisplayName("변환 프롬프트는 Prompts.kt 의 생성 결과를 그대로 담는다")
    fun `구분자와 지시문이 들어 있다`() {
        val prompt = LlmPrompt.forConversion("행정 안내문 본문입니다.", fixedIds)

        assertThat(prompt.user).contains("<문서 id=\"0123456789ab\">")
        assertThat(prompt.user).contains("</문서 id=\"0123456789ab\">")
        assertThat(prompt.system).contains("[변환 규칙]")
    }

    @Test
    @DisplayName("사전 컨텍스트는 user 프롬프트로만 내려가고 system 은 그대로다")
    fun `사전 컨텍스트를 전달한다`() {
        val documentText = "금일 서류를 지참하세요."
        val context = "[문서 사전]\n- 금일: 오늘"

        val injected = LlmPrompt.forConversion(documentText, fixedIds, context)
        val plain = LlmPrompt.forConversion(documentText, fixedIds)

        assertThat(injected.user).startsWith("$context\n\n<문서 id=\"0123456789ab\">")
        assertThat(plain.user).doesNotContain(context)
        assertThat(injected.system).isEqualTo(plain.system)
    }

    @Test
    @DisplayName("R3 사전 컨텍스트는 검수 표식을 보존하면서 별도 자료로 감싼다")
    fun `R3 사전 컨텍스트를 안전하게 전달한다`() {
        val context = "### 공식 이름 참고\n- 법 이름\n  설명(검수된 정의): 검수된 뜻"
        val prompt =
            LlmPrompt.forConversion(
                "법 이름을 확인하세요.",
                fixedIds,
                context,
                explanationVersion = ExplanationPromptVersion.R3,
            )

        assertThat(prompt.user).contains(DICTIONARY_CONTEXT_TAG_NAME, context, "설명(검수된 정의)")
        assertThat(prompt.system).contains(DICTIONARY_CONTEXT_GUARD)
    }

    @Test
    @DisplayName("재변환 앞선 문맥은 변환·보정 두 프롬프트에만 명시적으로 전달한다")
    fun `재변환 문맥을 전달한다`() {
        val prior = "앞서 국민기초생활 보장법(생활이 어려운 사람을 돕는 법)을 설명했습니다."
        val conversion =
            LlmPrompt.forConversion(
                "국민기초생활 보장법을 확인하세요.",
                fixedIds,
                explanationVersion = ExplanationPromptVersion.R3,
                priorBodyContext = prior,
            )
        val repair =
            LlmPrompt.forRepair(
                ModelDraft("국민기초생활 보장법을 확인하세요."),
                emptyList(),
                documentIds = fixedIds,
                sourceText = "국민기초생활 보장법을 확인하세요.",
                explanationVersion = ExplanationPromptVersion.R3,
                priorBodyContext = prior,
            )

        assertThat(conversion.user).contains(prior, PRIOR_BODY_CONTEXT_TAG_NAME)
        assertThat(repair.user).contains(prior, PRIOR_BODY_CONTEXT_TAG_NAME)
        assertThat(conversion.system).contains(PRIOR_BODY_CONTEXT_GUARD)
        assertThat(repair.system).contains(PRIOR_BODY_CONTEXT_GUARD)
        assertThat(conversion.system).contains("검수된 사전 정의").doesNotContain("문서 전체에서 첫 등장인지 알 수 없으므로")
        assertThat(repair.system).contains("검수된 사전 정의").doesNotContain("문서 전체에서 첫 등장인지 알 수 없으므로")
    }

    @Test
    @DisplayName("보정 프롬프트는 1차 변환문과 지적 목록을 담는다")
    fun `보정 프롬프트를 만든다`() {
        val issue =
            SentenceIssue(
                sentence = "신청을 열람합니다.",
                kind = StyleRuleKind.DIFFICULT_WORD,
                reason = "어려운 낱말: 열람",
                word = "열람",
            )

        val prompt =
            LlmPrompt.forRepair(
                ModelDraft("신청을 열람합니다."),
                listOf(issue),
                documentIds = fixedIds,
                sourceText = "원문",
            )

        assertThat(prompt.user).contains("<변환문 id=\"0123456789ab\">")
        assertThat(prompt.user).contains("[고칠 곳]")
        assertThat(prompt.user).contains("신청을 열람합니다.")
        assertThat(prompt.system).contains("[고치는 방법]")
    }

    @Test
    @DisplayName("빠진 사실이 있으면 보정 프롬프트에 값이 그대로 실린다")
    fun `빠진 사실을 보정 프롬프트에 싣는다`() {
        val fact = FactIssue(FactKind.PHONE, "02-1234-5678")

        val prompt =
            LlmPrompt.forRepair(
                ModelDraft("문의하세요."),
                emptyList(),
                listOf(fact),
                fixedIds,
                sourceText = "원문",
            )

        assertThat(prompt.user).contains("[빠진 사실]")
        assertThat(prompt.user).contains("02-1234-5678")
    }

    @Test
    @DisplayName("judge 프롬프트는 no 일 때 둘째 줄에 사유를 적으라고 지시한다")
    fun `judge 시스템 프롬프트에 사유 지시가 있다`() {
        val prompt = LlmPrompt.forJudge("원문", "변환문", emptyList())

        assertThat(prompt.system).contains("no 이면 둘째 줄에")
        assertThat(prompt.system).contains("첫 줄에 yes")
    }

    @Test
    fun `행동 안내 프롬프트는 원문과 저장 본문을 별도 자료로 감싸고 근거 좌표를 알려준다`() {
        val prompt = LlmPrompt.forActionGuide("신청 기한은 3월입니다.\n서류를 내세요.", "3월까지 서류를 내세요.")

        assertThat(prompt.system).contains("JSON 객체 하나", "source_unit_indexes", "데이터", "needs_review")
        assertThat(prompt.user).contains("[0] 신청 기한은 3월입니다.", "[1] 서류를 내세요.")
        assertThat(prompt.user).contains("<source id=", "<saved_body id=", "3월까지 서류를 내세요.")
        assertThat(prompt.user).doesNotContain("<source id=\"0123456789ab\">")
        assertThat(prompt.toString()).doesNotContain("신청 기한", "3월까지")
    }

    @Test
    fun `행동 안내 프롬프트는 자격과 범위의 포함 제외 조건을 보존하도록 지시한다`() {
        val prompt =
            LlmPrompt.forActionGuide(
                "지원 대상과 지원 범위가 적힌 원문입니다.",
                "지원 범위를 정리한 본문입니다.",
            )

        assertThat(prompt.system)
            .contains(
                "지원 대상·자격 조건",
                "지원·사용 범위",
                "포함·제외 대상",
                "text 또는 cautions",
                "실제 적용되는 자격·한도·필수 행동·예외 조건",
                "원문에 없는 조건이나 혜택을 만들지 마라",
                "괄호·각주·별표·뒤따르는 문장",
                "수치의 단위·분모·대상 표현",
                "원문 인용과 같은 표기",
                "exceptions가 available이고 steps도 available",
                "관련 steps 항목의 cautions",
                "steps가 not_in_source",
                "모든 사실을 뒷받침하는 짧고 정확한 quote",
                "같은 긴 문단을 반복 인용하지 마라",
                "일반 참고용 예산 항목·예시·목록",
                "개별 혜택으로 하나씩 나열하지 말고",
                "참고 역할만 한 번 요약",
                "documents의 파일명은 원문 표기를 정확히 유지",
                "파일별 text 또는 줄바꿈",
                "파일 목록만으로 제출 대상·필수 제출이라고 단정하지 마라",
                "여섯 kind를 각각 정확히 한 번",
                "전체 text는 4,000 코드 포인트 이하다",
            )
        assertThat(prompt.system).doesNotContain("비금여", "비급여", "088")
    }

    @Test
    fun `그림 제안 프롬프트는 원문과 저장 본문 양쪽에 줄 번호를 붙인다`() {
        val prompt =
            LlmPrompt.forIllustrationSuggestions(
                "신청서를 준비합니다.\n주민센터에 제출합니다.",
                "먼저 신청서를 준비해요.\n그다음 주민센터에 내요.",
            )

        assertThat(prompt.user).contains("[0] 신청서를 준비합니다.", "[1] 주민센터에 제출합니다.")
        assertThat(prompt.user).contains("[0] 먼저 신청서를 준비해요.", "[1] 그다음 주민센터에 내요.")
        assertThat(prompt.user).contains("<source id=", "<saved_body id=")
        assertThat(prompt.user).doesNotContain("<source id=\"0123456789ab\">")
        assertThat(prompt.toString()).doesNotContain("신청서", "주민센터")
    }

    @Test
    @DisplayName("구분자는 호출마다 달라지고 원문·저장 본문 양쪽 태그가 같은 값을 쓴다")
    fun `그림 제안 프롬프트의 구분자는 매번 새로 뽑는다`() {
        val first = LlmPrompt.forIllustrationSuggestions("원문입니다.", "본문입니다.")
        val second = LlmPrompt.forIllustrationSuggestions("원문입니다.", "본문입니다.")

        val firstIds = delimiterPattern.findAll(first.user).map { it.groupValues[1] }.toList()
        assertThat(firstIds).hasSize(4)
        assertThat(firstIds.distinct()).hasSize(1)
        assertThat(delimiterPattern.findAll(second.user).map { it.groupValues[1] }.toSet())
            .doesNotContainAnyElementsOf(firstIds)
    }

    @Test
    fun `그림 제안 시스템 프롬프트는 제안할 문맥과 제안하지 않을 문맥을 모두 지시한다`() {
        val prompt = LlmPrompt.forIllustrationSuggestions("원문입니다.", "본문입니다.")

        assertThat(prompt.system)
            .contains(
                "JSON 객체 하나",
                "schema_version=1",
                "suggestions",
                "procedure",
                "comparison",
                "relationship",
                "source_unit_indexes",
                "body_range",
                "preserved_facts",
                "alt_text_draft",
                "빈 배열",
                "낱말마다 아이콘",
                "장식",
                "연락처",
                "오해",
                "원문에 없는 행동·장소·기간·조건",
                "어린이처럼",
                "그림 속 글자",
                "정확한 인용",
                "데이터",
            )
        assertThat(prompt.system).doesNotContain("suggestion_id", "analysis_version")
    }

    @Test
    @DisplayName("toString 에 본문이 실리지 않는다")
    fun `toString 은 길이만 남긴다`() {
        val prompt = LlmPrompt.forConversion("대외비 문서 본문입니다.", fixedIds)

        val rendered = prompt.toString()

        assertThat(rendered).doesNotContain("대외비")
        assertThat(rendered).contains("${prompt.user.length}자")
    }
}
