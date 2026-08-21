package kr.easydoc.application.document

import kr.easydoc.core.document.SourceFormat

/** 업로드 파일에서 본문 텍스트를 뽑는 포트. */
fun interface DocumentTextExtractor {
    /** [filename] 의 확장자로 형식을 가려 [bytes] 에서 본문을 뽑는다. */
    fun extract(
        filename: String?,
        bytes: ByteArray,
    ): ExtractedDocument
}

/** 추출 결과 — 가려낸 형식과 정규화된 본문. */
class ExtractedDocument(
    val format: SourceFormat,
    val text: String,
) {
    /** 형식과 길이만 남긴다. 본문은 나가지 않는다. */
    override fun toString(): String = "ExtractedDocument(${format.wireName}, ${text.length}자)"
}
