package kr.easydoc.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.core.easyread.contentDisposition
import kr.easydoc.core.easyread.exportFilename
import kr.easydoc.core.easyread.renderTxt
import kr.easydoc.core.parity.ParityActual
import kr.easydoc.core.parity.ParityCase
import kr.easydoc.core.parity.ParityFixtureCase
import kr.easydoc.core.parity.ParityFixtures
import kr.easydoc.core.privacy.MaskCategory
import kr.easydoc.core.privacy.MaskedItem
import kr.easydoc.core.privacy.ModelDraft
import kr.easydoc.core.privacy.ReviewedBody
import kr.easydoc.core.privacy.restoreForExport
import kr.easydoc.core.security.Secret
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/** `export` 도메인 parity 산출물 생산자. */
class ExportParityTest {
    private companion object {
        const val DOMAIN = "export"

        /** fixture 가 파일명·헤더를 요구하는 형식 셋. */
        val FILENAME_FORMATS = listOf(ExportFormat.DOCX, ExportFormat.TXT, ExportFormat.HWPX)
    }

    @Test
    @Tag("parity")
    @DisplayName("export fixture 전건을 돌려 parity/actual 에 산출물을 쓴다")
    fun `산출물을 만든다`() {
        val cases = ParityFixtures.cases(DOMAIN)
        val produced = cases.map { ParityCase(id = it.id, actual = runCase(it)) }

        val written = ParityActual.write(DOMAIN, "$DOMAIN.json", produced)

        assertThat(produced).hasSameSizeAs(cases)
        assertThat(written.fileName.toString()).isEqualTo("$DOMAIN.json")
    }

    private fun runCase(case: ParityFixtureCase): JsonElement {
        val input = case.input
        return when {
            input.containsKey("originals") -> restoreActual(input)
            input.containsKey("body") -> txtActual(input)
            else -> filenameActual(input)
        }
    }

    /** 형식 셋 각각의 파일명과 헤더. */
    private fun filenameActual(input: JsonObject): JsonElement {
        val title = input.string("title")
        return JsonObject(
            FILENAME_FORMATS.associate { format ->
                val filename = exportFilename(title, format)
                format.extension to
                    JsonObject(
                        mapOf(
                            "filename" to JsonPrimitive(filename),
                            "content_disposition" to JsonPrimitive(contentDisposition(filename)),
                        ),
                    )
            },
        )
    }

    /** 자리표시자 복원. */
    private fun restoreActual(input: JsonObject): JsonElement {
        val text = input.string("text")
        val items =
            input
                .getValue("originals")
                .jsonObject
                .map { (placeholder, original) ->
                    MaskedItem(
                        category = categoryOf(placeholder),
                        placeholder = placeholder,
                        original = Secret(original.jsonPrimitive.content),
                    )
                }

        val restoration = restoreForExport(ModelDraft(text), ReviewedBody(text), items)
        return JsonObject(mapOf("text" to JsonPrimitive(restoration.text)))
    }

    /** TXT 바이트. 해시는 내용 바이트의 것이다(컨테이너가 없어 그것이 곧 파일이다). */
    private fun txtActual(input: JsonObject): JsonElement {
        val file = renderTxt(input.string("title"), input.string("body"))
        return JsonObject(
            mapOf(
                "filename" to JsonPrimitive(file.filename),
                "media_type" to JsonPrimitive(file.mediaType),
                "content_utf8" to JsonPrimitive(String(file.content, Charsets.UTF_8)),
                "content_sha256_hex" to JsonPrimitive(sha256Hex(file.content)),
            ),
        )
    }

    /** 자리표시자 라벨에서 범주를 되읽는다. */
    private fun categoryOf(placeholder: String): MaskCategory =
        MaskCategory.entries.firstOrNull { placeholder.startsWith("[[${it.label}") }
            ?: error("자리표시자 `$placeholder` 의 범주를 읽을 수 없다 — 범주가 늘었는지 확인하라")

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.content
            ?: error("fixture 입력에 `$key` 가 없다 — fixture 형식이 바뀌었는지 확인하라")
}
