package kr.easydoc.core.document

import kr.easydoc.core.exceptions.StorageException

// 계약·추출기 집합 검증: `UploadFormatContractTest`, `DocumentExtractorsTest`.

/** 문서가 어디서 왔는가 — 붙여넣기(`text`)이거나 업로드한 파일의 소문자 확장자다. */
enum class SourceFormat(val wireName: String) {
    /** 붙여넣기. 파일이 아니므로 추출기를 지나지 않는다. */
    TEXT("text"),

    /** OOXML 워드 문서. zip 컨테이너다. */
    DOCX("docx"),

    /** PDF. zip 이 아니다. */
    PDF("pdf"),

    /** OWPML(한글) 문서. zip 컨테이너다. */
    HWPX("hwpx"),

    ;

    /** 이 형식이 zip 컨테이너인가. */
    val isZipContainer: Boolean get() = this == DOCX || this == HWPX

    companion object {
        /** 업로드로 들어올 수 있는 형식 — [TEXT] 를 뺀 전부. */
        val UPLOAD_FORMATS: List<SourceFormat> = listOf(DOCX, PDF, HWPX)

        /** 저장된 컬럼 값(`documents.source_format`)을 형식으로 되읽는다. */
        fun ofWireName(value: String): SourceFormat =
            entries.firstOrNull { it.wireName == value }
                ?: throw StorageException(ConversionStatus.UNKNOWN_STATUS_MESSAGE)

        /** 파일 이름의 확장자로 업로드 형식을 가린다. 대소문자를 가리지 않는다. */
        fun ofUploadFilename(filename: String?): SourceFormat? {
            val leaf = filename.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            // `.hwpx` 처럼 이름 전체가 확장자인 경우를 형식으로 인정하지 않는다 —
            // `substringAfterLast` 는 구분자가 없으면 원문을 돌려주므로 점 위치를 직접 본다.
            val dot = leaf.lastIndexOf('.')
            if (dot <= 0) return null
            val extension = leaf.substring(dot + 1).lowercase()
            return UPLOAD_FORMATS.firstOrNull { it.wireName == extension }
        }
    }
}
