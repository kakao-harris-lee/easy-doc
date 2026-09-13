package kr.easydoc.core.llm

import kr.easydoc.core.easyread.DocumentIdGenerator
import kr.easydoc.core.easyread.FactIssue
import kr.easydoc.core.easyread.FactKind
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
    @DisplayName("toString 에 본문이 실리지 않는다")
    fun `toString 은 길이만 남긴다`() {
        val prompt = LlmPrompt.forConversion("대외비 문서 본문입니다.", fixedIds)

        val rendered = prompt.toString()

        assertThat(rendered).doesNotContain("대외비")
        assertThat(rendered).contains("${prompt.user.length}자")
    }
}
