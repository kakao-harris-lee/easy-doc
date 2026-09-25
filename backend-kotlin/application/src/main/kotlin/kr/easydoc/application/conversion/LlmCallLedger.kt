package kr.easydoc.application.conversion

import kr.easydoc.core.document.ReadingLevel
import kr.easydoc.core.llm.LlmCallRecord
import java.time.Instant
import java.util.UUID

/**
 * LLM 호출 원장(`llm_calls`, V14) 한 행에 실릴 값 — [record] 가 호출 자체의 값이고
 * 나머지는 그 호출이 일어난 문맥이다.
 *
 * **쓰는 시점**에는 [conversionId]·[documentId]·[workspaceId]·[userId]·[documentCharCount]
 * 모두 실제 값이 있다 — 그 값을 모르는 호출은 애초에 일어날 수 없다.
 *
 * [conversionId]와 [documentId]는 쓰는 시점에는 존재하지만 참조 대상 삭제 뒤에도 원장에
 * 값을 보존하기 위해 FK를 두지 않는다. [workspaceId]는 DB에서 `ON DELETE SET NULL`,
 * [userId]는 `ON DELETE CASCADE` 정책을 따른다.
 *
 * [documentCharCount] 는 이 호출이 속한 **문서**의 `documents.char_count`(원문, 마스킹
 * 전, 등록 시 확정) 스냅샷이다 — [record]의 `charCount`(그 호출이 실제로 본 마스킹된
 * 입력 길이)와 다른 값이고, U2 집계가 문서 단위 문자 수·크레딧을 구하는 유일한 자리다
 * (`documents` 표를 더는 참조하지 않는다).
 */
data class LlmCallEntry(
    val conversionId: UUID?,
    val documentId: UUID?,
    val workspaceId: UUID,
    val userId: UUID,
    val record: LlmCallRecord,
    val calledAt: Instant,
    val documentCharCount: Int,
    val readingLevel: ReadingLevel? = null,
)

/**
 * LLM 호출 원장에 쓰는 포트. **완료·실패 결과 저장과 같은 트랜잭션**에서 부른다
 * (`ProcessConversionJob.finishSuccess`·`ReconvertUnitService.settle`) — 원장 쓰기 실패가
 * 곧 변환 실패다(계획 §6 리스크 2, 청구 근거 없는 완료를 만들지 않는다).
 *
 * `llm_calls` 표는 [kr.easydoc.core.crypto.EncryptedField] 가 아는 표 밖이다 — 담는 열이
 * 숫자·이름뿐이라 암호화 대상이 아니고, 그래서 `OwnershipPredicateGuardTest`·
 * `EnvelopeColumnWriteGuardTest`(둘 다 [kr.easydoc.core.crypto.EncryptedField] 가 아는 표만
 * 훑는다) 인구조사 대상도 아니다.
 */
fun interface LlmCallLedger {
    /** [entries] 가 비어 있으면 아무것도 쓰지 않는다 — 실패한 호출은 애초에 항목이 없다. */
    fun append(entries: List<LlmCallEntry>)
}
