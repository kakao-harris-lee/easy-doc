package kr.easydoc.core.parity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Phase 1 배선 증명용 자체 점검 산출물.
 *
 * Phase 1에는 아직 parity 도메인 구현이 하나도 없다(마스킹은 Phase 2, crypto·JWT는
 * Phase 3~4). 그래서 `parity/actual/{도메인}/` 에 넣을 진짜 산출물이 없는데,
 * **배선이 도는지는 지금 증명해야 한다** — Phase 3·4에 가서 "쓰기 경로가 애초에
 * 동작하지 않았다"를 발견하면 그 Phase의 종료 조건까지 함께 밀린다.
 *
 * 그래서 이 파일은 `parity/actual/` **바깥**(`parity/_harness-selfcheck/`)에 쓴다.
 * 게이트 디렉터리에 도메인이 아닌 디렉터리를 남기면 `compare_parity.py` 의
 * 도메인 집합 검사와 섞이기 때문이다.
 *
 * 이 산출물은 게이트 판정에 쓰이지 않는다. 확인하는 것은 딱 하나다:
 * **Gradle `parityHarness` 태스크가 Kotlin 코드를 실행했고, 그 실행이 저장소 루트에
 * 파일을 남길 수 있다.**
 */
object ParityHarnessSelfCheck {
    /** 자체 점검 산출물 디렉터리 이름. `parity/actual/` 의 형제다. */
    const val DIRECTORY_NAME: String = "_harness-selfcheck"

    /** 자체 점검 산출물 파일명. */
    const val FILE_NAME: String = "kotlin.json"

    private val json =
        Json {
            // 사람이 diff 로 읽는 파일이라 들여쓴다. 들여쓰기 폭은 지정하지 않는다 —
            // prettyPrintIndent 는 opt-in 이 필요한 실험 API이고, 비교기는 값을 파싱해서
            // 보므로 폭이 판정에 영향을 주지 않는다.
            prettyPrint = true
        }

    /**
     * 자체 점검 산출물을 쓴다.
     *
     * 담는 것은 실행 사실을 뒷받침하는 런타임 정보뿐이다 — 문서 본문·개인정보·비밀값은
     * 담지 않는다(프로젝트 CLAUDE.md 보안 규칙). JVM 벤더·버전을 담는 이유는 이 파일이
     * Python 하네스로는 만들 수 없는 값을 최소한 하나는 갖게 하기 위해서다.
     */
    fun write(): Path {
        val document =
            JsonObject(
                mapOf(
                    "runtime" to JsonPrimitive(ParityActual.RUNTIME),
                    "purpose" to
                        JsonPrimitive(
                            "Phase 1 배선 증명 전용. 게이트 판정에 쓰지 않는다.",
                        ),
                    "jvm" to
                        JsonObject(
                            mapOf(
                                "version" to JsonPrimitive(System.getProperty("java.version") ?: "unknown"),
                                "vendor" to JsonPrimitive(System.getProperty("java.vendor") ?: "unknown"),
                                "kotlinVersion" to JsonPrimitive(KotlinVersion.CURRENT.toString()),
                            ),
                        ),
                    "domainsPending" to
                        JsonPrimitive(
                            "masking·text·style·style-tables·prompts·postprocess·repair-adoption·export 는 Phase 2, " +
                                "jwt·argon2 는 Phase 3, crypto 는 Phase 4에서 채운다.",
                        ),
                ),
            )

        val target = ParityActual.actualRoot().resolveSibling(DIRECTORY_NAME).resolve(FILE_NAME)
        Files.createDirectories(target.parent)
        Files.writeString(target, json.encodeToString(JsonElement.serializer(), document) + "\n")
        return target
    }
}
