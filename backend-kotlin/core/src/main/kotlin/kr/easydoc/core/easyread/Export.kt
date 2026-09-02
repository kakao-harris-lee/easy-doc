package kr.easydoc.core.easyread

import kr.easydoc.core.document.SourceFormat
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
    ;

    companion object {
        /** 쿼리 파라미터·계약 enum 값으로 형식을 읽는다. 목록에 없으면 `null` — 422 는 호출 쪽이 낸다. */
        fun ofWireName(value: String): ExportFormat? = entries.firstOrNull { it.extension == value }

        /**
         * 원본 형식이 정하는 내보내기 형식 — **들어온 형식 그대로 나간다**(`DESIGN.md` §6.5).
         *
         * `PDF` 만 `null` 이다. **대체 형식으로 접지 않는다** — §6.5 가 "PDF 다운로드 기능이
         * 준비되기 전까지 다른 형식으로의 우회 다운로드를 기본 행동으로 제공하지 않는다"고
         * 정했고, 여기서 `TXT` 를 돌려주면 계약이 그 우회를 **권하는** 것이 된다.
         * `PDF` 를 [ExportFormat] 에 더해 이 `null` 을 없애려 들지 마라 — 렌더러가 없다.
         *
         * `when` 을 **전수로** 적는다. [SourceFormat] 에 값이 늘면 여기가 컴파일 에러가 되고,
         * 그것이 새 형식의 내보내기 판정을 빠뜨리지 않게 하는 장치다.
         */
        fun ofSource(source: SourceFormat): ExportFormat? =
            when (source) {
                SourceFormat.TEXT -> TXT

                SourceFormat.DOCX -> DOCX

                SourceFormat.HWPX -> HWPX

                SourceFormat.PDF -> null

                // 평문 업로드에도 붙여넣기와 같은 렌더러를 쓴다 — 반영할 원본 구조가 없으므로
                // "그대로 나간다"의 가장 단순한 형태다(`PackagedOriginalReflector` 가 반영을
                // 맡지 않는 이유와 같다).
                SourceFormat.TXT -> TXT
            }
    }
}

/** 파일명에서 걷어낼 문자. */
private val FORBIDDEN_IN_FILENAME =
    Regex("""[\u0000-\u001F\u007F-\u009F"\\/:*?<>|]""")

/**
 * 제목이 통째로 지워졌을 때 쓸 이름.
 *
 * 여기에는 [EASY_READ_SUFFIX] 를 덧붙이지 않는다 — 이 이름은 **원본 제목에서 나온 것이
 * 아니라서** 구분할 원본 파일명 자체가 없고, 이름이 이미 「쉬운 글」이라 붙이면
 * `쉬운 글-쉬운글` 이 된다.
 */
private const val FALLBACK_NAME = "쉬운 글"

/**
 * 원본 파일과 구분하려고 제목 뒤에 붙이는 표식 (`DESIGN.md` §6.5 — 예: `청년월세안내-쉬운글.docx`).
 *
 * 원본 서식을 유지해 내보내면 결과 파일이 원본과 **같은 형식·비슷한 이름**이라, 표식이
 * 없으면 내려받기 폴더에서 둘을 가릴 수 없다. 표식은 `filename*` 쪽에만 실린다 — ASCII
 * 대체 이름([ASCII_FALLBACK_STEM])은 계약이 `easy-read.<ext>` 로 고정한 값이라 그대로 둔다.
 */
private const val EASY_READ_SUFFIX = "-쉬운글"

/**
 * 확장자를 뺀 파일명 길이 상한. **[EASY_READ_SUFFIX] 를 포함한 길이다** — 상한을 제목 몫으로만
 * 읽으면 표식만큼 이름이 길어져, 한글 제목이 상한에 닿았을 때 UTF-8 바이트 예산을 넘는다.
 */
private const val MAX_FILENAME_STEM = 80

/** `filename*` 을 모르는 클라이언트를 위한 ASCII 대체 이름. */
private const val ASCII_FALLBACK_STEM = "easy-read"

/** 앞뒤에서 깎아 낼 문자 — 점과 공백. */
private const val TRIMMED_EDGE_CHARS = ". "

/** `Byte` 의 부호를 떼고 0..255 로 읽기 위한 마스크. JVM `Byte` 는 부호 있는 8비트다. */
private const val BYTE_MASK = 0xFF

/**
 * 문서 제목으로 내려받을 파일명을 만든다 — 제목 뒤에 [EASY_READ_SUFFIX] 가 붙는다.
 *
 * 자르기는 표식을 붙이기 **전**에 하고 그 몫을 미리 뺀다. 붙인 뒤에 자르면 표식이 잘려
 * `…-쉬운` 같은 이름이 나가고, 자르지 않으면 상한이 표식만큼 늘어난다.
 */
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
            .takeCodePoints(MAX_FILENAME_STEM - EASY_READ_SUFFIX.length)
            .trim { it in TRIMMED_EDGE_CHARS }
    val named = if (stem.isEmpty()) FALLBACK_NAME else stem + EASY_READ_SUFFIX
    return "$named.${format.extension}"
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

/** 제목·형식·본문 바이트로 파일 한 건을 만든다. 파일명 정제는 형식과 무관하게 같다. */
fun exportFileOf(
    title: String,
    format: ExportFormat,
    content: ByteArray,
): ExportFile =
    ExportFile(
        filename = exportFilename(stripControlChars(title), format),
        mediaType = format.mediaType,
        content = content,
    )

/** 본문을 TXT 바이트로 만든다. */
fun renderTxt(
    title: String,
    body: String,
): ExportFile = exportFileOf(title, ExportFormat.TXT, stripControlChars(body).toByteArray(StandardCharsets.UTF_8))

/** 앞에서 [limit] **코드포인트**만 남긴다. */
private fun String.takeCodePoints(limit: Int): String {
    if (codePointCount(0, length) <= limit) return this
    return substring(0, offsetByCodePoints(0, limit))
}

/**
 * 내보낼 본문을 **원본 단위에 짝지을 문단**으로 나눈다.
 *
 * 빈 줄을 버리는 것이 추출과 대칭이다 — 추출기가 빈 블록을 버렸으므로(infrastructure
 * `ingest/ExtractedTextBuilder`) 원본 단위 쪽에는 빈 줄에 대응하는 자리가 애초에 없다.
 * 여기서 빈 줄을 세면 「원본보다 문단이 많다」가 사실이 아닌 채로 서고, 그 차이만큼 빈
 * 문단이 문서 끝에 덧붙는다.
 *
 * **판정과 실제 반영이 이 함수 하나를 함께 쓴다** — `reflectedPreservation` 이 미리 말한
 * 개수와 내보내기가 실제로 쓴 개수가 갈리면 응답이 거짓이 된다.
 *
 * 새 문서를 만드는 갈래(`infrastructure/export/exportParagraphs`)와 다른 함수인 것은
 * 의도적이다: 그쪽은 짝지을 원본이 없어 빈 줄도 그대로 문단이 된다.
 */
fun exportContentLines(body: String): List<String> =
    stripControlChars(body).split("\r\n", "\n").filter { it.isNotBlank() }
