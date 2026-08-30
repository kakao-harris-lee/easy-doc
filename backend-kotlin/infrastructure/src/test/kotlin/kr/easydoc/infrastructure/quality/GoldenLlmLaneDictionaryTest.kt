package kr.easydoc.infrastructure.quality

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

    private fun env(directory: Path): (String) -> String? =
        mapOf(LaneDictionary.DIRECTORY_ENV to directory.toString())::get

    private fun ready(plan: LaneDictionaryPlan): LaneDictionary {
        assertThat(plan).isInstanceOf(LaneDictionaryPlan.Ready::class.java)
        return (plan as LaneDictionaryPlan.Ready).dictionary
    }

    private companion object {
        const val BODY: String = "[문서 사전]\n- 금일: 오늘"
    }
}
