package kr.easydoc.core.document

import kr.easydoc.core.privacy.MaskCategory
import kr.easydoc.core.security.Secret
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

/** 복호화된 마스킹 항목 한 건 — 검수 화면이 보여 주는 「무엇을 무엇으로 가렸는가」. */
data class MaskedItemView(
    val category: MaskCategory,
    val placeholder: String,
    val original: Secret,
)
