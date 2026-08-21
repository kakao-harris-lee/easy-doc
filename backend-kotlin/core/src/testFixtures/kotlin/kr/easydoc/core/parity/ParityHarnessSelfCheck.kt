package kr.easydoc.core.parity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/** Phase 1 배선 증명용 자체 점검 산출물. */
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

    /** 자체 점검 산출물을 쓴다. */
    fun write(): Path {
        val document =
            JsonObject(
                mapOf(
                    "runtime" to JsonPrimitive(ParityActual.RUNTIME),
                    // 2026-08-14(C-24) 현행화. 옛 문구는 "Phase 1 배선 증명 전용"이었는데,
                    // Phase 2 에서 masking·repair-adoption 이 실제로 값을 판정하기 시작하면서
                    // **거짓이 됐다** — 배선은 이미 증명됐고 이 파일은 그 뒤로도 계속 쓰인다.
                    // 바뀌지 않은 것은 "게이트 판정에 쓰지 않는다" 쪽이라 그 절만 남긴다.
                    "purpose" to
                        JsonPrimitive(
                            "하네스 배선 자체 점검용(도메인 산출물이 아니다). " +
                                "게이트 판정에 쓰지 않는다 — 판정 범위의 정본은 " +
                                "backend-kotlin/parity-domains.txt 다.",
                        ),
                    "jvm" to
                        JsonObject(
                            mapOf(
                                "version" to JsonPrimitive(System.getProperty("java.version") ?: "unknown"),
                                "vendor" to JsonPrimitive(System.getProperty("java.vendor") ?: "unknown"),
                                "kotlinVersion" to JsonPrimitive(KotlinVersion.CURRENT.toString()),
                            ),
                        ),
                    // 어느 도메인이 포팅됐는지는 **여기 적지 않는다.** 그 목록의 정본은
                    // backend-kotlin/parity-domains.txt 하나이고, Gradle parityManifestCheck 가
                    // 그 파일을 실제 산출물과 대조한다. 여기에 목록을 다시 적으면 두 벌이
                    // 되어 한쪽만 갱신된 채 사실과 어긋난 진행 상황이 남는다.
                    "domainsDeclaredIn" to JsonPrimitive("backend-kotlin/parity-domains.txt"),
                ),
            )

        val target = ParityActual.actualRoot().resolveSibling(DIRECTORY_NAME).resolve(FILE_NAME)
        Files.createDirectories(target.parent)
        Files.writeString(target, json.encodeToString(JsonElement.serializer(), document) + "\n")
        return target
    }
}
