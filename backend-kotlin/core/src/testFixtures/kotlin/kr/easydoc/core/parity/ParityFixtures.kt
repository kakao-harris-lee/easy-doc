package kr.easydoc.core.parity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText

/** `parity/fixtures/{도메인}` 아래 json fixture 를 **Kotlin 생산자 테스트가 읽게** 하는 입력 하네스. */
object ParityFixtures {
    private const val FIXTURE_DIR_PROPERTY = "parity.fixtures.dir"

    private val json = Json { ignoreUnknownKeys = true }

    /** fixture 루트. 시스템 프로퍼티가 없으면 던진다 (위 KDoc 참고). */
    fun root(): Path {
        val configured =
            System.getProperty(FIXTURE_DIR_PROPERTY)
                ?: error(
                    "시스템 프로퍼티 $FIXTURE_DIR_PROPERTY 가 없다. " +
                        "parity 생산자 테스트는 Gradle test/parityHarness 태스크로만 실행한다 " +
                        "(경로를 코드에 박으면 실행 위치에 따라 조용히 어긋난다).",
                )
        return Paths.get(configured)
    }

    /** 한 도메인의 fixture 케이스를 순서대로 읽는다. */
    fun cases(
        domain: String,
        fileName: String = "$domain.json",
    ): List<ParityFixtureCase> {
        val path = root().resolve(domain).resolve(fileName)
        check(Files.isRegularFile(path)) {
            "parity fixture 가 없다: $path — 생성 명령은 " +
                "`uv run python .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py --domain $domain`"
        }

        val document = json.parseToJsonElement(path.readText()).jsonObject
        val cases =
            (document["cases"]?.jsonArray ?: error("fixture 에 cases 배열이 없다: $path"))
                .map { element ->
                    val case = element.jsonObject
                    ParityFixtureCase(
                        id = case["id"]?.jsonPrimitive?.content ?: error("케이스에 id 가 없다: $path"),
                        input = case["input"]?.jsonObject ?: JsonObject(emptyMap()),
                    )
                }

        check(cases.isNotEmpty()) { "fixture 케이스가 0건이다: $path" }
        return cases
    }
}

/** fixture 케이스의 **입력부만** 담는다. */
data class ParityFixtureCase(
    val id: String,
    val input: JsonObject,
)
