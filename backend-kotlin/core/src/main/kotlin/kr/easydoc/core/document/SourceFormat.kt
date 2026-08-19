package kr.easydoc.core.document

import kr.easydoc.core.exceptions.StorageException

/**
 * 문서가 어디서 왔는가 — 붙여넣기(`text`)이거나 업로드한 파일의 소문자 확장자다.
 *
 * 원본: `app/services/documents.py` 의 `TEXT_SOURCE_FORMAT` 상수 + `_source_format`.
 * Python 은 이것을 자유 문자열로 다뤘고, 계약(`DocumentListItem.source_format`)이
 * `[text, docx, pdf, hwpx]` 로 **닫아** 두었다. 값 집합이 이미 닫혀 있으므로 enum 으로 든다.
 *
 * ## `wireName` 이 따로 있는 이유
 *
 * 이 값은 **DB 컬럼(`documents.source_format`)에도 계약 응답에도 그대로 나간다.** Kotlin
 * enum 이름은 대문자 관례라 `DOCX` 인데, 저장·응답은 `docx` 여야 한다. `name.lowercase()`
 * 로 유도하면 enum 이름을 바꾸는 순간 옛 행이 안 읽히는데 그 사실이 아무 데도 안 적힌다 —
 * 그래서 갈래를 만들어 두고, 이 문자열이 **스키마만큼 무겁다**는 것을 여기 적는다.
 *
 * ## 지원 형식을 늘릴 때
 *
 * 계약이 *"이 enum 과 `x-input-limits.supported_upload_formats` 를 같은 변경 단위에서
 * 함께 늘린다"* 고 적었다. 이 파일과 계약이 갈리면 `DocumentExtractors` 의 계약 대조
 * 테스트가 빨개진다 — 집합이 늘어도 검사가 안 늘면 새 형식이 **검사 자체를 받지 않는다**.
 */
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

    /**
     * 이 형식이 zip 컨테이너인가.
     *
     * 압축 폭탄 방어를 **디스패치 한 곳**에 모으기 위한 값이다(원본 `_Format.is_zip` 과 같은
     * 목적). 새 zip 계열 형식을 더하면 방어가 자동으로 적용된다 — 형식별 파서마다 방어를
     * 따로 붙이면 하나를 빠뜨리는 날 그 형식만 무방비가 된다.
     */
    val isZipContainer: Boolean get() = this == DOCX || this == HWPX

    companion object {
        /**
         * 업로드로 들어올 수 있는 형식 — [TEXT] 를 뺀 전부.
         *
         * 계약 `x-input-limits.supported_upload_formats` 와 같은 집합이며 **순서까지 같다**
         * (`지원 형식: docx, pdf, hwpx` 안내 문구가 이 순서를 그대로 쓴다).
         */
        val UPLOAD_FORMATS: List<SourceFormat> = listOf(DOCX, PDF, HWPX)

        /**
         * 저장된 컬럼 값(`documents.source_format`)을 형식으로 되읽는다.
         *
         * 모르는 값은 [kr.easydoc.core.exceptions.StorageException] 이다 — 사용자 입력
         * 문제가 아니라 **DB 에 우리가 모르는 형식이 들어 있다**는 뜻이고, 조용히 [TEXT]
         * 같은 값으로 접으면 목록이 거짓을 보여 준다. [ConversionStatus.ofWireName] 과
         * 같은 규약이고 문구도 같다.
         */
        fun ofWireName(value: String): SourceFormat =
            entries.firstOrNull { it.wireName == value }
                ?: throw StorageException(ConversionStatus.UNKNOWN_STATUS_MESSAGE)

        /**
         * 파일 이름의 확장자로 업로드 형식을 가린다. 대소문자를 가리지 않는다.
         *
         * **내용 스니핑을 하지 않는다**(원본 `extract_text` 와 같다). 확장자를 속인 파일은
         * 파서가 손상 파일로 거절하거나, OLE2 라면 추출기가 전용 안내를 낸다.
         *
         * 파일 이름은 **여기서만 쓰이고 버려진다** — 저장하지도 로그에 남기지도 않는다.
         * 파일 이름 자체가 개인정보일 수 있다(예: `홍길동_주민등록등본.pdf`).
         *
         * 널·빈 문자열·경로가 섞인 값·`..` 을 전부 여기서 흡수한다. 브라우저에 따라
         * `getOriginalFilename()` 이 경로째 보내오고, 정제는 프레임워크가 하지 않는다.
         */
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
