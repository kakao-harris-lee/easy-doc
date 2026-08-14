package kr.easydoc.core.easyread

import kr.easydoc.core.text.stripControlChars
import java.nio.charset.StandardCharsets

// 내보내기 — **순수 문자열·바이트 로직만** 둔다.
//
// 원본: app/easyread/export.py 의 export_filename · content_disposition · render_export(TXT).
//
// ## 여기 없는 것과 그 이유
//
// - **DOCX·HWPX 렌더링**: POI/ZIP 의존이라 `infrastructure` 몫이고 Phase 4다. 이 파일이
//   `core` 에 있는 조건은 "fixture 파일 하나와 순수 함수만으로 검증 가능한가"이고
//   (kotlin-spring-conventions §1), zip 컨테이너 조립은 그 조건을 만족하지 않는다.
// - **자리표시자 복원**: 이미 `privacy/Masking.kt::restoreForExport` 에 있다. 여기 다시
//   만들지 않는다 — 복원 규칙(정확히 1회일 때만·검수본 없으면 보류)은 마스킹 쪽 결정이고
//   두 벌로 두면 한쪽만 고쳐지는 날이 온다. Python 이 `export.py` 에 둔 것을 그대로 옮기지
//   않은 유일한 자리이며, 그 판단의 근거는 `restoreForExport` KDoc 에 적혀 있다.

/**
 * 내보내기 형식. 값이 그대로 확장자다.
 *
 * 원본: `ExportFormat`. pdf·구버전 hwp 는 Lean MVP 범위 밖이다.
 */
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

/**
 * 파일명에서 걷어낼 문자.
 *
 * 제어문자(C0·DEL)와 경로 구분자·따옴표·윈도우 예약 문자다. 제목은 **본문 첫 줄에서 유도한
 * 사용자 입력**이므로, 이것을 그대로 파일명에 쓰면 디렉터리를 벗어나거나(`../`) 저장이
 * 실패하는 이름이 된다.
 */
private val FORBIDDEN_IN_FILENAME = Regex("""[\u0000-\u001F\u007F"\\/:*?<>|]""")

/** 제목이 통째로 지워졌을 때 쓸 이름. */
private const val FALLBACK_NAME = "쉬운 글"

/**
 * 확장자를 뺀 파일명 길이 상한.
 *
 * **문자 수로 센다.** 파일 시스템 상한은 바이트지만(대개 255), 한글은 UTF-8 로 3바이트라
 * 여기서 바이트로 세면 사용자가 보는 길이와 어긋난다. 80자면 한글이어도 240바이트라
 * 확장자를 붙여도 상한 안이다.
 */
private const val MAX_FILENAME_STEM = 80

/** `filename*` 을 모르는 클라이언트를 위한 ASCII 대체 이름. */
private const val ASCII_FALLBACK_STEM = "easy-read"

/** 앞뒤에서 깎아 낼 문자 — 점과 공백. */
private const val TRIMMED_EDGE_CHARS = ". "

/** `Byte` 의 부호를 떼고 0..255 로 읽기 위한 마스크. JVM `Byte` 는 부호 있는 8비트다. */
private const val BYTE_MASK = 0xFF

/**
 * 문서 제목으로 내려받을 파일명을 만든다.
 *
 * 원본: `app/easyread/export.py::export_filename`.
 *
 * 금지 문자를 **공백으로** 바꾸고(지우지 않는다 — 지우면 낱말이 붙는다) 공백을 접은 뒤,
 * 앞뒤의 점·공백을 깎는다. 앞뒤 점을 깎는 이유는 숨김 파일(`.name`)이나 윈도우가 거부하는
 * 이름(`name.`)이 되는 것을 막기 위해서다.
 *
 * 자르기는 **깎기 → 자르기 → 다시 깎기** 순서다. 자른 자리에 점이 남을 수 있어서
 * 한 번 더 깎는다.
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
            .take(MAX_FILENAME_STEM)
            .trim { it in TRIMMED_EDGE_CHARS }
    return "${stem.ifEmpty { FALLBACK_NAME }}.${format.extension}"
}

/**
 * RFC 5987 방식으로 파일명을 담은 `Content-Disposition` 헤더 값을 만든다.
 *
 * 원본: `app/easyread/export.py::content_disposition`.
 *
 * HTTP 헤더 값은 latin-1 만 담을 수 있어 한글 파일명을 그대로 넣으면 인코딩 오류이거나
 * 깨진 이름이 된다. 퍼센트 인코딩한 `filename*` 을 진짜 이름으로 쓰고, 그 확장을 모르는
 * 클라이언트를 위해 ASCII 대체 이름을 함께 둔다.
 *
 * 인코딩에 `java.net.URLEncoder` 를 **쓰지 않는다** — 그것은 form 인코딩이라 공백을 `+` 로
 * 바꾸고 `*`·`~` 같은 문자를 남긴다. RFC 5987 의 `ext-value` 는 그 둘 다 어긋난다.
 * 공백은 `%20` 이어야 하고, 안전 문자 집합도 다르다. 그래서 안전 문자를 직접 정하고
 * 나머지를 UTF-8 바이트의 퍼센트 표기로 적는다.
 */
fun contentDisposition(filename: String): String {
    val suffix = filename.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    return "attachment; filename=\"$ASCII_FALLBACK_STEM$suffix\"; " +
        "filename*=UTF-8''${percentEncode(filename)}"
}

/**
 * RFC 5987 `ext-value` 의 퍼센트 인코딩.
 *
 * 안전하게 둘 문자는 원본(Python `quote(..., safe='')`)과 같은 집합이다 —
 * 영숫자와 `_.-~`. 그 밖은 전부 UTF-8 바이트 단위로 `%XX`(대문자 16진)다.
 */
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

/**
 * 내보낼 파일 한 건. 전송에 필요한 것만 담는다 — HTTP 헤더 조립은 api 계층이 한다.
 *
 * `data class` 이지만 [content] 가 `ByteArray` 라 기본 `toString()` 은 본문을 찍지 않고
 * 배열 주소를 찍는다. 그래도 **명시적으로 가린다** — 필드가 늘거나 타입이 `String` 으로
 * 바뀌는 날 조용히 새는 것을 막는다(`MaskingResult` 가 같은 이유로 받은 처리와 같다).
 *
 * `equals`/`hashCode` 도 `ByteArray` 때문에 참조 비교가 되므로 내용 비교로 재정의한다 —
 * data class 가 주는 것을 반만 쓰면 다음 사람이 값 비교를 기대하고 틀린다.
 */
class ExportFile(
    val filename: String,
    val mediaType: String,
    val content: ByteArray,
) {
    override fun toString(): String = "ExportFile(filename=$filename, mediaType=$mediaType, content=${content.size}바이트)"

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

/**
 * 본문을 TXT 바이트로 만든다.
 *
 * 원본: `app/easyread/export.py::render_export` 의 `ExportFormat.TXT` 갈래.
 *
 * ## 제어문자 제거를 여기서 하는 이유
 *
 * 본문을 이루는 두 재료(워커가 저장한 AI 초안, 복원해 넣은 마스킹 원문)는 저장 경로에서
 * 정규화되지 않으므로 **여기가 유일한 방어**다. 형식별 분기 뒤가 아니라 앞에서 거른다 —
 * XML 만 못 담는 문자라고 docx 에서만 지우면 두 산출물의 내용이 갈린다.
 *
 * ## 제목을 본문에 얹지 않는다
 *
 * docx 는 제목 스타일이 구조를 만들지만 txt 는 구조가 없어, 제목을 얹으면 본문과 구분되지
 * 않는 중복 줄만 남는다. 제목은 **파일명에만** 쓰인다.
 *
 * ## BOM 을 붙이지 않는다
 *
 * 미디어 타입이 `charset=utf-8` 을 명시하므로 BOM 은 불필요하고, 붙이면 그 세 바이트가
 * 본문 첫 글자 앞에 보이는 편집기가 있다.
 */
fun renderTxt(
    title: String,
    body: String,
): ExportFile =
    ExportFile(
        filename = exportFilename(stripControlChars(title), ExportFormat.TXT),
        mediaType = ExportFormat.TXT.mediaType,
        content = stripControlChars(body).toByteArray(StandardCharsets.UTF_8),
    )
