package kr.easydoc.infrastructure.quality

import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * 레인이 문서마다 실어 줄 **사전 컨텍스트**. 사전 있음/없음 A/B 의 「있음」 쪽 재료다.
 *
 * 컨텍스트는 저장소에 두지 않고 [DIRECTORY_ENV] 가 가리키는 디렉터리에서 읽는다 — 사전 산출물은
 * 레인 코드와 수명이 다르고, 실측마다 다른 것을 실어 보는 것이 이 이음매의 목적이다.
 *
 * **본문은 어디에도 찍지 않는다.** [description] 이 남기는 것은 개수뿐이다(CLAUDE.md 관측 규칙).
 */
internal class LaneDictionary private constructor(
    /** `null` 이면 주입하지 않는 실행이다 — 프롬프트가 베이스라인과 한 글자도 다르지 않다. */
    private val directory: Path?,
    private val contexts: Map<String, String>,
    private val documentCount: Int,
) {
    fun contextFor(documentId: String): String? = contexts[documentId]

    /** 이 실행이 무엇을 실었는지 한 줄. 레인 요약의 측정 조건에 붙는다. */
    val description: String
        get() = if (directory == null) "dictContext=off" else "dictContext=${contexts.size}/$documentCount"

    companion object {
        /** 문서별 컨텍스트 파일이 있는 디렉터리. 미설정이면 주입하지 않는다. */
        const val DIRECTORY_ENV: String = "EASYDOC_LANE_DICT_CONTEXT_DIR"

        /** 문서 id 하나에 파일 하나 — `<디렉터리>/<id>.txt`. */
        const val FILE_SUFFIX: String = ".txt"

        /**
         * [env] 가 준 환경으로 무엇을 실을지 정한다. [env] 를 인자로 받는 이유는
         * [GoldenLlmLane.plan] 과 같다 — 선택 규칙을 유료 호출 없이 시험하기 위해서다.
         *
         * 디렉터리를 설정했는데 없으면 **실패로 알린다.** 그 값을 설정한 사람은 「사전 있음」을
         * 잴 의도였고, 조용히 빈 컨텍스트로 넘어가면 베이스라인을 재고도 A/B 를 쟀다고 적게 된다.
         */
        fun plan(
            env: (String) -> String?,
            documentIds: List<String>,
        ): LaneDictionaryPlan {
            val configured =
                env(DIRECTORY_ENV)?.takeIf(String::isNotBlank)
                    ?: return LaneDictionaryPlan.Ready(LaneDictionary(null, emptyMap(), documentIds.size))

            val directory = Path.of(configured)
            return if (directory.isDirectory()) {
                LaneDictionaryPlan.Ready(LaneDictionary(directory, read(directory, documentIds), documentIds.size))
            } else {
                LaneDictionaryPlan.Unusable(
                    "$DIRECTORY_ENV=$configured 가 디렉터리가 아니다 — 이대로 돌면 컨텍스트 없이 " +
                        "베이스라인을 재고 A/B 를 쟀다고 적게 된다.",
                )
            }
        }

        /**
         * 실행 **전에** 전부 읽는다. 개수를 알아야 측정 조건을 요약에 적을 수 있고, 읽지 못하는
         * 파일은 유료 호출을 시작하기 전에 터지는 편이 낫다.
         *
         * 빈 파일은 싣지 않은 것으로 센다 — `buildUserPrompt` 가 공백을 없는 것으로 보므로,
         * 세어 주면 개수가 실제 조건을 거짓으로 말한다.
         */
        private fun read(
            directory: Path,
            documentIds: List<String>,
        ): Map<String, String> =
            documentIds
                .mapNotNull { id ->
                    directory
                        .resolve("$id$FILE_SUFFIX")
                        .takeIf { it.isRegularFile() }
                        ?.readText()
                        ?.takeIf(String::isNotBlank)
                        ?.let { id to it }
                }.toMap()
    }
}

/** 사전 컨텍스트를 정할 수 있는가. [GoldenLlmLane] 의 [LanePlan] 과 같은 어휘를 쓴다. */
internal sealed interface LaneDictionaryPlan {
    /** 정했다. 주입하지 않는 실행도 여기로 온다 — 그것은 오류가 아니라 베이스라인 조건이다. */
    class Ready(val dictionary: LaneDictionary) : LaneDictionaryPlan

    /** 이 설정으로는 A/B 를 잴 수 없다. 실패로 알린다. */
    class Unusable(val reason: String) : LaneDictionaryPlan
}
