package kr.easydoc.core.parity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * parity 역방향 산출물(`parity/actual/{도메인}` 아래 json 산출물)을 **Kotlin 테스트가** 쓰게 하는 하네스.
 *
 * ## 이 파일이 존재하는 이유
 *
 * `python-kotlin-parity` 스킬은 자기가 막지 못하는 구멍을 하나 적어 두었다.
 *
 * > "그 산출물을 정말 Kotlin이 만들었는가"는 증명되지 않는다. fixture가 Fernet 키와
 * > JWT 시크릿을 공개하므로 같은 값을 Python으로도 만들 수 있고, `runtime` 필드는
 * > 손으로 적을 수 있다. 이 경계는 코드로 닫히지 않는다 — **Kotlin 테스트 하네스가
 * > `actual_file`을 쓰도록 CI에 배선하는 것이 유일한 방어**이며, Phase 1에서 그 배선을
 * > 만들 때 함께 확인한다.
 *
 * 즉 이 하네스는 값을 검증하지 않는다. 값 판정은 `compare_parity.py` 가 한다.
 * 이것이 보장하는 것은 **산출 경로가 Kotlin 테스트 실행에만 열려 있다**는 것 하나다.
 *
 * ## 산출 경로를 시스템 프로퍼티로만 받는 이유
 *
 * 경로를 코드에 박으면 어떤 실행 경로에서든(예: 사람이 IDE에서 한 테스트만 돌려도)
 * 게이트 디렉터리가 갱신된다. 그러면 "게이트에 있는 파일은 CI가 만든 것"이라는
 * 전제가 깨진다. 그래서 `parity.actual.dir` 시스템 프로퍼티가 **없으면 던진다** —
 * 조용히 쓰지 않고 넘어가면, 산출물이 만들어지지 않은 것을 아무도 모르는 채
 * 비교기가 "미검증(종료 코드 2)"만 계속 찍게 된다.
 *
 * `build.gradle.kts` 가 이 프로퍼티를 두 값으로 나눠 준다.
 * - `test` → 모듈 `build/parity-actual/` (버려지는 사본. 게이트를 건드리지 않는다)
 * - `parityHarness` → 저장소 루트 `parity/actual/` (게이트가 읽는 진짜 산출물)
 */
object ParityActual {
    /** 비교기가 요구하는 런타임 선언. 이 값이 아니면 `compare_parity.py` 가 종료 코드 1로 막는다. */
    const val RUNTIME: String = "kotlin"

    private const val ACTUAL_DIR_PROPERTY = "parity.actual.dir"

    private val json =
        Json {
            // 사람이 diff 로 읽는 파일이라 들여쓴다. 들여쓰기 폭은 지정하지 않는다 —
            // prettyPrintIndent 는 opt-in 이 필요한 실험 API이고, 비교기는 값을 파싱해서
            // 보므로 폭이 판정에 영향을 주지 않는다.
            prettyPrint = true
        }

    /**
     * 산출물 루트. 시스템 프로퍼티가 없으면 던진다 (위 KDoc 참고).
     */
    fun actualRoot(): Path {
        val configured =
            System.getProperty(ACTUAL_DIR_PROPERTY)
                ?: error(
                    "시스템 프로퍼티 $ACTUAL_DIR_PROPERTY 가 없다. " +
                        "parity 산출은 Gradle test/parityHarness 태스크로만 실행한다 " +
                        "(경로를 코드에 박으면 게이트 디렉터리가 아무 실행에서나 갱신된다).",
                )
        return Paths.get(configured)
    }

    /**
     * 한 도메인의 산출물 파일 하나를 쓴다.
     *
     * @param domain `parity/fixtures/` 의 도메인 디렉터리 이름과 같아야 한다
     *   (`masking`, `crypto`, `jwt`, `argon2` 등). 비교기는 fixture와 **같은 상대 경로**에서
     *   산출물을 찾는다.
     * @param fileName 확장자 포함 파일명. 역방향 케이스는 fixture 의
     *   `verification.actual_file` 이 지정한 이름을 그대로 써야 한다
     *   (예: `kotlin-encrypt.json`).
     * @param cases 케이스 id → 산출값. 값의 모양은 fixture 의 `expected` 와 같은 구조여야 한다.
     * @return 실제로 쓴 경로.
     */
    fun write(
        domain: String,
        fileName: String,
        cases: List<ParityCase>,
    ): Path {
        require(domain.isNotBlank()) { "domain 이 비었다" }
        require(fileName.endsWith(".json")) { "산출물 파일명은 .json 이어야 한다: $fileName" }
        require(cases.isNotEmpty()) {
            "케이스가 0건이다 ($domain/$fileName). 빈 산출물은 비교기에서 '미실행'으로 잡히지만, " +
                "쓰는 쪽에서 먼저 막는 편이 원인 추적이 쉽다."
        }

        val document =
            JsonObject(
                mapOf(
                    "runtime" to JsonPrimitive(RUNTIME),
                    "cases" to
                        JsonArray(
                            cases.map { case ->
                                JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive(case.id),
                                        "actual" to case.actual,
                                    ),
                                )
                            },
                        ),
                ),
            )

        val target = actualRoot().resolve(domain).resolve(fileName)
        Files.createDirectories(target.parent)
        Files.writeString(target, json.encodeToString(JsonElement.serializer(), document) + "\n")
        return target
    }
}

/**
 * 산출물 케이스 한 건.
 *
 * @param id fixture 의 케이스 id 와 정확히 같아야 한다 — 비교기가 이 값으로 짝짓는다.
 * @param actual Kotlin 구현이 실제로 낸 값.
 */
data class ParityCase(
    val id: String,
    val actual: JsonElement,
)
