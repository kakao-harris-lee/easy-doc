package kr.easydoc.core.easyread

import kr.easydoc.core.text.stripControlChars
import java.nio.charset.StandardCharsets

// 내보내기 — **순수 문자열·바이트 로직만** 둔다.
//
// ## 여기 없는 것과 그 이유
//
// - **DOCX·HWPX 렌더링**: POI/ZIP 의존이라 `infrastructure` 몫이고 Phase 4다. 이 파일이
//   `core` 에 있는 조건은 "fixture 파일 하나와 순수 함수만으로 검증 가능한가"이고
//   (kotlin-spring-conventions §1), zip 컨테이너 조립은 그 조건을 만족하지 않는다.
// - **자리표시자 복원**: 이미 `privacy/Masking.kt::restoreForExport` 에 있다. 여기 다시
//   만들지 않는다 — 복원 규칙(정확히 1회일 때만·검수본 없으면 보류)은 마스킹 쪽 결정이고
//   두 벌로 두면 한쪽만 고쳐지는 날이 온다. 판단 근거는 `restoreForExport` KDoc에 있다.

/** 내보내기 형식. 값이 그대로 확장자다. */
enum class ExportFormat(
    val extension: String,
    val mediaType: String,
) {
    DOCX(
        "docx",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ),

    /**
     * charset 을 명시한다 — txt 는 **BOM 없이** UTF-8 로 내보내므로, 이 표시가 없으면
     * 브라우저가 로캘 기본 인코딩으로 열어 한글이 깨진다.
     */
    TXT("txt", "text/plain; charset=utf-8"),

    /**
     * hwpx 에는 IANA 등록 미디어 타입이 없다. 원본이 `application/hwp+zip` 을 고른 근거를
     * 그대로 따른다(한컴 개발자 포럼 공식 답변 등 세 곳 일치).
     */
    HWPX("hwpx", "application/hwp+zip"),
}

/** 파일명에서 걷어낼 문자. */
private val FORBIDDEN_IN_FILENAME =
    Regex("""[\u0000-\u001F\u007F-\u009F"\\/:*?<>|]""")

/** 제목이 통째로 지워졌을 때 쓸 이름. */
private const val FALLBACK_NAME = "쉬운 글"

/** 확장자를 뺀 파일명 길이 상한. */
private const val MAX_FILENAME_STEM = 80

/** `filename*` 을 모르는 클라이언트를 위한 ASCII 대체 이름. */
private const val ASCII_FALLBACK_STEM = "easy-read"

/** 앞뒤에서 깎아 낼 문자 — 점과 공백. */
private const val TRIMMED_EDGE_CHARS = ". "

/** `Byte` 의 부호를 떼고 0..255 로 읽기 위한 마스크. JVM `Byte` 는 부호 있는 8비트다. */
private const val BYTE_MASK = 0xFF

/** 문서 제목으로 내려받을 파일명을 만든다. */
fun exportFilename(
    title: String,
    format: ExportFormat,
): String {
    val cleaned =
        FORBIDDEN_IN_FILENAME
            .replace(title, " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    val stem =
        cleaned
            .trim { it in TRIMMED_EDGE_CHARS }
            .takeCodePoints(MAX_FILENAME_STEM)
            .trim { it in TRIMMED_EDGE_CHARS }
    return "${stem.ifEmpty { FALLBACK_NAME }}.${format.extension}"
}

/** RFC 5987 방식으로 파일명을 담은 `Content-Disposition` 헤더 값을 만든다. */
fun contentDisposition(filename: String): String {
    val suffix = filename.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    return "attachment; filename=\"$ASCII_FALLBACK_STEM$suffix\"; " +
        "filename*=UTF-8''${percentEncode(filename)}"
}

/** RFC 5987 `ext-value` 의 퍼센트 인코딩. */
private fun percentEncode(value: String): String =
    buildString {
        for (byte in value.toByteArray(StandardCharsets.UTF_8)) {
            val code = byte.toInt() and BYTE_MASK
            val char = code.toChar()
            if (char.isAsciiUnreserved()) append(char) else append("%%%02X".format(code))
        }
    }

/** `unreserved` 문자인가 (RFC 3986). `URLEncoder` 를 쓰지 않는 이유는 위 KDoc. */
private fun Char.isAsciiUnreserved(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in "_.-~"

/** 내보낼 파일 한 건. 전송에 필요한 것만 담는다 — HTTP 헤더 조립은 api 계층이 한다. */
class ExportFile(
    val filename: String,
    val mediaType: String,
    val content: ByteArray,
) {
    /** **파일명을 찍지 않는다** (게이트 25 R-10 — 이 재정의가 실제로 새고 있었다). */
    override fun toString(): String = "ExportFile(파일명 ${filename.length}자, $mediaType, ${content.size}바이트)"

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is ExportFile &&
                    filename == other.filename &&
                    mediaType == other.mediaType &&
                    content.contentEquals(other.content)
            )

    override fun hashCode(): Int = (filename.hashCode() * 31 + mediaType.hashCode()) * 31 + content.contentHashCode()
}

/** 본문을 TXT 바이트로 만든다. */
fun renderTxt(
    title: String,
    body: String,
): ExportFile =
    ExportFile(
        filename = exportFilename(stripControlChars(title), ExportFormat.TXT),
        mediaType = ExportFormat.TXT.mediaType,
        content = stripControlChars(body).toByteArray(StandardCharsets.UTF_8),
    )

/** 앞에서 [limit] **코드포인트**만 남긴다. */
private fun String.takeCodePoints(limit: Int): String {
    if (codePointCount(0, length) <= limit) return this
    return substring(0, offsetByCodePoints(0, limit))
}
