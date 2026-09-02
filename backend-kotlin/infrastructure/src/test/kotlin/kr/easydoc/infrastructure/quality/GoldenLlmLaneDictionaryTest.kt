package kr.easydoc.infrastructure.quality

import kr.easydoc.application.conversion.NoDictionaryContext
import kr.easydoc.core.privacy.maskText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * 사전 컨텍스트 주입 조건을 **유료 호출 없이** 고정한다.
 *
 * 사전 있음/없음 A/B 는 두 실행이 실제로 다른 조건이었을 때만 뜻이 있다. 그 조건은 전부
 * 여기서 시험할 수 있고, 시험할 수 있어야 측정 전에 깨진 것을 안다([GoldenLlmLaneTest] 와 같은 방침).
 */
class GoldenLlmLaneDictionaryTest {
    @Test
    @DisplayName("환경변수가 없으면 주입하지 않는다")
    fun `미설정이면 off 다`() {
        val dictionary = ready(LaneDictionary.plan(env = { null }, documentIds = listOf("001", "002")))

        assertThat(dictionary.contextFor("001")).isNull()
        assertThat(dictionary.description).isEqualTo("dictContext=off")
    }

    @Test
    @DisplayName("설정한 디렉터리가 없으면 실패다 — 조용히 베이스라인을 재지 않는다")
    fun `없는 디렉터리는 거절한다`(
        @TempDir temp: Path,
    ) {
        val plan = LaneDictionary.plan(env(temp.resolve("없는-디렉터리")), listOf("001"))

        assertThat(plan).isInstanceOf(LaneDictionaryPlan.Unusable::class.java)
        assertThat((plan as LaneDictionaryPlan.Unusable).reason).contains(LaneDictionary.DIRECTORY_ENV)
    }

    @Test
    @DisplayName("문서마다 <id>.txt 를 싣고, 파일이 없는 문서는 세지 않는다")
    fun `있는 문서만 싣는다`(
        @TempDir temp: Path,
    ) {
        temp.resolve("001.txt").writeText(BODY)

        val dictionary = ready(LaneDictionary.plan(env(temp), listOf("001", "002")))

        assertThat(dictionary.contextFor("001")).isEqualTo(BODY)
        assertThat(dictionary.contextFor("002")).isNull()
        assertThat(dictionary.description).isEqualTo("dictContext=1/2")
    }

    @Test
    @DisplayName("빈 파일은 실린 것으로 세지 않는다 — 개수가 측정 조건을 거짓으로 말하게 된다")
    fun `빈 파일은 세지 않는다`(
        @TempDir temp: Path,
    ) {
        temp.resolve("001.txt").writeText("   \n")

        val dictionary = ready(LaneDictionary.plan(env(temp), listOf("001")))

        assertThat(dictionary.contextFor("001")).isNull()
        assertThat(dictionary.description).isEqualTo("dictContext=0/1")
    }

    @Test
    @DisplayName("측정 조건 요약에 컨텍스트 본문이 실리지 않는다")
    fun `본문을 찍지 않는다`(
        @TempDir temp: Path,
    ) {
        temp.resolve("001.txt").writeText(BODY)

        val dictionary = ready(LaneDictionary.plan(env(temp), listOf("001")))

        assertThat(dictionary.description).doesNotContain("금일")
    }

    @Test
    @DisplayName("제품 조립을 요청하면 문서별 문자열 대신 제품 포트를 쓴다")
    fun `제품 조립을 쓴다`() {
        val dictionary =
            ready(LaneDictionary.plan(env(LaneDictionary.PRODUCT_ENV to "1"), listOf("001", "002")))

        // 실제 컨텍스트는 마스킹 이후에만 나온다 — 문서별 자리는 늘 비어 있어야 그 자리로
        // ConvertDocumentUseCase 의 명시 인자가 아니라 포트가 값을 대게 된다.
        assertThat(dictionary.contextFor("001")).isNull()
        assertThat(dictionary.contextSource).isNotSameAs(NoDictionaryContext)
        assertThat(dictionary.description).isEqualTo("dictContext=product")
    }

    @Test
    @DisplayName("기본 제품 조립은 실제 사전 색인을 읽어 컨텍스트를 만든다")
    fun `기본 조립은 실제로 동작한다`() {
        val dictionary = ready(LaneDictionary.plan(env(LaneDictionary.PRODUCT_ENV to "1"), listOf("001")))

        val context = dictionary.contextSource.contextFor(maskText(WITH_TERMS).maskedText)

        assertThat(context).isNotNull
        assertThat(context).contains("구비서류")
    }

    @Test
    @DisplayName("파일 주입 모드에서는 포트가 NoDictionaryContext 다 — 문서별 문자열이 이중으로 실리지 않는다")
    fun `파일 주입 모드는 포트를 쓰지 않는다`(
        @TempDir temp: Path,
    ) {
        temp.resolve("001.txt").writeText(BODY)

        val dictionary = ready(LaneDictionary.plan(env(temp), listOf("001")))

        assertThat(dictionary.contextSource).isSameAs(NoDictionaryContext)
    }

    @Test
    @DisplayName("파일 주입과 제품 조립을 함께 설정하면 거절한다 — 서로 다른 방식을 하나로 정할 수 없다")
    fun `둘 다 설정하면 거절한다`(
        @TempDir temp: Path,
    ) {
        val plan =
            LaneDictionary.plan(
                env(LaneDictionary.DIRECTORY_ENV to temp.toString(), LaneDictionary.PRODUCT_ENV to "1"),
                listOf("001"),
            )

        assertThat(plan).isInstanceOf(LaneDictionaryPlan.Unusable::class.java)
        val reason = (plan as LaneDictionaryPlan.Unusable).reason
        assertThat(reason).contains(LaneDictionary.DIRECTORY_ENV)
        assertThat(reason).contains(LaneDictionary.PRODUCT_ENV)
    }

    @Test
    @DisplayName("제품 조립을 요청했는데 색인을 읽을 수 없으면 실패다 — 조용히 베이스라인을 재지 않는다")
    fun `색인을 읽을 수 없으면 거절한다`() {
        val plan =
            LaneDictionary.plan(
                env = env(LaneDictionary.PRODUCT_ENV to "1"),
                documentIds = listOf("001"),
                productAssembly = { error("사전 색인 리소스가 없다: /dictionary/easy_dict.index.json") },
            )

        assertThat(plan).isInstanceOf(LaneDictionaryPlan.Unusable::class.java)
        val reason = (plan as LaneDictionaryPlan.Unusable).reason
        assertThat(reason).contains(LaneDictionary.PRODUCT_ENV)
        assertThat(reason).contains("사전 색인 리소스가 없다")
    }

    private fun env(directory: Path): (String) -> String? =
        mapOf(LaneDictionary.DIRECTORY_ENV to directory.toString())::get

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs)::get

    private fun ready(plan: LaneDictionaryPlan): LaneDictionary {
        assertThat(plan).isInstanceOf(LaneDictionaryPlan.Ready::class.java)
        return (plan as LaneDictionaryPlan.Ready).dictionary
    }

    private companion object {
        const val BODY: String = "[문서 사전]\n- 금일: 오늘"

        /**
         * 사전 용어가 실제로 들어 있는 안내문. `DictionaryContextSourceTest.WITH_TERMS` 와 같은
         * 이유로 한 줄짜리를 쓰지 않는다 — 기본 정책의 `maxCharsRatio=1.0` 이 원문 길이를 그대로
         * 상한으로 삼아 짧은 문서는 매칭이 있어도 항목이 전부 잘려 나간다.
         */
        val WITH_TERMS: String =
            """
            차상위계층 지원 안내

            □ 신청 방법
             ○ 신청하실 때에는 구비서류를 지참하여 가까운 주민센터에 방문하여 주시기 바랍니다.
             ○ 제출한 서류의 사본은 반환하지 않으며, 수령 사실을 확인한 뒤 심사를 진행합니다.
             ○ 해당자께서는 신청 기간 안에 접수하여야 하며, 기한이 지나면 소급하여 적용되지 않습니다.

            □ 유의 사항
             ○ 소득인정액이 선정기준액을 넘으면 지원 대상에서 제외될 수 있습니다.
             ○ 신청서에 적은 내용이 사실과 다르면 지원금이 환수될 수 있으니 정확히 적어 주십시오.
             ○ 자세한 내용은 담당 부서로 문의하여 주시기 바랍니다.
            """.trimIndent()
    }
}
