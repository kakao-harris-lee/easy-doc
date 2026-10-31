package kr.easydoc.infrastructure.illustration.generation

import kr.easydoc.core.exceptions.IllustrationResultException
import kr.easydoc.core.illustration.generation.ILLUSTRATION_GENERATION_SIZE
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Base64
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.ImageInputStream

internal const val MAX_ILLUSTRATION_PNG_BYTES: Int = 8 * 1024 * 1024
internal const val MAX_ILLUSTRATION_BASE64_LENGTH: Int = 4 * ((MAX_ILLUSTRATION_PNG_BYTES + 2) / 3)

internal object GeneratedPngValidator {
    private val pngSignature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun validate(encoded: String): ByteArray {
        val bytes = decode(encoded)
        requireMaximumSize(bytes)
        requireSignature(bytes)
        decodeImage(bytes)
        return bytes
    }

    private fun decode(encoded: String): ByteArray {
        if (encoded.length > MAX_ILLUSTRATION_BASE64_LENGTH) {
            throw invalid("base64 길이가 8MiB 평문 상한을 넘습니다")
        }
        return try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw invalid("base64 형식이 아닙니다")
        }
    }

    private fun requireMaximumSize(bytes: ByteArray) {
        if (bytes.size > MAX_ILLUSTRATION_PNG_BYTES) throw invalid("PNG가 8MiB 상한을 넘습니다")
    }

    private fun requireSignature(bytes: ByteArray) {
        if (bytes.size < pngSignature.size || !pngSignature.indices.all { bytes[it] == pngSignature[it] }) {
            throw invalid("PNG signature가 아닙니다")
        }
    }

    private fun decodeImage(bytes: ByteArray) {
        try {
            val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: throw invalid("PNG 입력이 없습니다")
            input.use(::decodeImage)
        } catch (_: IOException) {
            throw invalid("PNG를 디코딩할 수 없습니다")
        }
    }

    private fun decodeImage(input: ImageInputStream) {
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) throw invalid("PNG reader가 없습니다")
        val reader = readers.next()
        try {
            reader.input = input
            requireSize(reader)
            reader.read(0)
        } finally {
            reader.dispose()
        }
    }

    private fun requireSize(reader: ImageReader) {
        if (reader.getWidth(0) != ILLUSTRATION_GENERATION_SIZE ||
            reader.getHeight(0) != ILLUSTRATION_GENERATION_SIZE
        ) {
            throw invalid("1024x1024 PNG가 아닙니다")
        }
    }

    private fun invalid(reason: String) = IllustrationResultException("openai 이미지 응답 형식 오류 ($reason)")
}
