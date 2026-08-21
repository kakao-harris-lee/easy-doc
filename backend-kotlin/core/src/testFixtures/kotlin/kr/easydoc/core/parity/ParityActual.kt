package kr.easydoc.core.parity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** parity 역방향 산출물(`parity/actual/{도메인}` 아래 json 산출물)을 **Kotlin 테스트가** 쓰게 하는 하네스. */
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

    /** 산출물 루트. 시스템 프로퍼티가 없으면 던진다 (위 KDoc 참고). */
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

    /** 한 도메인의 산출물 파일 하나를 쓴다. */
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

/** 산출물 케이스 한 건. */
data class ParityCase(
    val id: String,
    val actual: JsonElement,
)
