package kr.easydoc.application.conversion

import kr.easydoc.core.llm.LlmCallRecord
import java.time.Instant
import java.util.UUID

/**
 * LLM 호출 원장(`llm_calls`, V12) 한 행에 실릴 값 — [record] 가 호출 자체의 값이고
 * 나머지는 그 호출이 일어난 문맥이다.
 *
 * **쓰는 시점**에는 [conversionId]·[documentId]·[workspaceId]·[userId]·[documentCharCount]
 * 모두 실제 값이 있다 — 그 값을 모르는 호출은 애초에 일어날 수 없다.
 *
 * [conversionId]·[documentId] 를 `nullable` 로 둔 것은 필드 자체가 쓰는 시점에 없을 수
 * 있어서가 **아니다** — DB 열이 그 대상이 지워진 뒤에도 값을 그대로 들고 있는다는
 * 사실(2026-09-08 리뷰 정정, 아래)을 타입으로 드러내려면 이 값이 "쓸 때는 항상 있지만
 * 나중에 그 대상이 사라질 수 있는 참조"라는 성격을 갖고 있어야 하기 때문이다 — `UUID`
 * non-null 로 두면 그 성격이 감춰진다.
 *
 * **보존 결정의 정정(계획 §2 결정 1, 2026-09-08 리뷰로 3차 정정).** 청구 근거(원장)는
 * 참조 대상이 지워져도 값이 지워지면 안 된다는 원칙은 그대로이지만, 그 원칙을 지키는
 * 방법이 바뀌었다 — 이전 결정(`conversion_id`·`document_id`에 `ON DELETE SET NULL`)은
 * **원장의 존재 이유와 정면으로 어긋났다**: U2가 워크스페이스 사용량의 `documents`·
 * `characters`·`credits`를 `documents` 표가 아니라 이 원장에서 유도하도록 다시
 * 설계됐는데(2026-09-08 리뷰, `JdbcUsageReadRepository` KDoc), 문서가 보존 만료로
 * 지워지면 `document_id`가 `NULL`이 되어 그 문서가 낸 지난달 청구 근거(문자 수·
 * 크레딧)까지 함께 사라지기 때문이다. 그래서 `conversion_id`·`document_id`는 **FK
 * 자체를 두지 않는다** — 참조 대상이 지워져도 이 열의 값은 그대로 남는 감사 로그
 * (append-only ledger)로 다룬다. **`workspace_id`는 여전히 `ON DELETE SET NULL`이다**
 * — 워크스페이스 단위 사용량(`readWorkspaceUsage`)은 그 워크스페이스가 없어지면 조회
 * 대상 자체가 사라지므로 참조를 끊어도 청구 근거(사용자 단위 리포트, U3)가 없어지지
 * 않는다. [userId] 만 `ON DELETE CASCADE` 다 — 계정 자체가 없으면 청구 대상도 없다.
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
