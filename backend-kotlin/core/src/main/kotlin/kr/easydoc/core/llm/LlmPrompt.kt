package kr.easydoc.core.llm

import kr.easydoc.core.easyread.DocumentIdGenerator
import kr.easydoc.core.easyread.SecureDocumentIds
import kr.easydoc.core.easyread.SentenceIssue
import kr.easydoc.core.easyread.buildRepairPrompt
import kr.easydoc.core.easyread.buildSystemPrompt
import kr.easydoc.core.easyread.buildUserPrompt
import kr.easydoc.core.privacy.MaskedText
import kr.easydoc.core.privacy.ModelDraft
import kr.easydoc.core.quality.RequiredFact

/** LLM 에 실제로 나가는 `(system, user)` 페이로드. */
class LlmPrompt private constructor(
    /** 시스템 프롬프트. 스타일 규칙·어려운 말 사전·자리표시자 지시가 들어 있다. */
    val system: String,
    /** 사용자 프롬프트. 마스킹을 거친 본문이 난수 구분자 안에 들어 있다. */
    val user: String,
) {
    /** 길이만 남긴다. 본문·프롬프트 문구는 로그에 싣지 않는다. */
    override fun toString(): String = "LlmPrompt(system=${system.length}자, user=${user.length}자)"

    companion object {
        /**
         * 1차 변환 프롬프트. 마스킹을 거친 본문만 받는다.
         *
         * [dictionaryContext] 는 [buildUserPrompt] 로 그대로 내려간다 — 계약은 그쪽 KDoc 에 있다.
         * 시스템 프롬프트는 문서에 따라 달라지지 않으므로 건드리지 않는다.
         */
        fun forConversion(
            maskedText: MaskedText,
            documentIds: DocumentIdGenerator = SecureDocumentIds,
            dictionaryContext: String? = null,
        ): LlmPrompt =
            LlmPrompt(
                system = buildSystemPrompt(maskedText),
                user = buildUserPrompt(maskedText, documentIds, dictionaryContext),
            )

        /** 보정(수리) 패스 프롬프트. 기계 검사가 잡아낸 위반만 표적으로 고치게 한다. */
        fun forRepair(
            converted: ModelDraft,
            violations: List<SentenceIssue>,
            documentIds: DocumentIdGenerator = SecureDocumentIds,
        ): LlmPrompt {
            val repair = buildRepairPrompt(converted, violations, documentIds)
            return LlmPrompt(system = repair.system, user = repair.user)
        }

        /**
         * LLM-as-judge 프롬프트. 원문·변환문을 실어 사실 보존만 묻는다.
         * 이 객체의 [toString] 은 길이만 남기므로 본문이 로그에 실리지 않는다.
         */
        fun forJudge(
            source: String,
            converted: String,
            facts: List<RequiredFact>,
        ): LlmPrompt {
            val factLines = facts.joinToString("\n") { "- ${it.canonical}" }
            return LlmPrompt(
                system = JUDGE_SYSTEM,
                user = "필수 사실:\n$factLines\n\n원문:\n$source\n\n변환:\n$converted",
            )
        }

        private const val JUDGE_SYSTEM: String =
            "너는 쉬운 글 변환의 사실 보존을 채점한다. 필수 사실이 변환문에 남았으면 " +
                "첫 줄에 yes, 빠졌으면 no 만 답한다. 본문을 되풀이하지 않는다."
    }
}
