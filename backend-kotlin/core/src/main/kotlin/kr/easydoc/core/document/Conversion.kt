package kr.easydoc.core.document

import java.time.Instant
import java.util.UUID

/** 변환 한 건의 **비밀 아닌 부분** — 이 커밋이 실제로 쓰는 만큼. */
class Conversion(
    val id: UUID,
    val documentId: UUID,
    val status: ConversionStatus,
    val failureCode: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** 로그 허용목록 그대로 — 식별자·상태·실패 코드(계획 §4.4 가 명시적으로 허용한 값). */
    override fun toString(): String = "Conversion($id, ${status.wireName}, failure=$failureCode)"
}
