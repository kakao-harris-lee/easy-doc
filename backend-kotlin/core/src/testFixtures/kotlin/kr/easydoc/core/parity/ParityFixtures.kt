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

/**
 * `parity/fixtures/{도메인}` 아래 json fixture 를 **Kotlin 생산자 테스트가 읽게** 하는 입력 하네스.
 *
 * (경로를 `{도메인}` 다음에 별표로 적지 않는다 — Kotlin 은 블록 주석 중첩을 허용해서
 * KDoc 안의 `슬래시+별표` 가 닫히지 않은 주석을 연다. 실제로 한 번 컴파일이 깨졌다.)
 *
 * [ParityActual] 의 짝이다 — 그쪽이 산출물을 쓰고 이쪽이 입력을 읽는다.
 *
 * ## 기대값을 손으로 옮겨 적지 않는 이유
 *
 * fixture 의 `input` 을 Kotlin 테스트에 상수로 베껴 적으면, 비교하는 것이 두 **구현**이
 * 아니라 두 벌의 **사람 해석**이 된다. fixture 가 바뀌어도 Kotlin 쪽은 옛 입력을 계속
 * 돌리고, 게이트는 그 사실을 모른 채 초록이다. 그래서 입력은 언제나 파일에서 읽는다
 * (`02_parity-verifier_conversion-spec.md` §7-2).
 *
 * ## 경로를 시스템 프로퍼티로만 받는 이유
 *
 * [ParityActual] 과 같다 — 경로를 코드에 박으면 실행 위치(모듈 디렉터리)에 따라 조용히
 * 어긋나고, 없을 때 던지지 않으면 "fixture 를 못 찾아 0건을 산출했다"가 통과로 집계된다.
 * `build.gradle.kts` 가 `parity.fixtures.dir` 을 준다.
 *
 * ## 판정하지 않는다
 *
 * 이 객체는 `assert`·`reference` 를 읽지도 해석하지도 않는다. 값 판정은 전부
 * `compare_parity.py` 의 몫이다 — 생산자가 기대값을 알면 그것에 맞추는 코드를 쓰게 된다.
 */
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

    /**
     * 한 도메인의 fixture 케이스를 순서대로 읽는다.
     *
     * @param domain `parity/fixtures/` 의 디렉터리 이름.
     * @param fileName 확장자 포함 파일명. 기본값은 `{도메인}.json` — 산출물도 **같은 이름**이어야
     *   비교기가 짝짓는다(`ParityActual.write` 의 `fileName` 과 맞춘다).
     */
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

/**
 * fixture 케이스의 **입력부만** 담는다.
 *
 * `assert`·`reference` 를 일부러 싣지 않는다 — 생산자가 기대값을 볼 수 있으면 그것에
 * 맞추는 코드가 만들어지고, 그 순간 게이트는 구현이 아니라 베끼기를 검사하게 된다.
 */
data class ParityFixtureCase(
    val id: String,
    val input: JsonObject,
)
