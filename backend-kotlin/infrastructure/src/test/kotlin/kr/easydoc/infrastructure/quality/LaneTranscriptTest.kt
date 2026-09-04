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
        val transcript = ready(LaneTranscript.plan(env = { null }, documentIds = listOf("001")))

        assertThat(transcript.enabled).isFalse()
        assertThat(transcript.description).isEqualTo("transcript=off")

        transcript.save("001", "본문")

        assertThat(temp.toFile().listFiles()).isEmpty()
    }

    @Test
    @DisplayName("off 면 문서 id 가 위험해도 검사하지 않는다 — 기존 동작과 같다")
    fun `off 면 id 검사도 하지 않는다`() {
        // 노브를 켜면 거절당할 id(경로 순회, 예약 이름 충돌) 지만, off 는 쓰지 않으므로
        // 안전 여부를 볼 이유가 없다 — 이 노브를 쓰지 않는 기존 골든 테스트가 이 문서 id
        // 문법으로 영향받으면 안 된다.
        val plan = LaneTranscript.plan(env = { null }, documentIds = listOf("../report", "conditions"))

        assertThat(plan).isInstanceOf(LaneTranscriptPlan.Ready::class.java)
    }

    @Test
    @DisplayName("설정하면 문서마다 <id>.txt 로 남긴다")
    fun `설정하면 문서별 파일이 생긴다`(
        @TempDir temp: Path,
    ) {
        val transcript = ready(LaneTranscript.plan(env(temp), listOf("001")))

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

        val transcript = ready(LaneTranscript.plan(env(target), listOf("001")))

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

        val plan = LaneTranscript.plan(env(blocked), listOf("001"))

        assertThat(plan).isInstanceOf(LaneTranscriptPlan.Unusable::class.java)
        assertThat((plan as LaneTranscriptPlan.Unusable).reason).contains(LaneTranscript.DIRECTORY_ENV)
    }

    @Test
    @DisplayName("경로 순회 문서 id 는 유료 호출을 시작하기 전에 거절한다")
    fun `경로 순회 id 는 거절한다`(
        @TempDir temp: Path,
    ) {
        // 원래 결함 재현 — "../report" 는 <디렉터리>/../report.txt 로 풀려 디렉터리 밖에 쓴다.
        val plan = LaneTranscript.plan(env(temp), listOf("001", "../report"))

        assertThat(plan).isInstanceOf(LaneTranscriptPlan.Unusable::class.java)
        val reason = (plan as LaneTranscriptPlan.Unusable).reason
        assertThat(reason).contains("../report")
        // 계획 단계에서 거절됐으니 아무 파일도(디렉터리 밖은 물론 안에도) 쓰지 않았어야 한다 —
        // "유료 호출 전에" 를 파일시스템으로 확인한다.
        assertThat(temp.toFile().listFiles()).isEmpty()
        assertThat(temp.resolveSibling("report.txt").exists()).isFalse()
    }

    @Test
    @DisplayName("conditions 와 충돌하는 문서 id 는 거절한다 — 측정 조건 파일을 덮어쓰면 안 된다")
    fun `conditions 와 충돌하는 id 는 거절한다`(
        @TempDir temp: Path,
    ) {
        val plan = LaneTranscript.plan(env(temp), listOf("001", "conditions"))

        assertThat(plan).isInstanceOf(LaneTranscriptPlan.Unusable::class.java)
        val reason = (plan as LaneTranscriptPlan.Unusable).reason
        assertThat(reason).contains("conditions")
        assertThat(reason).contains(LaneTranscript.CONDITIONS_FILE_NAME)
    }

    @Test
    @DisplayName("코퍼스 안에서 문서 id 가 중복되면 거절한다 — 나중 문서가 앞선 변환문을 조용히 덮어쓰면 안 된다")
    fun `중복 id 는 거절한다`(
        @TempDir temp: Path,
    ) {
        val plan = LaneTranscript.plan(env(temp), listOf("001", "002", "001"))

        assertThat(plan).isInstanceOf(LaneTranscriptPlan.Unusable::class.java)
        assertThat((plan as LaneTranscriptPlan.Unusable).reason).contains("001")
    }

    @Test
    @DisplayName("본문은 description 에 실리지 않는다")
    fun `본문을 찍지 않는다`(
        @TempDir temp: Path,
    ) {
        val transcript = ready(LaneTranscript.plan(env(temp), listOf("001")))

        transcript.save("001", BODY)

        assertThat(transcript.description).doesNotContain(BODY)
    }

    @Test
    @DisplayName("비어 있지 않은 디렉터리는 거절한다 — 이전 실행 변환문을 이번 결과로 오인하면 안 된다")
    fun `비어 있지 않으면 거절한다`(
        @TempDir temp: Path,
    ) {
        // 1차 실행이 남긴 것으로 볼 수 있는 파일 하나.
        temp.resolve("047.txt").writeText("이전 실행의 변환문")

        val plan = LaneTranscript.plan(env(temp), listOf("001"))

        assertThat(plan).isInstanceOf(LaneTranscriptPlan.Unusable::class.java)
        val reason = (plan as LaneTranscriptPlan.Unusable).reason
        assertThat(reason).contains(LaneTranscript.DIRECTORY_ENV)
        assertThat(reason).contains("비어 있지 않다")
    }

    @Test
    @DisplayName("runs 를 2 이상으로 주면 <id>-run<n>.txt 로 남긴다")
    fun `runs 2 이상이면 run 접미사를 붙인다`(
        @TempDir temp: Path,
    ) {
        val transcript = ready(LaneTranscript.plan(env(temp), listOf("001"), runs = 2))

        transcript.save("001", BODY, run = 1)
        transcript.save("001", "$BODY-2", run = 2)

        assertThat(temp.resolve("001-run1.txt").readText()).isEqualTo(BODY)
        assertThat(temp.resolve("001-run2.txt").readText()).isEqualTo("$BODY-2")
        assertThat(temp.resolve("001.txt").exists()).isFalse()
    }

    @Test
    @DisplayName("runs=1(기본)이면 접미사 없이 기존과 같은 파일명을 쓴다 — 반복 노브를 쓰지 않은 실행과 같게 보인다")
    fun `runs 1 이면 접미사가 없다`(
        @TempDir temp: Path,
    ) {
        val transcript = ready(LaneTranscript.plan(env(temp), listOf("001"), runs = 1))

        transcript.save("001", BODY)

        assertThat(temp.resolve("001.txt").readText()).isEqualTo(BODY)
        assertThat(temp.resolve("001-run1.txt").exists()).isFalse()
    }

    @Test
    @DisplayName("측정 조건을 conditions.txt 로 남긴다 — 본문은 담지 않는다")
    fun `조건 파일을 남긴다`(
        @TempDir temp: Path,
    ) {
        val transcript = ready(LaneTranscript.plan(env(temp), listOf("001")))
        val conditions = "provider=anthropic settings=stub · dictContext=off · transcript=$temp"

        transcript.writeConditions(conditions)
        transcript.save("001", BODY)

        val written = temp.resolve("conditions.txt")
        assertThat(written.readText()).isEqualTo(conditions)
        assertThat(written.readText()).doesNotContain(BODY)
    }

    @Test
    @DisplayName("off 면 조건 파일도 남기지 않는다")
    fun `off 면 조건 파일도 안 남긴다`(
        @TempDir temp: Path,
    ) {
        val transcript = ready(LaneTranscript.plan(env = { null }, documentIds = listOf("001")))

        transcript.writeConditions("provider=anthropic")

        assertThat(temp.toFile().listFiles()).isEmpty()
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
