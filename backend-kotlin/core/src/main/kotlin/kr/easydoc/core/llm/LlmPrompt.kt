package kr.easydoc.core.llm

import kr.easydoc.core.easyread.DocumentIdGenerator
import kr.easydoc.core.easyread.SecureDocumentIds
import kr.easydoc.core.easyread.SentenceIssue
import kr.easydoc.core.easyread.buildRepairPrompt
import kr.easydoc.core.easyread.buildSystemPrompt
import kr.easydoc.core.easyread.buildUserPrompt
import kr.easydoc.core.privacy.MaskedText
import kr.easydoc.core.privacy.ModelDraft

/**
 * LLM 에 실제로 나가는 `(system, user)` 페이로드.
 *
 * ## 왜 String 두 개가 아니라 별도 타입인가
 *
 * `CLAUDE.md` 아키텍처 규칙 2(보안 불변식): 사용자 문서 텍스트는 마스킹을 통과한 뒤에만
 * LLM 으로 전달될 수 있다. [LlmProvider.complete] 가 `system: String, user: String` 을
 * 받으면 그 불변식은 주석으로 되돌아간다 — 아무 문자열이나 넘어가고, 마스킹을 건너뛰는
 * 새 경로는 **운영에서 처음 터진다**(그런 경로는 대개 테스트가 없는 경로다).
 *
 * ## 강제의 실제 범위 (선언과 도달을 일치시킨 기록)
 *
 * 생성자가 `private` 이라 이 클래스 안에서만 인스턴스를 만들 수 있고, 클래스가 여는
 * 통로는 [Companion.forConversion] 과 [Companion.forRepair] 둘뿐이다. 둘 다 임의
 * 문자열을 받지 않고 [MaskedText] / [ModelDraft] 를 받아 `Prompts.kt` 를 통과시킨다 —
 * 즉 **"감싸기만 하는 통로"가 존재하지 않는다.** `Masking.kt` 의 [MaskedText] 가 쓴
 * 것과 같은 수법이고, 같은 이유다.
 *
 * 강제가 미치지 **않는** 범위도 적어 둔다.
 * - **[forRepair] 는 [forConversion] 보다 약하다.** [ModelDraft] 는 생성자가 열려 있어
 *   `ModelDraft(원문)` 이 컴파일된다(`Prompts.kt::buildRepairPrompt` KDoc 에 이미 적힌
 *   한계). 보정 패스에 들어가는 것은 사용자 원문이 아니라 **LLM 이 낸 1차 변환문**이라
 *   [MaskedText] 를 요구할 수 없다 — 이미 자리표시자가 박힌 변환문을 다시 마스킹하면
 *   자리표시자가 탈출 표기로 망가진다. 남은 것은 호출자가 "이것은 모델 출력이다"라고
 *   의식적으로 선언하게 만드는 것뿐이다.
 * - **Kotlin 호출자에 한정된다.** 리플렉션·바이트코드 조작은 어떤 가시성으로도 막지 못한다.
 *
 * ## data class 가 아닌 이유
 *
 * [user] 에는 **사용자 문서 본문이 통째로** 들어 있다. `data class` 의 기본
 * `toString()` 은 모든 필드를 그대로 찍으므로, 로거 인자로 한 번 실리는 순간 문서 본문이
 * 로그 수집기로 나간다(CLAUDE.md 보안 규칙: 로깅은 문서 ID·길이·처리 상태까지만).
 * 그래서 일반 class 로 두고 [toString] 을 길이만 남기게 재정의한다.
 */
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
         * 원본: `app/services/conversion.py` 가 `build_system_prompt` + `build_user_prompt`
         * 를 잇는 자리. 두 호출을 여기로 모은 이유는 그 조합이 **불변식의 경계**이기
         * 때문이다 — 나뉘어 있으면 "시스템 프롬프트는 마스킹본으로, 사용자 프롬프트는
         * 원문으로" 같은 조합이 컴파일된다.
         *
         * @param maskedText 마스킹 파이프라인을 통과한 본문.
         * @param documentIds 구분자 id 생성기. 테스트만 고정 생성기를 넘긴다.
         */
        fun forConversion(
            maskedText: MaskedText,
            documentIds: DocumentIdGenerator = SecureDocumentIds,
        ): LlmPrompt =
            LlmPrompt(
                system = buildSystemPrompt(maskedText),
                user = buildUserPrompt(maskedText, documentIds),
            )

        /**
         * 보정(수리) 패스 프롬프트. 기계 검사가 잡아낸 위반만 표적으로 고치게 한다.
         *
         * 원본: `app/easyread/prompts.py::build_repair_prompt`.
         *
         * @param converted 후처리를 마친 1차 변환문(`easy_text`).
         * @param violations `checkStyle` 이 잡아낸 위반.
         * @param documentIds 구분자 id 생성기. 테스트만 고정 생성기를 넘긴다.
         */
        fun forRepair(
            converted: ModelDraft,
            violations: List<SentenceIssue>,
            documentIds: DocumentIdGenerator = SecureDocumentIds,
        ): LlmPrompt {
            val repair = buildRepairPrompt(converted, violations, documentIds)
            return LlmPrompt(system = repair.system, user = repair.user)
        }
    }
}
