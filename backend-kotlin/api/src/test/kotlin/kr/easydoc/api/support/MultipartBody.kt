package kr.easydoc.api.support

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * `multipart/form-data` 요청 본문을 **바이트로 직접 조립한다**.
 *
 * ## 왜 손으로 만드는가
 *
 * 명세 §5-1 이 업로드 케이스를 MockMvc 로 재는 것을 금지한다 — 컨테이너가 만드는 응답과
 * 파트 파싱은 목으로 **재현되지 않으면서 통과한다**. 실제 소켓으로 쏘려면
 * `java.net.http.HttpClient` 를 쓰는데 그쪽에는 multipart 본문 생성기가 없다.
 *
 * ## `Content-Type` 을 호출자가 고를 수 있다
 *
 * DC-5 가 **대소문자를 뒤섞은 미디어 타입**(`Multipart/Form-Data`)으로 같은 본문을 쏜다.
 * 그래서 [contentTypeWith] 가 타입 문자열을 인자로 받는다 — 경계(boundary) 파라미터는
 * 그대로 두고 타입만 바꾼다.
 *
 * ## 파일 파트와 값 파트를 가르는 것은 `filename` 이다
 *
 * `Content-Disposition` 에 `filename` 이 있으면 서블릿 컨테이너가 그 파트를 파일로 올리고,
 * 없으면 폼 파라미터로 둔다. DC-6 의 「파일이 아닌 값」 갈래가 정확히 그 차이를 쓴다 —
 * 그래서 두 파트 종류를 **별도 메서드**로 두어 테스트가 어느 쪽을 보내는지 드러나게 한다.
 */
class MultipartBody {
    private val boundary = "easydoc${UUID.randomUUID().toString().replace("-", "")}"
    private val sink = ByteArrayOutputStream()

    /** 파일 파트 — `filename` 이 있으므로 컨테이너가 파일로 올린다. */
    fun file(
        name: String,
        filename: String,
        content: ByteArray,
    ): MultipartBody {
        writeHeader(
            """form-data; name="$name"; filename="$filename"""" + "\r\n" + "Content-Type: application/octet-stream",
        )
        sink.write(content)
        sink.write(CRLF)
        return this
    }

    /** 값 파트 — `filename` 이 **없으므로** 폼 파라미터가 된다. */
    fun value(
        name: String,
        content: String,
    ): MultipartBody {
        writeHeader("""form-data; name="$name"""")
        sink.write(content.toByteArray(Charsets.UTF_8))
        sink.write(CRLF)
        return this
    }

    fun build(): ByteArray {
        val closing = ByteArrayOutputStream()
        closing.write(sink.toByteArray())
        closing.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return closing.toByteArray()
    }

    /** 기본 미디어 타입. */
    fun contentType(): String = contentTypeWith(FORM_DATA)

    /** DC-5 가 쓰는 자리 — 타입 문자열만 갈아 끼운다. */
    fun contentTypeWith(mediaType: String): String = "$mediaType; boundary=$boundary"

    private fun writeHeader(disposition: String) {
        sink.write("--$boundary\r\nContent-Disposition: $disposition\r\n\r\n".toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val FORM_DATA = "multipart/form-data"
        val CRLF = "\r\n".toByteArray(Charsets.UTF_8)
    }
}
