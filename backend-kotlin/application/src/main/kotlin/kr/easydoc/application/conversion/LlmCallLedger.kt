package kr.easydoc.application.conversion

import kr.easydoc.core.llm.LlmCallRecord
import java.time.Instant
import java.util.UUID

/**
 * LLM 호출 원장(`llm_calls`, V12) 한 행에 실릴 값 — [record] 가 호출 자체의 값이고
 * 나머지는 그 호출이 일어난 문맥이다.
 *
 * **쓰는 시점**에는 [conversionId]·[documentId]·[workspaceId]·[userId] 모두 실제 값이
 * 있다 — 그 값을 모르는 호출은 애초에 일어날 수 없다. [conversionId]·[documentId] 를
 * `nullable` 로 둔 것은 DB 열의 미래 상태(쓴 **뒤에** 그 대상이 지워지면 `SET NULL` 로
 * 참조만 끊고 행은 남는다)를 타입에 그대로 반영해서다 — 보존 파기가 청구 근거를 함께
 * 지우지 않는다(계획 §2 결정 1 「보존 결정의 정정」). **`workspace_id` 도 같은 이유로 DB
 * 열은 nullable 이다**(리뷰로 CASCADE 에서 SET NULL 로 2차 정정 — 워크스페이스 삭제가
 * 그 워크스페이스에 쌓인 청구 근거를 지우면 안 된다) — [workspaceId] 필드 자체는 쓰는
 * 시점의 값이 항상 있어 `UUID` non-null 로 남긴다. [userId] 만 `ON DELETE CASCADE` 다 —
 * 계정 자체가 없으면 청구 대상도 없다.
 */
data class LlmCallEntry(
    val conversionId: UUID?,
    val documentId: UUID?,
    val workspaceId: UUID,
    val userId: UUID,
    val record: LlmCallRecord,
    val calledAt: Instant,
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
