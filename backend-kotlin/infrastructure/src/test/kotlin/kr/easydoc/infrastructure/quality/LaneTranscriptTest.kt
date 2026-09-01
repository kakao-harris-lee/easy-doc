package kr.easydoc.infrastructure.quality

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * 변환문 보존 노브의 선택 규칙을 **유료 호출 없이** 고정한다.
 *
 * [GoldenLlmLaneDictionaryTest] 와 같은 방침이다 — 이 규칙이 깨졌는지는 유료 호출을 시작하기
 * 전에 알아야 한다.
 */
class LaneTranscriptTest {
    @Test
    @DisplayName("환경변수가 없으면 off 다 — 아무 것도 쓰지 않는다")
    fun `미설정이면 off 다`(
        @TempDir temp: Path,
    ) {
        val transcript = ready(LaneTranscript.plan(env = { null }))

        assertThat(transcript.enabled).isFalse()
        assertThat(transcript.description).isEqualTo("transcript=off")

        transcript.save("001", "본문")

        assertThat(temp.toFile().listFiles()).isEmpty()
    }

    @Test
    @DisplayName("설정하면 문서마다 <id>.txt 로 남긴다")
    fun `설정하면 문서별 파일이 생긴다`(
        @TempDir temp: Path,
    ) {
        val transcript = ready(LaneTranscript.plan(env(temp)))

        assertThat(transcript.enabled).isTrue()
        assertThat(transcript.description).isEqualTo("transcript=$temp")

        transcript.save("001", BODY)

        val written = temp.resolve("001.txt")
        assertThat(written.exists()).isTrue()
        assertThat(written.readText()).isEqualTo(BODY)
    }

    @Test
    @DisplayName("없는 디렉터리는 만들어서 쓴다")
    fun `없는 디렉터리는 만든다`(
        @TempDir temp: Path,
    ) {
        val target = temp.resolve("새-디렉터리")

        val transcript = ready(LaneTranscript.plan(env(target)))

        assertThat(target.exists()).isTrue()
        transcript.save("001", BODY)
        assertThat(target.resolve("001.txt").readText()).isEqualTo(BODY)
    }

    @Test
    @DisplayName("쓸 수 없는 경로면 유료 호출을 시작하기 전에 거절한다")
    fun `쓸 수 없는 경로는 거절한다`(
        @TempDir temp: Path,
    ) {
        // 이미 파일이 있는 경로를 디렉터리로 지정하면 만들 수 없다 — 권한 조작 없이도
        // "쓸 수 없다" 를 이식성 있게 재현하는 방법이다.
        val blocked = temp.resolve("파일로-막힌-경로")
        blocked.writeText("이미 파일이다")

        val plan = LaneTranscript.plan(env(blocked))

        assertThat(plan).isInstanceOf(LaneTranscriptPlan.Unusable::class.java)
        assertThat((plan as LaneTranscriptPlan.Unusable).reason).contains(LaneTranscript.DIRECTORY_ENV)
    }

    @Test
    @DisplayName("본문은 description 에 실리지 않는다")
    fun `본문을 찍지 않는다`(
        @TempDir temp: Path,
    ) {
        val transcript = ready(LaneTranscript.plan(env(temp)))

        transcript.save("001", BODY)

        assertThat(transcript.description).doesNotContain(BODY)
    }

    private fun env(directory: Path): (String) -> String? =
        mapOf(LaneTranscript.DIRECTORY_ENV to directory.toString())::get

    private fun ready(plan: LaneTranscriptPlan): LaneTranscript {
        assertThat(plan).isInstanceOf(LaneTranscriptPlan.Ready::class.java)
        return (plan as LaneTranscriptPlan.Ready).transcript
    }

    private companion object {
        const val BODY: String = "쉬운 말로 바꾼 본문입니다."
    }
}
