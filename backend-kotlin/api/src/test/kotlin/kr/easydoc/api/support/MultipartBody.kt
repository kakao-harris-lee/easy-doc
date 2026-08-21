package kr.easydoc.api.support

import java.io.ByteArrayOutputStream
import java.util.UUID

/** `multipart/form-data` 요청 본문을 바이트로 직접 조립한다. */
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
