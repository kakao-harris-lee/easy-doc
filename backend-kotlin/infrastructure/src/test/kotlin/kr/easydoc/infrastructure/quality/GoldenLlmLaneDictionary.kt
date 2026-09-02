package kr.easydoc.infrastructure.quality

import kr.easydoc.application.conversion.DictionaryContextSource
import kr.easydoc.application.conversion.NoDictionaryContext
import kr.easydoc.infrastructure.dictionary.DictionaryProperties
import kr.easydoc.infrastructure.queue.ConversionWorkerConfiguration
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * 레인이 문서마다 실어 줄 **사전 컨텍스트**. 사전 있음/없음 A/B 의 「있음」 쪽 재료다.
 *
 * 「있음」을 채우는 방식은 둘이다.
 *
 * ⑴ **파일 주입**([DIRECTORY_ENV]) — 저장소에 두지 않는 문서별 컨텍스트 파일을
 * [DIRECTORY_ENV] 가 가리키는 디렉터리에서 읽어 그대로 싣는다. 사전 산출물은 레인 코드와
 * 수명이 다르고, 실측마다 다른 것을 갈아 끼워 A/B 를 재는 것이 이 이음매의 목적이다.
 *
 * ⑵ **제품 조립**([PRODUCT_ENV]) — 제품 composition root
 * ([ConversionWorkerConfiguration.dictionaryContextSource])가 만드는 실제
 * [DictionaryContextSource] 를 그대로 쓴다. 게이트 ⓪ 2차 측정이 요구하는 「제품 조건
 * (dictContext=on)으로 잰다」의 「제품 조건」은 사전 산출물 한 벌이 아니라 **조립 자체**다 —
 * 인덱스 위치나 정책 예산 숫자를 여기 다시 적으면 값 출처가 둘이 되어 그 요구가 거짓이 된다.
 * 그래서 [DictionaryProperties] 를 **기본값 그대로** 넘긴다 — worker `application.yml` 에
 * override 가 없어 배포 값도 코드 기본값과 같다.
 *
 * 인덱스 **위치**도 노브로 받지 않는다. 제품은 그 값을 런타임 설정으로 받지 않는다 —
 * `DictionaryIndexJsonReader.readClasspathResource` 는 클래스패스 리소스
 * (`/dictionary/easy_dict.index.json`)를 고정으로 읽고, 그 커밋된 사본이 정본
 * `dictionary/dist/easy_dict.index.json` 과 같은지는 `infrastructure/build.gradle.kts` 의
 * `checkDictionaryIndex` 가 `check` 태스크에서 늘 확인한다. 여기서 경로를 따로 받으면 제품에
 * 없는 자리를 레인이 새로 만드는 것이다.
 *
 * 제품 조립을 쓰면 [contextFor] 는 문서마다 **항상 `null`** 이다 — 실제 컨텍스트는 마스킹을
 * 마친 뒤에야 만들 수 있어 여기서 미리 뽑아 둘 수 없고, [ConvertDocumentUseCase] 가 마스킹
 * 직후 [contextSource] 포트로 물어야 나온다([DictionaryContextSource] KDoc 「인자가
 * MaskedText 인 것이 이 포트의 요점」). 파일 주입은 반대로 [contextSource] 가
 * [NoDictionaryContext] 다 — 문서마다 이미 뽑아 둔 문자열을
 * `ConvertDocumentUseCase.convert` 의 명시 인자로 싣기 때문이다(그 함수 KDoc 「명시 인자가
 * 이긴다」). 포트도 함께 실 소스로 두면 같은 문서에 두 경로가 동시에 값을 대는 자리가 생긴다.
 *
 * **본문은 어디에도 찍지 않는다.** [description] 이 남기는 것은 방식과 개수뿐이다
 * (CLAUDE.md 관측 규칙).
 */
internal class LaneDictionary private constructor(
    /** `null` 이면 파일 주입이 아니다 — off 이거나 제품 조립이다. */
    private val directory: Path?,
    private val contexts: Map<String, String>,
    private val documentCount: Int,
    /** `null` 이 아니면 제품 조립 모드다. */
    private val productSource: DictionaryContextSource? = null,
) {
    fun contextFor(documentId: String): String? = contexts[documentId]

    /**
     * `ConvertDocumentUseCase` 생성자에 넘길 포트. 제품 조립 모드에서만 실제 소스이고 그 외에는
     * [NoDictionaryContext] 다 — 클래스 KDoc 「두 방식」 절.
     */
    val contextSource: DictionaryContextSource
        get() = productSource ?: NoDictionaryContext

    /** 이 실행이 무엇을 실었는지 한 줄. 레인 요약의 측정 조건에 붙는다. */
    val description: String
        get() =
            when {
                productSource != null -> "dictContext=product"
                directory == null -> "dictContext=off"
                else -> "dictContext=${contexts.size}/$documentCount"
            }

    companion object {
        /** 문서별 컨텍스트 파일이 있는 디렉터리. 미설정이면 주입하지 않는다. */
        const val DIRECTORY_ENV: String = "EASYDOC_LANE_DICT_CONTEXT_DIR"

        /** 공백이 아닌 값이 있으면 제품 조립을 쓴다. 값 자체는 보지 않는다 — 존재만 본다. */
        const val PRODUCT_ENV: String = "EASYDOC_LANE_DICT_PRODUCT"

        /** 문서 id 하나에 파일 하나 — `<디렉터리>/<id>.txt`. */
        const val FILE_SUFFIX: String = ".txt"

        /**
         * [env] 가 준 환경으로 무엇을 실을지 정한다. [env] 를 인자로 받는 이유는
         * [GoldenLlmLane.plan] 과 같다 — 선택 규칙을 유료 호출 없이 시험하기 위해서다.
         *
         * [productAssembly] 는 [PRODUCT_ENV] 를 골랐을 때 실제로 조립을 부르는 자리다. 기본값은
         * 제품 조립 그대로([defaultProductAssembly])이고, 시험에서만 실패를 흉내 내려고 바꿔
         * 끼운다 — 이 매개변수가 인덱스 위치의 새 기본값이 되는 것은 아니다.
         *
         * 판정 순서:
         * ⑴ [DIRECTORY_ENV] 와 [PRODUCT_ENV] 를 **함께** 설정했으면 거절한다 — 파일 주입과
         * 제품 조립은 같은 자리를 채우는 서로 다른 방식이라, 하나가 조용히 이기면 다음 사람이
         * 리포트만 보고 무엇을 쟀는지 알 수 없다.
         * ⑵ 디렉터리만 설정했는데 없으면 거절한다(기존과 같다).
         * ⑶ 제품 조립을 요청했는데 색인을 읽을 수 없으면 거절한다 — 조용히 컨텍스트 없이
         * 베이스라인을 재고 A/B 를 쟀다고 적는 것을 막는다.
         */
        fun plan(
            env: (String) -> String?,
            documentIds: List<String>,
            productAssembly: () -> DictionaryContextSource = ::defaultProductAssembly,
        ): LaneDictionaryPlan {
            val directoryConfigured = env(DIRECTORY_ENV)?.takeIf(String::isNotBlank)
            val productConfigured = env(PRODUCT_ENV)?.takeIf(String::isNotBlank)

            return when {
                directoryConfigured != null && productConfigured != null -> bothConfigured()
                productConfigured != null -> planProduct(documentIds, productAssembly)
                directoryConfigured != null -> planDirectory(directoryConfigured, documentIds)
                else -> LaneDictionaryPlan.Ready(LaneDictionary(null, emptyMap(), documentIds.size))
            }
        }

        /** [DIRECTORY_ENV] 와 [PRODUCT_ENV] 를 함께 설정한 경우 — [plan] KDoc 판정 순서 ⑴. */
        private fun bothConfigured(): LaneDictionaryPlan.Unusable =
            LaneDictionaryPlan.Unusable(
                "$DIRECTORY_ENV 와 $PRODUCT_ENV 를 함께 설정할 수 없다 — 파일 주입과 제품 조립은 " +
                    "같은 자리를 채우는 서로 다른 방식이라 하나만 골라야 한다.",
            )

        /** 파일 주입 모드 — [plan] KDoc 판정 순서 ⑵. */
        private fun planDirectory(
            configured: String,
            documentIds: List<String>,
        ): LaneDictionaryPlan {
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
         * 제품 조립 모드 — [plan] KDoc 판정 순서 ⑶. 색인을 읽지 못하면 [IllegalStateException] 이
         * 온다(`DictionaryIndexJsonReader.readClasspathResource` 의 `error(...)`) — 잡아서
         * [LaneDictionaryPlan.Unusable] 로 접는다. 다른 예외는 여기서 삼키지 않는다 — 색인을
         * 읽지 못하는 것과는 다른 결의 오류이고, 삼키면 진짜 버그가 조용히 사라진다.
         */
        private fun planProduct(
            documentIds: List<String>,
            productAssembly: () -> DictionaryContextSource,
        ): LaneDictionaryPlan =
            try {
                LaneDictionaryPlan.Ready(
                    LaneDictionary(
                        directory = null,
                        contexts = emptyMap(),
                        documentCount = documentIds.size,
                        productSource = productAssembly(),
                    ),
                )
            } catch (exc: IllegalStateException) {
                LaneDictionaryPlan.Unusable(
                    "$PRODUCT_ENV 로 제품 조립을 요청했지만 사전 색인을 읽을 수 없다: ${exc.message} — " +
                        "이대로 돌면 컨텍스트 없이 베이스라인을 재고 A/B 를 쟀다고 적게 된다.",
                )
            }

        /**
         * 제품과 같은 조립. [DictionaryProperties] 를 기본값 그대로 넘긴다 — worker
         * `application.yml` 에 override 가 없어 배포 값도 코드 기본값과 같다
         * (`DictionaryProperties` KDoc 「enabled 의 기본값이 켜짐인 것은 정책이다」).
         */
        private fun defaultProductAssembly(): DictionaryContextSource =
            ConversionWorkerConfiguration().dictionaryContextSource(DictionaryProperties())

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
