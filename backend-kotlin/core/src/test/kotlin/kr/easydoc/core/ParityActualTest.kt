package kr.easydoc.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.easydoc.core.parity.ParityActual
import kr.easydoc.core.parity.ParityCase
import kr.easydoc.core.parity.ParityHarnessSelfCheck
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.readText

/**
 * parity 산출 하네스가 `compare_parity.py` 가 읽는 모양 그대로 쓰는지 확인한다.
 *
 * 값 판정이 아니라 **형식과 경로**의 검증이다. 형식이 틀리면 Phase 3·4에서 crypto·jwt
 * 산출물을 만들어도 비교기가 "runtime 미선언"·"읽기 실패"로 종료 코드 1을 내며,
 * 그때는 원인이 암호 구현인지 하네스인지 가리는 데 시간이 든다.
 */
class ParityActualTest {
    @Test
    fun `산출물은 runtime kotlin 과 cases 배열을 갖는다`() {
        val written =
            ParityActual.write(
                domain = "harness-format-check",
                fileName = "shape.json",
                cases =
                    listOf(
                        ParityCase("case-1", JsonObject(mapOf("masked" to JsonPrimitive("[주민등록번호]")))),
                        ParityCase("case-2", JsonPrimitive("문자열 산출값도 된다")),
                    ),
            )

        val document = Json.parseToJsonElement(written.readText()).jsonObject

        assertThat(document["runtime"]?.jsonPrimitive?.content).isEqualTo("kotlin")

        val cases = document["cases"]?.jsonArray ?: error("cases 배열이 없다")
        assertThat(cases).hasSize(2)
        assertThat(cases[0].jsonObject["id"]?.jsonPrimitive?.content).isEqualTo("case-1")
        assertThat(
            cases[0]
                .jsonObject["actual"]
                ?.jsonObject
                ?.get("masked")
                ?.jsonPrimitive
                ?.content,
        ).isEqualTo("[주민등록번호]")

        // 한글이 유니코드 이스케이프로 뭉개지지 않아야 Python 쪽 비교에서 그대로 짝지어진다.
        assertThat(written.readText()).contains("[주민등록번호]")

        Files.deleteIfExists(written)
    }

    @Test
    fun `fixture 와 같은 상대 경로에 쓴다`() {
        val written =
            ParityActual.write(
                domain = "harness-path-check",
                fileName = "kotlin-encrypt.json",
                cases = listOf(ParityCase("c", JsonPrimitive(1))),
            )

        assertThat(written.fileName.toString()).isEqualTo("kotlin-encrypt.json")
        assertThat(written.parent.fileName.toString()).isEqualTo("harness-path-check")
        assertThat(written.parent.parent).isEqualTo(ParityActual.actualRoot())

        Files.deleteIfExists(written)
    }

    @Test
    fun `케이스가 없으면 거부한다`() {
        assertThatThrownBy {
            ParityActual.write("harness-empty", "empty.json", emptyList())
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `json 이 아닌 파일명을 거부한다`() {
        assertThatThrownBy {
            ParityActual.write("harness-empty", "kotlin.txt", listOf(ParityCase("c", JsonPrimitive(1))))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    /**
     * Phase 0 필수 조치 E의 배선 증명.
     *
     * `@Tag("parity")` 가 붙어 있으므로 `./gradlew parityHarness` 가 이 테스트를 고르고,
     * 그때만 저장소 루트 `parity/_harness-selfcheck/kotlin.json` 이 갱신된다.
     * 일반 `./gradlew test` 에서는 모듈 `build/` 안에만 쓴다.
     */
    @Test
    @Tag("parity")
    fun `parityHarness 태스크가 저장소 루트에 산출물을 쓴다`() {
        val written = ParityHarnessSelfCheck.write()

        assertThat(Files.exists(written)).isTrue()

        val document = Json.parseToJsonElement(written.readText()).jsonObject
        assertThat(document["runtime"]?.jsonPrimitive?.content).isEqualTo("kotlin")
        // Python 하네스로는 만들 수 없는 값 — 이 파일이 JVM 실행의 산물임을 뒷받침한다.
        assertThat(
            document["jvm"]
                ?.jsonObject
                ?.get("kotlinVersion")
                ?.jsonPrimitive
                ?.content,
        ).isNotBlank()
    }
}
