package kr.easydoc.core.llm

import kr.easydoc.core.easyread.DocumentIdGenerator
import kr.easydoc.core.easyread.SentenceIssue
import kr.easydoc.core.easyread.StyleRuleKind
import kr.easydoc.core.privacy.ModelDraft
import kr.easydoc.core.privacy.maskText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/** [LlmPrompt] 가 마스킹 선행 불변식을 타입으로 지키는지 확인한다. */
class LlmPromptTest {
    private val fixedIds = DocumentIdGenerator { "0123456789ab" }

    @Test
    @DisplayName("LlmPrompt 를 만드는 통로는 마스킹을 거치는 것뿐이다")
    fun `생성자가 열려 있지 않다`() {
        val declared =
            LlmPrompt::class.java.declaredConstructors
                .filterNot { it.isSynthetic }

        assertThat(declared)
            .withFailMessage("LlmPrompt 생성자가 하나가 아니다 — 감싸기만 하는 통로가 생겼는지 확인하라")
            .hasSize(1)
        assertThat(Modifier.isPrivate(declared.single().modifiers))
            .withFailMessage(
                "LlmPrompt 생성자가 private 이 아니다. 열리는 순간 마스킹을 거치지 않은 문자열로 " +
                    "프롬프트를 만들 수 있게 되고, CLAUDE.md 아키텍처 규칙 2(마스킹 선행)가 주석으로 돌아간다.",
            ).isTrue()
    }

    @Test
    @DisplayName("변환 프롬프트에는 마스킹된 본문만 실린다")
    fun `주민등록번호가 자리표시자로 바뀐 채로 실린다`() {
        val result = maskText("신청자 900101-1234567 님께 안내드립니다.")

        val prompt = LlmPrompt.forConversion(result.maskedText, fixedIds)

        assertThat(prompt.user).contains("[[주민등록번호1]]")
        assertThat(prompt.user)
            .withFailMessage("마스킹 전 원문이 프롬프트에 남았다 — LLM 으로 개인정보가 그대로 나간다")
            .doesNotContain("900101-1234567")
        assertThat(prompt.system).doesNotContain("900101-1234567")
    }

    @Test
    @DisplayName("변환 프롬프트는 Prompts.kt 의 생성 결과를 그대로 담는다")
    fun `구분자와 지시문이 들어 있다`() {
        val prompt = LlmPrompt.forConversion(maskText("행정 안내문 본문입니다.").maskedText, fixedIds)

        assertThat(prompt.user).contains("<문서 id=\"0123456789ab\">")
        assertThat(prompt.user).contains("</문서 id=\"0123456789ab\">")
        assertThat(prompt.system).contains("[변환 규칙]")
        assertThat(prompt.system).contains("[개인정보 표시]")
    }

    @Test
    @DisplayName("사전 컨텍스트는 user 프롬프트로만 내려가고 system 은 그대로다")
    fun `사전 컨텍스트를 전달한다`() {
        val masked = maskText("금일 서류를 지참하세요.").maskedText
        val context = "[문서 사전]\n- 금일: 오늘"

        val injected = LlmPrompt.forConversion(masked, fixedIds, context)
        val plain = LlmPrompt.forConversion(masked, fixedIds)

        assertThat(injected.user).startsWith("$context\n\n<문서 id=\"0123456789ab\">")
        assertThat(plain.user).doesNotContain(context)
        assertThat(injected.system).isEqualTo(plain.system)
    }

    @Test
    @DisplayName("보정 프롬프트는 1차 변환문과 지적 목록을 담는다")
    fun `보정 프롬프트를 만든다`() {
        val issue =
            SentenceIssue(
                sentence = "신청을 접수합니다.",
                kind = StyleRuleKind.DIFFICULT_WORD,
                reason = "어려운 낱말: 접수",
                word = "접수",
            )

        val prompt = LlmPrompt.forRepair(ModelDraft("신청을 접수합니다."), listOf(issue), fixedIds)

        assertThat(prompt.user).contains("<변환문 id=\"0123456789ab\">")
        assertThat(prompt.user).contains("[고칠 곳]")
        assertThat(prompt.user).contains("신청을 접수합니다.")
        assertThat(prompt.system).contains("[고치는 방법]")
    }

    @Test
    @DisplayName("toString 에 본문이 실리지 않는다")
    fun `toString 은 길이만 남긴다`() {
        val prompt = LlmPrompt.forConversion(maskText("대외비 문서 본문 900101-1234567").maskedText, fixedIds)

        val rendered = prompt.toString()

        assertThat(rendered).doesNotContain("대외비")
        assertThat(rendered).doesNotContain("900101-1234567")
        assertThat(rendered).doesNotContain("[[주민등록번호1]]")
        assertThat(rendered).contains("${prompt.user.length}자")
    }
}
