package kr.easydoc.api

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import kr.easydoc.api.support.CanaryProbe
import kr.easydoc.api.support.RETRO_CONTROL_MARKER
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 실패 메시지에 원시 카나리가 실리지 않는다 — `CanaryProbe.report()` 자체를 재는 장치. */
class CanaryProbeRedactionTest {
    @Test
    @DisplayName("인접한 다른 축의 카나리가 조각에 남지 않는다 (축을 넘는 누출)")
    fun `축을 넘는 누출이 없다`() {
        val probe = probe()
        probe.addCanary(BODY_AXIS, BODY)
        probe.addCanary(TOKEN_AXIS, TOKEN)

        log(probe, "Authorization: Bearer $TOKEN\r\n\r\n{\"text\":\"$BODY\"}")

        assertNoFragment(probe)
    }

    @Test
    @DisplayName("적중 뒤에 등록된 카나리도 조각에 남지 않는다 (시간축 누출)")
    fun `늦게 등록한 카나리도 조각에 남지 않는다`() {
        val probe = probe()

        probe.addCanary(BODY_AXIS, BODY)

        log(probe, "Authorization: Bearer $TOKEN\r\n\r\n{\"text\":\"$BODY\"}")

        probe.addCanary(TOKEN_AXIS, TOKEN)
        probe.rescanRetained()

        assertNoFragment(probe)
    }

    @Test
    @DisplayName("지목은 유지된다 — 로거·레벨·축이 메시지에 있고 축 표식이 값을 대신한다")
    fun `치환해도 지목은 남는다`() {
        val probe = probe()
        probe.addCanary(BODY_AXIS, BODY)
        probe.addCanary(TOKEN_AXIS, TOKEN)
        log(probe, "Authorization: Bearer $TOKEN\r\n\r\n{\"text\":\"$BODY\"}")

        val report = probe.report()
        assertThat(report)
            .withFailMessage("지목이 사라졌다 — 치환이 문맥까지 지웠다면 이 장치는 쓸모가 없다:%n%s", report)
            .contains(LOGGER_NAME)
            .contains(BODY_AXIS)
            .contains(TOKEN_AXIS)
            .contains("«$BODY_AXIS»")
            .contains("«$TOKEN_AXIS»")
    }

    @Test
    @DisplayName("조각을 읽은 뒤의 카나리 등록은 거절된다 — 불완전한 집합으로 렌더할 길을 막는다")
    fun `읽은 뒤에는 등록할 수 없다`() {
        val probe = probe()
        probe.addCanary(BODY_AXIS, BODY)
        log(probe, "{\"text\":\"$BODY\"}")
        probe.report()

        assertThat(runCatching { probe.addCanary(TOKEN_AXIS, TOKEN) }.exceptionOrNull())
            .withFailMessage(
                "조각을 읽은 뒤에도 카나리를 등록할 수 있다 — 「불완전한 집합으로 만든 조각」이 " +
                    "다시 가능해진다. 이 빗장이 성질을 구조로 세우는 부분이다.",
            ).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    @DisplayName("늦게 등록된 카나리가 보관분에 대해 적중을 낸다 — 소급 대조가 카나리 집합을 지난다")
    fun `늦게 등록해도 보관분에서 적중한다`() {
        val probe = probe()

        log(probe, "Authorization: Bearer $TOKEN")

        probe.addCanary(TOKEN_AXIS, TOKEN)
        probe.rescanRetained()

        assertThat(probe.hits())
            .withFailMessage(
                "늦게 등록한 축이 보관분에서 적중하지 않았다 — `sawRetroControl()` 류의 " +
                    "「루프가 돌았다」 통제로는 이 상태를 못 본다.",
            ).isNotEmpty()
        assertThat(probe.pendingRetroMatches()).isEmpty()
    }

    @Test
    @DisplayName("등록이 소급 대조 뒤로 밀리면 그 축이 미대조로 지목된다")
    fun `소급 대조 뒤의 등록은 미대조로 남는다`() {
        val probe = probe()
        log(probe, "Authorization: Bearer $TOKEN")

        probe.rescanRetained()
        probe.addCanary(TOKEN_AXIS, TOKEN)

        assertThat(probe.pendingRetroMatches())
            .withFailMessage(
                "등록을 소급 대조 뒤로 옮겼는데 아무 축도 미대조로 남지 않았다 — " +
                    "그 축은 조용히 안 재진다(이 세션의 세 번째 같은 결함).",
            ).anyMatch { it.contains(TOKEN_AXIS) }
    }

    @Test
    @DisplayName("소급 대조를 아예 건너뛰면 통제 축이 적중을 내지 못한다")
    fun `소급 대조를 건너뛰면 통제가 빨개진다`() {
        val probe = probe()
        log(probe, "late canary $CONTROL_VALUE emitted")
        probe.addControlCanary(CONTROL_AXIS, CONTROL_VALUE)

        assertThat(probe.controlHitAxes())
            .withFailMessage("소급 대조를 건너뛰었는데 통제 축이 적중했다 — 통제가 무엇을 재는지 흐려진다")
            .doesNotContain(CONTROL_AXIS)
        assertThat(probe.pendingRetroMatches()).isNotEmpty()
    }

    @Test
    @DisplayName("통제 적중은 유출로 세지 않는다 — 걸러서가 아니라 다른 집합이기 때문")
    fun `통제 적중은 유출 목록에 들어가지 않는다`() {
        val probe = probe()
        log(probe, "late canary $CONTROL_VALUE emitted")
        probe.addControlCanary(CONTROL_AXIS, CONTROL_VALUE)
        probe.rescanRetained()

        assertThat(probe.controlHitAxes())
            .withFailMessage("통제 축이 적중하지 않았다 — 이 케이스의 전제가 성립하지 않는다")
            .contains(CONTROL_AXIS)
        assertThat(probe.hits())
            .withFailMessage("통제 적중이 유출 지목에 섞였다 — 두 집합이 갈리지 않았다:%n%s", probe.report())
            .isEmpty()

        assertNoFragment(probe)
    }

    @Test
    @DisplayName("재고는 등록한 축을 유출·통제 양쪽 다 정확히 담는다")
    fun `재고가 등록한 축을 정확히 담는다`() {
        val probe = probe()
        probe.addCanary(BODY_AXIS, BODY)
        probe.addCanary(TOKEN_AXIS, TOKEN)
        probe.addControlCanary(CONTROL_AXIS, CONTROL_VALUE)

        assertThat(probe.registeredAxes())
            .withFailMessage("재고가 등록과 다르다 — 이 접근자가 정확하지 않으면 재고 핀이 무의미하다")
            .containsExactlyInAnyOrder("유출 $BODY_AXIS", "유출 $TOKEN_AXIS", "통제 $CONTROL_AXIS")
    }

    @Test
    @DisplayName("등록하지 않은 축은 재고에 없다 — 삭제를 핀이 잡을 수 있는 근거")
    fun `등록하지 않은 축은 재고에 없다`() {
        val probe = probe()
        probe.addCanary(BODY_AXIS, BODY)

        assertThat(probe.registeredAxes())
            .withFailMessage(
                "등록하지 않은 축이 재고에 있다 — 재고가 등록을 반영하지 않으면 " +
                    "「축이 지워졌다」를 핀이 볼 수 없다.",
            ).doesNotContain("유출 $TOKEN_AXIS")
        assertThat(probe.registeredAxes()).containsExactly("유출 $BODY_AXIS")
    }

    @Test
    @DisplayName("재고는 축 이름만 담고 needle 값은 담지 않는다")
    fun `재고에 값이 섞이지 않는다`() {
        val probe = probe()
        probe.addCanary(TOKEN_AXIS, TOKEN)
        probe.addControlCanary(CONTROL_AXIS, CONTROL_VALUE)

        val inventory = probe.registeredAxes().joinToString(" ")
        assertThat(inventory)
            .withFailMessage("재고 문자열에 needle 값이 섞였다 — 재고는 이름만 다뤄야 한다: %s", inventory)
            .doesNotContain(TOKEN)
            .doesNotContain(CONTROL_VALUE)
    }

    /**
     * 잔여 판정은 [CanaryProbe.residualCanaryFragments] 가 정본이다 — 여기서 다시 정의하면
     * 실제 도달 케이스와 갈린다. 이 케이스는 그 정본을 적대적 입력에 물려 재는 쪽이다.
     */
    private fun assertNoFragment(probe: CanaryProbe) {
        val residue = probe.residualCanaryFragments()
        assertThat(residue)
            .withFailMessage(
                "실패 메시지에 카나리 원문 조각이 남았다 — 일치한 자리 %d곳 중 앞 %d곳: %s%n" +
                    "조각 값은 일부러 찍지 않는다. 치환이 **자르기와 등록 양쪽보다 먼저**인지 보라.",
                residue.size,
                RESIDUE_SHOWN,
                residue.take(RESIDUE_SHOWN),
            ).isEmpty()
    }

    private fun probe(): CanaryProbe = CanaryProbe(RETRO_CONTROL_MARKER)

    private fun log(
        probe: CanaryProbe,
        message: String,
    ) {
        val context = LoggerContext()
        val logger = context.getLogger(LOGGER_NAME)
        logger.level = Level.TRACE
        logger.addAppender(probe)
        logger.trace(message)
        logger.detachAppender(probe)
        context.stop()
    }

    private companion object {
        const val LOGGER_NAME = "org.example.probe.RequestBuffer"
        const val BODY_AXIS = "본문"
        const val TOKEN_AXIS = "자격증명(액세스 토큰)"
        const val BODY = "CANARY-UNIT-BODY-5H1KP"
        const val CONTROL_AXIS = "소급 대조 통제"
        const val CONTROL_VALUE = "CANARY-UNIT-CONTROL-8T4JD"

        /** 실제 JWT 와 같은 모양·길이여야 「창에 통째로 안 들어온다」는 조건이 재현된다. */
        const val TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJleHAiOjE3ODcyMzQ4ODMsInN1YiI6ImRkYmI2ZGU2LTU3OTktNDc2OC04N2E4" +
                "LTg0ODI3NzFkYTUwYSIsInR5cCI6ImFjY2VzcyJ9.bH3FatoLEsih-XynssEk1NMiEFmcrACdrffrBT5EGBg"

        const val RESIDUE_SHOWN = 5
    }
}
