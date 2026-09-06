package kr.easydoc.application.document

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.segment.SourceStructure

/** 업로드 파일에서 본문 텍스트를 뽑는 포트. */
fun interface DocumentTextExtractor {
    /** [filename] 의 확장자로 형식을 가려 [bytes] 에서 본문을 뽑는다. */
    fun extract(
        filename: String?,
        bytes: ByteArray,
    ): ExtractedDocument
}

/**
 * 추출 결과 — 가려낸 형식과 정규화된 본문, 그리고 그 줄마다 하나씩 붙은 원본 단위 종류
 * (표·목록 구조 힌트 계획 §1.2). [structure] 의 불변식(`kinds.size == splitUnits(text).size`)은
 * 추출기가 지고, 어겨도 이 타입은 검증하지 않는다 — 저장 직전(`DocumentService.store`)이
 * 정규화된 최종 본문을 기준으로 다시 검증해 어긋나면 전부 BODY 로 접는다.
 */
class ExtractedDocument(
    val format: SourceFormat,
    val text: String,
    val structure: SourceStructure,
) {
    /** 형식과 길이만 남긴다. 본문은 나가지 않는다. */
    override fun toString(): String = "ExtractedDocument(${format.wireName}, ${text.length}자)"
}
