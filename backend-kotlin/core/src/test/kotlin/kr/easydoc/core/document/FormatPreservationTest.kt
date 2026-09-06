package kr.easydoc.core.document

import kr.easydoc.core.easyread.exportContentLines
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 서식 유지 판정 — **개수만으로** 서는 규칙과, 그 문구가 본문을 담지 않는다는 규칙. */
class FormatPreservationTest {
    @Test
    @DisplayName("본문 단위와 문단 수가 정확히 같고 머리말이 없으면 `available` 이다")
    fun `짝이 정확히 맞으면 유지 가능이다`() {
        val judged = reflectedPreservation(outcome())

        assertThat(judged.status).isEqualTo(FormatPreservationStatus.AVAILABLE)
        assertThat(judged.details).isEmpty()
    }

    @Test
    @DisplayName("머리말·꼬리말이 있으면 그 문구가 원본으로 남으므로 `available` 이 아니다")
    fun `머리말이 있으면 일부 유지다`() {
        val judged = reflectedPreservation(outcome(headerFooterUnits = 2))

        assertThat(judged.status).isEqualTo(FormatPreservationStatus.PARTIAL)
        assertThat(judged.details).containsExactly("머리말·꼬리말 2곳은 원본 문구를 그대로 둡니다.")
    }

    @Test
    @DisplayName("검수본 문단이 모자라면 남은 원본 문단을 비운다고 말한다")
    fun `문단이 모자라면 비운다고 말한다`() {
        val judged = reflectedPreservation(outcome(emptiedUnits = 3))

        assertThat(judged.status).isEqualTo(FormatPreservationStatus.PARTIAL)
        assertThat(judged.details)
            .containsExactly(
                "원본 문단 3개는 반영할 내용이 없어 빈 문단으로 남습니다.",
                "문단 수가 원본과 달라 뒤쪽 문단의 서식이 밀릴 수 있습니다.",
            )
    }

    @Test
    @DisplayName("검수본 문단이 남으면 본문 끝에 덧붙는다고 말한다 — 버리지 않는다")
    fun `문단이 남으면 덧붙인다고 말한다`() {
        val judged = reflectedPreservation(outcome(headerFooterUnits = 1, appendedLines = 3))

        assertThat(judged.status).isEqualTo(FormatPreservationStatus.PARTIAL)
        assertThat(judged.details)
            .containsExactly(
                "머리말·꼬리말 1곳은 원본 문구를 그대로 둡니다.",
                "문단 3개는 원본에 자리가 없어 본문 끝에 덧붙습니다.",
                "문단 수가 원본과 달라 뒤쪽 문단의 서식이 밀릴 수 있습니다.",
            )
    }

    /**
     * 머리말 자리와 겹친 줄을 **말하지 않는 것**이 §6.5 가 금지한 조용한 거짓말이었다. 그 줄은
     * 이제 본문 끝으로 옮겨 붙고, 옮겼다는 사실이 판정 문구에 나온다.
     */
    @Test
    @DisplayName("머리말 자리와 겹친 문단은 옮겨 붙는다고 말한다 — 사라지지 않는다")
    fun `겹친 문단은 옮긴다고 말한다`() {
        val judged = reflectedPreservation(outcome(headerFooterUnits = 2, displacedLines = 2))

        assertThat(judged.status).isEqualTo(FormatPreservationStatus.PARTIAL)
        assertThat(judged.details)
            .containsExactly(
                "머리말·꼬리말 2곳은 원본 문구를 그대로 둡니다.",
                "머리말·꼬리말 자리와 겹친 문단 2개는 본문 끝으로 옮겨 붙습니다.",
            )
    }

    /**
     * 옮겨 붙은 문단은 자리를 **소비한 채** 줄만 끝으로 간다 — 원본 단위와 검수본 문단의 짝이
     * 한 칸도 밀리지 않으므로 「서식이 밀린다」고 말하면 그것이 도리어 틀린 말이 된다.
     */
    @Test
    @DisplayName("옮겨 붙은 문단만으로는 서식이 밀린다고 말하지 않는다")
    fun `옮김은 밀림이 아니다`() {
        val judged = reflectedPreservation(outcome(headerFooterUnits = 1, displacedLines = 1))

        assertThat(judged.details)
            .doesNotContain("문단 수가 원본과 달라 뒤쪽 문단의 서식이 밀릴 수 있습니다.")
    }

    @Test
    @DisplayName("판정 문구는 **개수만** 담는다 — 어떤 갈래도 문서 본문 조각을 담을 수 없다")
    fun `판정 문구가 본문을 담지 않는다`() {
        val judgments =
            listOf(
                noOriginalPreservation(),
                unreadableOriginalPreservation(),
                reflectedPreservation(outcome()),
                reflectedPreservation(outcome(headerFooterUnits = 2, appendedLines = 6)),
                reflectedPreservation(outcome(headerFooterUnits = 1, emptiedUnits = 8)),
                reflectedPreservation(outcome(headerFooterUnits = 4, displacedLines = 4)),
                reflectedPreservation(outcome(mergedUnits = 5)),
                reflectedPreservation(outcome(splitLines = 7)),
                reflectedPreservation(outcome(lowConfidenceLines = 9)),
                reflectedPreservation(outcome(placement = ReflectionPlacement.ORDINAL_FALLBACK)),
            )

        assertThat(judgments.flatMap { it.details })
            .allSatisfy { detail ->
                assertThat(detail)
                    .withFailMessage("판정 문구가 숫자와 고정 문장 밖의 값을 담았다: %s", detail)
                    .matches("""[가-힣·, ]*\d*[가-힣·, ]*(\d+[가-힣]+[가-힣·, ]*)*\.""")
            }
    }

    // --- S6: segment_map 을 쓴 짝짓기의 새 어휘(계획 §10.2 결정 4) — 전 조합 상태표. ---

    @Test
    @DisplayName("N:1 합침만 있으면 대응을 확신한 반영이라 `available` 이다 — 무슨 일이 있었는지는 말한다")
    fun `합침만 있으면 유지 가능이다`() {
        val judged = reflectedPreservation(outcome(mergedUnits = 2))

        assertThat(judged.status)
            .describedAs("2.16.0부터 available 에도 details 가 붙을 수 있다")
            .isEqualTo(FormatPreservationStatus.AVAILABLE)
        assertThat(judged.details).containsExactly("원본 문단 2개는 앞 문단과 합쳐져 빈 문단으로 남습니다.")
    }

    @Test
    @DisplayName("1:N 나눔만 있으면 대응을 확신한 반영이라 `available` 이다")
    fun `나눔만 있으면 유지 가능이다`() {
        val judged = reflectedPreservation(outcome(splitLines = 3))

        assertThat(judged.status).isEqualTo(FormatPreservationStatus.AVAILABLE)
        assertThat(judged.details).containsExactly("문단 3개는 원본 문단이 나뉘어 그 뒤에 새 문단으로 들어갑니다.")
    }

    @Test
    @DisplayName("합침과 나눔이 함께 있어도 `available` 이고 둘 다 말한다")
    fun `합침과 나눔이 함께여도 유지 가능이다`() {
        val judged = reflectedPreservation(outcome(mergedUnits = 1, splitLines = 2))

        assertThat(judged.status).isEqualTo(FormatPreservationStatus.AVAILABLE)
        assertThat(judged.details)
            .containsExactly(
                "원본 문단 1개는 앞 문단과 합쳐져 빈 문단으로 남습니다.",
                "문단 2개는 원본 문단이 나뉘어 그 뒤에 새 문단으로 들어갑니다.",
            )
    }

    @Test
    @DisplayName("LOW confidence 로 짐작한 자리가 있으면 `partial` 이다 — 자리가 짐작이다")
    fun `저확신 자리가 있으면 일부 유지다`() {
        val judged = reflectedPreservation(outcome(lowConfidenceLines = 4))

        assertThat(judged.status).isEqualTo(FormatPreservationStatus.PARTIAL)
        assertThat(judged.details).containsExactly("문단 4개는 원본 자리를 확신할 수 없어 앞뒤 비율로 자리를 옮겨 넣었습니다.")
    }

    @Test
    @DisplayName("지도 전제 검사가 어긋나 차례 폴백으로 떨어지면 `partial` 이고 밀릴 수 있다고 말한다")
    fun `차례 폴백이면 일부 유지고 밀림을 말한다`() {
        val judged = reflectedPreservation(outcome(placement = ReflectionPlacement.ORDINAL_FALLBACK))

        assertThat(judged.status).isEqualTo(FormatPreservationStatus.PARTIAL)
        assertThat(judged.details)
            .containsExactly(
                "원본 구조와 문단 수를 맞출 수 없어 차례대로 반영합니다.",
                "문단 수가 원본과 달라 뒤쪽 문단의 서식이 밀릴 수 있습니다.",
            )
    }

    @Test
    @DisplayName("합침·나눔이 폴백과 함께 있어도 밀림은 폴백 하나가 말한다 — 두 번 붙지 않는다")
    fun `폴백과 합침이 함께여도 밀림 문구는 하나다`() {
        val judged =
            reflectedPreservation(
                outcome(mergedUnits = 1, splitLines = 1, placement = ReflectionPlacement.ORDINAL_FALLBACK),
            )

        assertThat(judged.status).isEqualTo(FormatPreservationStatus.PARTIAL)
        assertThat(judged.details.count { it == "문단 수가 원본과 달라 뒤쪽 문단의 서식이 밀릴 수 있습니다." }).isEqualTo(1)
    }

    @Test
    @DisplayName("`MAPPED` 로 놓였으면 비움·덧붙임이 있어도 밀린다고 말하지 않는다")
    fun `지도로 놓이면 비움 덧붙임도 밀림이 아니다`() {
        val judged =
            reflectedPreservation(
                outcome(emptiedUnits = 1, appendedLines = 1, placement = ReflectionPlacement.MAPPED),
            )

        assertThat(judged.status).isEqualTo(FormatPreservationStatus.PARTIAL)
        assertThat(judged.details)
            .describedAs("MAPPED 는 지도가 자리를 직접 짚어 뒤 문단이 밀리지 않는다")
            .doesNotContain("문단 수가 원본과 달라 뒤쪽 문단의 서식이 밀릴 수 있습니다.")
    }

    @Test
    @DisplayName("머리말과 합침이 함께 있으면 합침은 available을 깨지 않아도 머리말 때문에 partial이다")
    fun `머리말과 합침이 함께면 일부 유지다`() {
        val judged = reflectedPreservation(outcome(headerFooterUnits = 1, mergedUnits = 2))

        assertThat(judged.status).isEqualTo(FormatPreservationStatus.PARTIAL)
        assertThat(judged.details)
            .containsExactly(
                "머리말·꼬리말 1곳은 원본 문구를 그대로 둡니다.",
                "원본 문단 2개는 앞 문단과 합쳐져 빈 문단으로 남습니다.",
            )
    }

    @Test
    @DisplayName("저확신과 합침이 함께 있으면 저확신 때문에 partial이다 — 합침 문구도 함께 붙는다")
    fun `저확신과 합침이 함께면 일부 유지다`() {
        val judged = reflectedPreservation(outcome(lowConfidenceLines = 1, mergedUnits = 2))

        assertThat(judged.status).isEqualTo(FormatPreservationStatus.PARTIAL)
        assertThat(judged.details)
            .containsExactly(
                "원본 문단 2개는 앞 문단과 합쳐져 빈 문단으로 남습니다.",
                "문단 1개는 원본 자리를 확신할 수 없어 앞뒤 비율로 자리를 옮겨 넣었습니다.",
            )
    }

    /** 상태표 여덟 갈래를 **전부** 채운 최대 조합 — details 순서를 통째로 고정한다. */
    @Test
    @DisplayName("모든 갈래가 함께 있으면 details 순서가 고정된 순서 그대로다")
    fun `모든 갈래가 함께면 순서가 고정된다`() {
        val judged =
            reflectedPreservation(
                outcome(
                    headerFooterUnits = 1,
                    emptiedUnits = 2,
                    appendedLines = 3,
                    displacedLines = 4,
                    mergedUnits = 5,
                    splitLines = 6,
                    lowConfidenceLines = 7,
                    placement = ReflectionPlacement.ORDINAL_FALLBACK,
                ),
            )

        assertThat(judged.status).isEqualTo(FormatPreservationStatus.PARTIAL)
        assertThat(judged.details)
            .containsExactly(
                "머리말·꼬리말 1곳은 원본 문구를 그대로 둡니다.",
                "머리말·꼬리말 자리와 겹친 문단 4개는 본문 끝으로 옮겨 붙습니다.",
                "원본 문단 2개는 반영할 내용이 없어 빈 문단으로 남습니다.",
                "문단 3개는 원본에 자리가 없어 본문 끝에 덧붙습니다.",
                "원본 문단 5개는 앞 문단과 합쳐져 빈 문단으로 남습니다.",
                "문단 6개는 원본 문단이 나뉘어 그 뒤에 새 문단으로 들어갑니다.",
                "문단 7개는 원본 자리를 확신할 수 없어 앞뒤 비율로 자리를 옮겨 넣었습니다.",
                "원본 구조와 문단 수를 맞출 수 없어 차례대로 반영합니다.",
                "문단 수가 원본과 달라 뒤쪽 문단의 서식이 밀릴 수 있습니다.",
            )
    }

    @Test
    @DisplayName("`toString` 은 상태와 개수만 남긴다 — 항목 문구는 로그에 넣지 않는다")
    fun `문자열 표현이 항목을 감춘다`() {
        val judged = reflectedPreservation(outcome(headerFooterUnits = 1, appendedLines = 3))

        assertThat(judged.toString()).isEqualTo("FormatPreservation(partial, 항목 3건)")
        judged.details.forEach { assertThat(judged.toString()).doesNotContain(it) }
    }

    @Test
    @DisplayName("열 수 없는 원본은 `failed` 이고 사유를 항목으로 준다")
    fun `열 수 없는 원본은 실패다`() {
        val judged = unreadableOriginalPreservation()

        assertThat(judged.status).isEqualTo(FormatPreservationStatus.FAILED)
        assertThat(judged.details).containsExactly("원본 파일을 열 수 없어 같은 형식으로 다시 만들 수 없습니다.")
    }

    @Test
    @DisplayName("판정이 세는 문단과 내보내기가 쓰는 문단은 같은 함수에서 나온다 — 빈 줄은 세지 않는다")
    fun `빈 줄은 문단이 아니다`() {
        val body = "첫 문단\n\n둘째 문단\n   \n셋째 문단\n"

        assertThat(exportContentLines(body))
            .describedAs("빈 줄을 문단으로 세면 원본과 짝이 맞는 문서가 `partial` 로 떨어진다")
            .containsExactly("첫 문단", "둘째 문단", "셋째 문단")
    }

    /**
     * 이 시험이 재는 것은 **갈래의 조합**이라 세지 않는 값은 0/`false` 다.
     *
     * `LongParameterList` 를 억제한다 — [ReflectionOutcome] 자체가 상태표 여덟 갈래를
     * 하나씩 세는 값 객체라(같은 클래스 KDoc), 그 상태표를 조합하는 시험 도우미도 같은
     * 수의 매개변수를 그대로 받아야 어느 조합을 세팅했는지 이름으로 드러난다.
     */
    @Suppress("LongParameterList")
    private fun outcome(
        headerFooterUnits: Int = 0,
        emptiedUnits: Int = 0,
        appendedLines: Int = 0,
        displacedLines: Int = 0,
        mergedUnits: Int = 0,
        splitLines: Int = 0,
        lowConfidenceLines: Int = 0,
        placement: ReflectionPlacement = ReflectionPlacement.ORDINAL,
    ): ReflectionOutcome =
        ReflectionOutcome(
            headerFooterUnits = headerFooterUnits,
            emptiedUnits = emptiedUnits,
            appendedLines = appendedLines,
            displacedLines = displacedLines,
            mergedUnits = mergedUnits,
            splitLines = splitLines,
            lowConfidenceLines = lowConfidenceLines,
            placement = placement,
        )
}
