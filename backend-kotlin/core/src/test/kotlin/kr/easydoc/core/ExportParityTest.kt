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

/**
 * `export` 도메인 parity 산출물 생산자.
 *
 * ## 선언까지 한 박자 늦은 이유 (이력 — 지우지 말 것)
 *
 * 이 생산자를 다 만든 시점에 `parity/fixtures/export/export.json` 은 `spec_status=pending`
 * 이었고 12건 전부 `assert` 가 없었다. 그 상태에서 선언하면 비교기가 **종료 코드 2(미검증)**
 * 를 내고 CI 가 붉어진다 — 추정이 아니라 실측이다. 그래서 태그 없이 두고, 정본이 `ready` 로
 * 바뀌는 날을 알리는 탐지기([kr.easydoc.core.ParityDeclarationSyncTest])를 함께 뒀다.
 *
 * **그 탐지기가 실제로 물었다** — 정본이 `ready` 로 승격된 직후 `ready 인데 미선언` 으로
 * 빨개졌고, 그래서 이 태그와 선언 두 줄이 붙었다. 주석이었다면 아무 날에도 알리지 않았을
 * 자리다. 같은 일이 남은 도메인에서 반복되므로 탐지기를 지우지 마라.
 *
 * ## 케이스 세 갈래
 *
 * fixture 는 `input` 의 모양으로 갈린다 — 파일명(`title` 만) · 복원(`text`+`originals`) ·
 * TXT 바이트(`title`+`body`). 판별을 키 존재로 하는 것은 `repair-adoption` 생산자와 같다.
 *
 * ## 바이트 해시를 형식 전체로 비교하지 않는다
 *
 * fixture 가 해시를 요구하는 것은 **TXT 하나**다. docx·hwpx 는 zip 컨테이너라 타임스탬프·
 * 엔트리 순서·압축 수준이 Python 과 같아질 수 없고, 같아질 필요도 없다(미결 원장).
 * TXT 는 그런 컨테이너가 없어 "본문 UTF-8 바이트"가 곧 파일이므로 해시가 성질이 된다.
 * 확인 결과 fixture 도 그렇게 돼 있다 — `content_sha256_hex` 는 txt 케이스에만 있다.
 *
 * ## 복원은 `restoreForExport` 를 쓴다
 *
 * `export.py::restore_placeholders` 를 따로 옮기지 않았다. 복원 규칙(정확히 1회일 때만,
 * 검수본이 없으면 보류)은 마스킹 쪽 결정이고 두 벌로 두면 한쪽만 고쳐진다.
 */
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

    /**
     * 자리표시자 복원.
     *
     * fixture 는 `originals` 를 **맵**으로 주지만 `restoreForExport` 는 [MaskedItem] 목록을
     * 받는다 — 그 타입이 범주와 [Secret] 을 함께 들기 때문이다. 맵을 목록으로 옮기며
     * 범주는 자리표시자 라벨에서 되읽는다.
     *
     * **[ReviewedBody] 를 만든다.** 이 함수는 검수본이 없으면 복원을 **보류**하므로
     * (사람이 위치를 확증하지 않은 본문에 개인정보를 꽂지 않는다) 복원 성질을 재려면
     * 검수 제출을 표현해야 한다. `MaskingTest` 가 같은 이유로 이미 같은 일을 하고,
     * 생성 지점은 `ProvenanceCreationSitesTest` 허용목록에 사유와 함께 오른다.
     */
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

    /** TXT 바이트. 해시는 **내용 바이트**의 것이다(컨테이너가 없어 그것이 곧 파일이다). */
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

    /**
     * 자리표시자 라벨에서 범주를 되읽는다.
     *
     * 계약이 라벨을 enum 으로 못박았으므로(`MaskedItemResponse`) 되읽기가 성립한다.
     * 못 읽으면 던진다 — 조용히 아무 범주나 고르면 그 오류가 산출물에 섞여 나간다.
     */
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
