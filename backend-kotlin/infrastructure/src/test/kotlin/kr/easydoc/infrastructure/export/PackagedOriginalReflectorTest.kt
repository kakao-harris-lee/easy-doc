package kr.easydoc.infrastructure.export

import kr.easydoc.application.document.OriginalDocument
import kr.easydoc.core.crypto.PlainBytes
import kr.easydoc.core.document.FormatPreservationStatus
import kr.easydoc.core.document.ReflectionPlacement
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.document.reflectedPreservation
import kr.easydoc.core.segment.SegmentConfidence
import kr.easydoc.core.segment.SegmentMap
import kr.easydoc.core.segment.SegmentUnit
import kr.easydoc.infrastructure.ingest.DocumentExtractors
import kr.easydoc.infrastructure.ingest.IngestFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 형식 디스패치와 **자리 순서** — 추출이 본 차례와 반영이 쓰는 차례가 같은가. */
class PackagedOriginalReflectorTest {
    private val reflector = PackagedOriginalReflector()
    private val extractors = DocumentExtractors()

    /** 머리말·꼬리말이 없는 fixture 들. 여기서는 추출 줄과 본문 단위가 **정확히** 같아야 한다. */
    private val plainFixtures =
        mapOf(
            "sample.docx" to SourceFormat.DOCX,
            "sample_table.docx" to SourceFormat.DOCX,
            "sample.hwpx" to SourceFormat.HWPX,
        )

    @Test
    @DisplayName("추출이 본 차례와 반영이 쓰는 차례가 자리마다 같다")
    fun `추출 순서와 반영 순서가 같다`() {
        plainFixtures.forEach { (name, format) ->
            val original = originalOf(name, format)
            val places = extractors.extract(name, original.bytes.value).text.split("\n")
            val marked = places.indices.map { "${it + 1}번 자리" }

            val file = reflector.reflect(original, "차례", marked.joinToString("\n"), map = null)!!

            assertThat(extractors.extract(file.filename, file.content).text.split("\n"))
                .withFailMessage(
                    "%s: 반영이 추출과 다른 차례로 썼다. 한 칸이라도 밀리면 검수본이 엉뚱한 문단 서식에 들어간다.",
                    name,
                ).isEqualTo(marked)
        }
    }

    @Test
    @DisplayName("자리가 정확히 맞는 원본은 `available` 로 판정된다")
    fun `짝이 맞으면 유지 가능이다`() {
        plainFixtures.forEach { (name, format) ->
            val original = originalOf(name, format)
            val body = extractors.extract(name, original.bytes.value).text

            val outcome = reflector.outline(original, body, map = null)!!

            assertThat(reflectedPreservation(outcome).status)
                .withFailMessage("%s: 원본과 문단 수가 같은데도 유지 가능이 아니다", name)
                .isEqualTo(FormatPreservationStatus.AVAILABLE)
        }
    }

    @Test
    @DisplayName("빈 줄은 문단으로 세지 않는다 — 판정과 반영이 같은 함수로 나눈다")
    fun `빈 줄은 자리를 차지하지 않는다`() {
        val original = originalOf("sample.docx", SourceFormat.DOCX)
        val body = "\n쉬운 제목\n\n\n쉬운 본문\n\n"

        val outcome = reflector.outline(original, body, map = null)!!
        val file = reflector.reflect(original, "안내", body, map = null)!!

        assertThat(reflectedPreservation(outcome).status).isEqualTo(FormatPreservationStatus.AVAILABLE)
        assertThat(extractors.extract(file.filename, file.content).text).isEqualTo("쉬운 제목\n쉬운 본문")
    }

    /**
     * **검수본 문단은 어느 갈래에서도 사라지지 않는다.**
     *
     * 두 형식의 머리말 자리가 달라서 같은 시험을 둘 다에 건다. DOCX 는 머리글 파트가 본문
     * 뒤라 겹치는 자리가 검수본의 끝줄이고, HWPX 는 머리말이 본문 사이에 들어가 겹치는 자리가
     * 가운데다. 어느 쪽이든 그 자리와 겹친 줄은 본문 끝으로 옮겨 붙고, 판정은 머리말이 있다는
     * 이유만으로도 `available` 이 아니다.
     */
    @Test
    @DisplayName("머리말이 있는 원본에서도 검수본 문단이 하나도 사라지지 않는다")
    fun `머리말이 있어도 검수본이 사라지지 않는다`() {
        headerFooterFixtures.forEach { (name, original) ->
            listOf(1, 5, 8, 12).forEach { count ->
                val lines = List(count) { "검수한 문단 ${it + 1}." }
                val body = lines.joinToString("\n")

                val outcome = reflector.outline(original, body, map = null)!!
                val file = reflector.reflect(original, "안내", body, map = null)!!

                val written = extractors.extract(file.filename, file.content).text
                assertThat(lines)
                    .withFailMessage(
                        "%s 에 %d 줄을 반영했더니 결과에 없는 검수본 문단이 있다. 검수한 문장이 소리 없이 사라진다.%n결과: %s",
                        name,
                        count,
                        written,
                    ).allMatch { line -> written.contains(line) }
                assertThat(reflectedPreservation(outcome).status)
                    .withFailMessage("%s: 머리말 문구가 원본으로 남는데 「그대로 나간다」고 말했다", name)
                    .isEqualTo(FormatPreservationStatus.PARTIAL)
            }
        }
    }

    @Test
    @DisplayName("PDF 원본은 반영하지 않는다 — 같은 형식으로 내보낼 수단이 없다")
    fun `pdf 는 반영하지 않는다`() {
        val original = originalOf("sample.pdf", SourceFormat.PDF)

        assertThat(reflector.outline(original, "쉬운 본문", map = null)).isNull()
        assertThat(reflector.reflect(original, "안내", "쉬운 본문", map = null)).isNull()
    }

    @Test
    @DisplayName("txt 원본은 반영하지 않는다 — 평문에는 반영할 원본 구조가 없다")
    fun `txt 는 반영하지 않는다`() {
        val original = OriginalDocument(SourceFormat.TXT, PlainBytes("안내문 본문".toByteArray(Charsets.UTF_8)))

        assertThat(reflector.outline(original, "쉬운 본문", map = null)).isNull()
        assertThat(reflector.reflect(original, "안내", "쉬운 본문", map = null)).isNull()
    }

    @Test
    @DisplayName("압축 예산을 넘는 원본은 열지 않는다 — 저장된 바이트에도 방어가 걸린다")
    fun `예산을 넘으면 열지 않는다`() {
        val bomb = originalOf("oversized.zip", SourceFormat.DOCX)

        assertThat(reflector.outline(bomb, "쉬운 본문", map = null)).isNull()
        assertThat(reflector.reflect(bomb, "안내", "쉬운 본문", map = null)).isNull()
    }

    /**
     * (A) `segment_map` 의 1:N 나눔 — 계획 §10.2 3항, §10.4 수용 기준 둘째 문단.
     *
     * 첫 줄은 원본 단위 자리에 갈아 끼우고, 나머지는 그 문단 **바로 뒤**에 같은 속성으로 끼워
     * 넣는다. 나눔은 `HIGH` 대응이라 그 자체로는 `available` 을 깨지 않는다 — 무슨 일이 있었는지는
     * details 하나로만 말한다.
     */
    @Test
    @DisplayName("(A) 1:N 나눔 — 첫 줄은 자리에 갈아 끼우고 나머지는 바로 뒤에 끼워 넣는다")
    fun `지도가 1대N 나눔을 자리 뒤에 끼워 넣는다`() {
        val original = originalOf("sample.docx", SourceFormat.DOCX)
        val body = "첫 문장 앞부분입니다.\n첫 문장 뒷부분입니다.\n둘째 문단입니다."
        val map =
            SegmentMap(
                sourceUnitCount = 2,
                easyUnitCount = 3,
                units =
                    listOf(
                        SegmentUnit(0, listOf(0), SegmentConfidence.HIGH),
                        SegmentUnit(1, listOf(0), SegmentConfidence.HIGH),
                        SegmentUnit(2, listOf(1), SegmentConfidence.HIGH),
                    ),
            )

        val outcome = reflector.outline(original, body, map)!!
        val file = reflector.reflect(original, "안내", body, map)!!

        assertThat(extractors.extract(file.filename, file.content).text)
            .describedAs("나뉜 둘째 줄이 첫 단위 문단 바로 뒤에 서고, 그 뒤 단위는 밀리지 않는다")
            .isEqualTo("첫 문장 앞부분입니다.\n첫 문장 뒷부분입니다.\n둘째 문단입니다.")
        assertThat(outcome.mergedUnits).isZero()
        assertThat(outcome.splitLines).isEqualTo(1)
        assertThat(outcome.placement).isEqualTo(ReflectionPlacement.MAPPED)
        assertThat(reflectedPreservation(outcome).status).isEqualTo(FormatPreservationStatus.AVAILABLE)
        assertThat(reflectedPreservation(outcome).details).hasSize(1)
    }

    /**
     * (B) `segment_map` 의 N:1 합침 — 계획 §10.2 3항, §10.4 수용 기준 첫째 문단.
     *
     * 합쳐진 문장은 첫 원본 단위에 쓰고, 나머지 원본 단위는 빈 문단으로 남는다 — `emptiedUnits`
     * 가 아니라 `mergedUnits` 로 센다(뒤 문단이 밀리지 않는다는 사실이 다르다).
     */
    @Test
    @DisplayName("(B) N:1 합침 — 첫 단위에 쓰고 나머지는 빈 문단으로 남는다")
    fun `지도가 N대1 합침을 첫 단위에 쓰고 나머지를 비운다`() {
        val original = originalOf("sample.docx", SourceFormat.DOCX)
        val body = "합쳐진 문장입니다."
        val map =
            SegmentMap(
                sourceUnitCount = 2,
                easyUnitCount = 1,
                units = listOf(SegmentUnit(0, listOf(0, 1), SegmentConfidence.HIGH)),
            )

        val outcome = reflector.outline(original, body, map)!!
        val file = reflector.reflect(original, "안내", body, map)!!

        assertThat(extractors.extract(file.filename, file.content).text)
            .describedAs("둘째 단위는 빈 문단이 되어 추출기가 걸러낸다 — 남는 것은 합쳐진 문장 하나뿐이다")
            .isEqualTo("합쳐진 문장입니다.")
        assertThat(outcome.mergedUnits).isEqualTo(1)
        assertThat(outcome.emptiedUnits).isZero()
        assertThat(outcome.splitLines).isZero()
        assertThat(outcome.placement).isEqualTo(ReflectionPlacement.MAPPED)
        assertThat(reflectedPreservation(outcome).status).isEqualTo(FormatPreservationStatus.AVAILABLE)
        assertThat(reflectedPreservation(outcome).details).hasSize(1)
    }

    /**
     * (C) `LOW` confidence 로 놓인 줄이 **실제로 자리를 옮겼을 때만** 세어진다(계획 §10.2 3항,
     * 2026-09-06 리뷰 F1 정정). 3 단위 / 2 줄 — easy0 은 [0,1] 을 `HIGH` 로 합치고, easy1 은
     * 원본 색인 2 를 `LOW` 로 받는다. `target(2) != easyUnitIndex(1)` 이라 차례 짝짓기와 다른
     * 자리로 옮겨졌으므로 면제되지 않고 `lowConfidenceLines` 로 세어 `partial` 이 된다.
     */
    @Test
    @DisplayName("(C) 차례와 다른 자리로 옮겨진 LOW 줄만 저확신으로 세어 partial 이다")
    fun `실제로 옮겨진 LOW 줄만 저확신으로 센다`() {
        val original = OriginalDocument(SourceFormat.DOCX, PlainBytes(ExportFixtures.threeParagraphDocx()))
        val body = "합쳐진 문장.\n쉬운 셋째 문단."
        val map =
            SegmentMap(
                sourceUnitCount = 3,
                easyUnitCount = 2,
                units =
                    listOf(
                        SegmentUnit(0, listOf(0, 1), SegmentConfidence.HIGH),
                        SegmentUnit(1, listOf(2), SegmentConfidence.LOW),
                    ),
            )

        val outcome = reflector.outline(original, body, map)!!
        val file = reflector.reflect(original, "안내", body, map)!!

        assertThat(extractors.extract(file.filename, file.content).text)
            .describedAs("둘째 단위는 합침으로 비워져 추출기가 걸러내고, 셋째 단위에 LOW 줄이 갈아 끼워진다")
            .isEqualTo("합쳐진 문장.\n쉬운 셋째 문단.")
        assertThat(outcome.mergedUnits).isEqualTo(1)
        assertThat(outcome.lowConfidenceLines)
            .describedAs("easy1 의 원본 색인(2)이 그 줄의 투영된 쉬운 글 색인(1)과 달라 차례 그대로가 아니다")
            .isEqualTo(1)
        assertThat(outcome.placement).isEqualTo(ReflectionPlacement.MAPPED)
        assertThat(reflectedPreservation(outcome).status).isEqualTo(FormatPreservationStatus.PARTIAL)
    }

    /**
     * (I) 앵커(사실)가 하나도 없는 문서 — `segment_map` 이 전부 `LOW` 로
     * 차례 그대로 짚어도(계획 §10.2 3항 면제 규칙, 2026-09-06 리뷰 F1) `available` 로 남는다.
     * 결론이 차례 짝짓기와 바이트 단위로 같은데 `partial` 로 낮추면 정보가 아니라 상수가 된다.
     */
    @Test
    @DisplayName("(I) 앵커 없는 동일 개수 DOCX 는 identity LOW 지도로도 available 로 남는다")
    fun `앵커 없는 동일 개수 문서는 available 로 남는다`() {
        val original = originalOf("sample.docx", SourceFormat.DOCX)
        val body = "쉬운 제목입니다.\n쉬운 본문입니다."
        val identityLowMap =
            SegmentMap(
                sourceUnitCount = 2,
                easyUnitCount = 2,
                units =
                    listOf(
                        SegmentUnit(0, listOf(0), SegmentConfidence.LOW),
                        SegmentUnit(1, listOf(1), SegmentConfidence.LOW),
                    ),
            )

        val outcome = reflector.outline(original, body, identityLowMap)!!

        assertThat(outcome.lowConfidenceLines).isZero()
        assertThat(outcome.placement).isEqualTo(ReflectionPlacement.MAPPED)
        assertThat(reflectedPreservation(outcome).status).isEqualTo(FormatPreservationStatus.AVAILABLE)
        assertThat(reflectedPreservation(outcome).details).isEmpty()
    }

    /**
     * (D) 전제 검사 실패(계획 §10.2 3항 첫 bullet) — `sourceUnitCount` 가 실제 단위 수와 다르면
     * 지도를 버리고 차례 짝짓기로 떨어진다. 결과는 오늘의(`map = null`) 결과와 **바이트까지** 같다.
     */
    @Test
    @DisplayName("(D) sourceUnitCount 가 실제 단위 수와 다르면 지도를 버리고 차례 짝짓기로 떨어진다")
    fun `단위 수가 다르면 차례 짝짓기로 떨어진다`() {
        val original = originalOf("sample.docx", SourceFormat.DOCX)
        val body = "쉬운 문단 하나.\n쉬운 문단 둘."
        val mismatched =
            SegmentMap(
                sourceUnitCount = 99,
                easyUnitCount = 2,
                units =
                    listOf(
                        SegmentUnit(0, listOf(0), SegmentConfidence.HIGH),
                        SegmentUnit(1, listOf(1), SegmentConfidence.HIGH),
                    ),
            )

        val ordinalOutcome = reflector.outline(original, body, map = null)!!
        val fallbackOutcome = reflector.outline(original, body, mismatched)!!
        val ordinalFile = reflector.reflect(original, "안내", body, map = null)!!
        val fallbackFile = reflector.reflect(original, "안내", body, mismatched)!!

        assertThat(fallbackOutcome.placement).isEqualTo(ReflectionPlacement.ORDINAL_FALLBACK)
        assertThat(fallbackOutcome.emptiedUnits).isEqualTo(ordinalOutcome.emptiedUnits)
        assertThat(fallbackOutcome.appendedLines).isEqualTo(ordinalOutcome.appendedLines)
        assertThat(fallbackOutcome.displacedLines).isEqualTo(ordinalOutcome.displacedLines)
        assertSameZipContent(fallbackFile.content, ordinalFile.content)
        assertThat(reflectedPreservation(fallbackOutcome).status).isEqualTo(FormatPreservationStatus.PARTIAL)
        assertThat(reflectedPreservation(fallbackOutcome).details).anyMatch { it.contains("차례대로 반영") }
    }

    /**
     * (G) 머리말이 본문 **사이**에 오는 HWPX — 계획 §10.2 3항. 지도 짝짓기도 머리말 자리를
     * 소비할 뿐 건너뛰지 않는다 — 건너뛰면 그 뒤 본문 전체가 한 칸씩 밀린다.
     */
    @Test
    @DisplayName("(G) 머리말이 본문 사이에 오는 HWPX 에서도 지도 짝짓기가 머리말 자리를 소비할 뿐 밀리지 않는다")
    fun `머리말이 본문 사이에 있어도 지도 짝짓기가 밀리지 않는다`() {
        val original = OriginalDocument(SourceFormat.HWPX, PlainBytes(ExportFixtures.richHwpx()))
        val lines = listOf("쉬운 머리말", "쉬운 첫 문단", "쉬운 셀 하나", "쉬운 셀 둘", "쉬운 표 뒤", "쉬운 둘째 구역")
        val body = lines.joinToString("\n")
        val map =
            SegmentMap(
                sourceUnitCount = 6,
                easyUnitCount = 6,
                units = lines.indices.map { SegmentUnit(it, listOf(it), SegmentConfidence.HIGH) },
            )

        val outcome = reflector.outline(original, body, map)!!
        val file = reflector.reflect(original, "안내", body, map)!!

        assertThat(outcome.placement).isEqualTo(ReflectionPlacement.MAPPED)
        assertThat(outcome.displacedLines).isEqualTo(1)
        assertThat(extractors.extract(file.filename, file.content).text)
            .describedAs("머리말 자리는 원본 그대로 남고, 본문 자리는 한 칸도 밀리지 않는다")
            .isEqualTo("머리말 문구\n쉬운 첫 문단\n쉬운 셀 하나\n쉬운 셀 둘\n쉬운 표 뒤\n쉬운 둘째 구역\n쉬운 머리말")
    }

    /**
     * (2026-09-06 리뷰 항목 4) 원본 색인이 범위를 벗어난 지도 — 개수(`sourceUnitCount`·
     * `easyUnitCount`)는 맞지만 `sourceUnitIndexes` 값이 실제 단위 범위를 벗어난다. 예외로
     * 내보내기 전체가 무너지는 대신 **조용히 폴백**해 오늘의(`map = null`) 결과와 바이트까지
     * 같아야 한다.
     */
    @Test
    @DisplayName("원본 색인이 범위를 벗어난 지도는 예외 대신 차례 짝짓기로 폴백한다")
    fun `원본 색인이 범위를 벗어나면 폴백한다`() {
        val original = originalOf("sample.docx", SourceFormat.DOCX)
        val body = "쉬운 문단 하나.\n쉬운 문단 둘."
        val outOfRange =
            SegmentMap(
                sourceUnitCount = 2,
                easyUnitCount = 2,
                units =
                    listOf(
                        SegmentUnit(0, listOf(5), SegmentConfidence.HIGH),
                        SegmentUnit(1, listOf(1), SegmentConfidence.HIGH),
                    ),
            )

        val ordinalOutcome = reflector.outline(original, body, map = null)!!
        val fallbackOutcome = reflector.outline(original, body, outOfRange)!!
        val ordinalFile = reflector.reflect(original, "안내", body, map = null)!!
        val fallbackFile = reflector.reflect(original, "안내", body, outOfRange)!!

        assertThat(fallbackOutcome.placement).isEqualTo(ReflectionPlacement.ORDINAL_FALLBACK)
        assertThat(fallbackOutcome.emptiedUnits).isEqualTo(ordinalOutcome.emptiedUnits)
        assertThat(fallbackOutcome.appendedLines).isEqualTo(ordinalOutcome.appendedLines)
        assertThat(fallbackOutcome.displacedLines).isEqualTo(ordinalOutcome.displacedLines)
        assertSameZipContent(fallbackFile.content, ordinalFile.content)
    }

    /**
     * (2026-09-06 리뷰 항목 2) HWPX 1:N 나눔 — 머리말(source 0)이 displaced 로 빠지는 동시에
     * 본문 unit 1(`첫 문단입니다.`)이 두 줄로 나뉜다. 머리말 대상과 나눔이 함께 있어도 자리가
     * 밀리지 않는지 확인한다.
     */
    @Test
    @DisplayName("HWPX 1:N 나눔 — 머리말 대상과 함께 있어도 나뉜 줄이 바로 뒤에 선다")
    fun `HWPX 에서도 1대N 나눔이 바로 뒤에 선다`() {
        val original = OriginalDocument(SourceFormat.HWPX, PlainBytes(ExportFixtures.richHwpx()))
        val lines =
            listOf("쉬운 머리말", "쉬운 첫 문단 앞", "쉬운 첫 문단 뒤", "쉬운 셀 하나", "쉬운 셀 둘", "쉬운 표 뒤", "쉬운 둘째 구역")
        val body = lines.joinToString("\n")
        val map =
            SegmentMap(
                sourceUnitCount = 6,
                easyUnitCount = 7,
                units =
                    listOf(
                        SegmentUnit(0, listOf(0), SegmentConfidence.HIGH),
                        SegmentUnit(1, listOf(1), SegmentConfidence.HIGH),
                        SegmentUnit(2, listOf(1), SegmentConfidence.HIGH),
                        SegmentUnit(3, listOf(2), SegmentConfidence.HIGH),
                        SegmentUnit(4, listOf(3), SegmentConfidence.HIGH),
                        SegmentUnit(5, listOf(4), SegmentConfidence.HIGH),
                        SegmentUnit(6, listOf(5), SegmentConfidence.HIGH),
                    ),
            )

        val outcome = reflector.outline(original, body, map)!!
        val file = reflector.reflect(original, "안내", body, map)!!

        assertThat(outcome.splitLines).isEqualTo(1)
        assertThat(outcome.displacedLines).isEqualTo(1)
        assertThat(extractors.extract(file.filename, file.content).text)
            .describedAs("나뉜 둘째 줄이 unit1 바로 뒤에 서고, 그 뒤 단위는 밀리지 않는다")
            .isEqualTo(
                "머리말 문구\n쉬운 첫 문단 앞\n쉬운 첫 문단 뒤\n쉬운 셀 하나\n쉬운 셀 둘\n쉬운 표 뒤\n" +
                    "쉬운 둘째 구역\n쉬운 머리말",
            )
    }

    /**
     * (2026-09-06 리뷰 항목 2) HWPX N:1 합침 — 표 셀 둘(source 2·3)이 한 줄로 합쳐진다.
     * 셀 하나는 갈아 끼우고 다른 셀은 빈 문단으로 남아, 표 구조 자체는 바뀌지 않는다.
     */
    @Test
    @DisplayName("HWPX N:1 합침 — 표 셀 둘이 한 줄로 합쳐져도 표 구조는 그대로다")
    fun `HWPX 표 셀도 N대1 합침으로 비워진다`() {
        val original = OriginalDocument(SourceFormat.HWPX, PlainBytes(ExportFixtures.richHwpx()))
        val lines = listOf("쉬운 머리말", "쉬운 첫 문단", "합쳐진 셀", "쉬운 표 뒤", "쉬운 둘째 구역")
        val body = lines.joinToString("\n")
        val map =
            SegmentMap(
                sourceUnitCount = 6,
                easyUnitCount = 5,
                units =
                    listOf(
                        SegmentUnit(0, listOf(0), SegmentConfidence.HIGH),
                        SegmentUnit(1, listOf(1), SegmentConfidence.HIGH),
                        SegmentUnit(2, listOf(2, 3), SegmentConfidence.HIGH),
                        SegmentUnit(3, listOf(4), SegmentConfidence.HIGH),
                        SegmentUnit(4, listOf(5), SegmentConfidence.HIGH),
                    ),
            )

        val outcome = reflector.outline(original, body, map)!!
        val file = reflector.reflect(original, "안내", body, map)!!

        assertThat(outcome.mergedUnits).isEqualTo(1)
        assertThat(extractors.extract(file.filename, file.content).text)
            .describedAs("합쳐진 셀 문구 하나만 남고, 짝 셀은 비워져 추출기가 걸러낸다")
            .isEqualTo("머리말 문구\n쉬운 첫 문단\n합쳐진 셀\n쉬운 표 뒤\n쉬운 둘째 구역\n쉬운 머리말")
        val section = IngestFixtures.entriesOf(file.content).getValue("Contents/section0.xml").decodeToString()
        assertThat(section).contains("hp:tbl")
        assertThat(countOf(section, "<hp:tc>"))
            .describedAs("표 셀 둘이 합쳐져도 셀 자체(<hp:tc>)는 지우지 않는다")
            .isEqualTo(2)
    }

    /**
     * (2026-09-06 리뷰 항목 2) HWPX 표 셀 안 1:N 나눔 — 셀 하나(source 2)가 두 줄로 나뉠 때
     * 새 문단이 **그 셀 안에** 끼워지는지(표 밖으로 새지 않는지) 원문 XML로 직접 확인한다.
     */
    @Test
    @DisplayName("HWPX 표 셀 안 1:N 나눔 — 새 문단이 같은 셀 안에 끼워진다")
    fun `HWPX 표 셀 안에서도 나뉜 줄이 셀 안에 선다`() {
        val original = OriginalDocument(SourceFormat.HWPX, PlainBytes(ExportFixtures.richHwpx()))
        val lines =
            listOf("쉬운 머리말", "쉬운 첫 문단", "쉬운 셀 하나 앞", "쉬운 셀 하나 뒤", "쉬운 셀 둘", "쉬운 표 뒤", "쉬운 둘째 구역")
        val body = lines.joinToString("\n")
        val map =
            SegmentMap(
                sourceUnitCount = 6,
                easyUnitCount = 7,
                units =
                    listOf(
                        SegmentUnit(0, listOf(0), SegmentConfidence.HIGH),
                        SegmentUnit(1, listOf(1), SegmentConfidence.HIGH),
                        SegmentUnit(2, listOf(2), SegmentConfidence.HIGH),
                        SegmentUnit(3, listOf(2), SegmentConfidence.HIGH),
                        SegmentUnit(4, listOf(3), SegmentConfidence.HIGH),
                        SegmentUnit(5, listOf(4), SegmentConfidence.HIGH),
                        SegmentUnit(6, listOf(5), SegmentConfidence.HIGH),
                    ),
            )

        val outcome = reflector.outline(original, body, map)!!
        val file = reflector.reflect(original, "안내", body, map)!!

        assertThat(outcome.splitLines).isEqualTo(1)
        assertThat(extractors.extract(file.filename, file.content).text)
            .isEqualTo(
                "머리말 문구\n쉬운 첫 문단\n쉬운 셀 하나 앞\n쉬운 셀 하나 뒤\n쉬운 셀 둘\n쉬운 표 뒤\n" +
                    "쉬운 둘째 구역\n쉬운 머리말",
            )

        val section = IngestFixtures.entriesOf(file.content).getValue("Contents/section0.xml").decodeToString()
        assertThat(countOf(section, "<hp:tc>"))
            .describedAs("나뉜 줄이 새 셀을 만들지 않는다 — 셀 수는 그대로 둘이다")
            .isEqualTo(2)
        val firstCell = section.substringAfter("<hp:tc>").substringBefore("</hp:tc>")
        assertThat(firstCell)
            .describedAs("나뉜 두 줄이 첫 번째 셀 **안에** 함께 들어간다 — 표 밖으로 새지 않는다")
            .contains("쉬운 셀 하나 앞")
            .contains("쉬운 셀 하나 뒤")
        val secondCell = section.substringAfterLast("<hp:tc>").substringBefore("</hp:tc>")
        assertThat(secondCell)
            .describedAs("둘째 셀에는 나뉜 줄이 섞여 들지 않는다")
            .doesNotContain("쉬운 셀 하나")
    }

    /**
     * (H) 빈 줄 투영(계획 §10.2 3항 전제 검사) — 본문 중간의 빈 줄은 `segment_map` 색인에는
     * 있지만 `exportContentLines` 는 세지 않는다. 투영이 그 차이를 흡수해 지도가 떨어지지 않는다.
     */
    @Test
    @DisplayName("(H) 본문 중간의 빈 줄이 있어도 지도가 떨어지지 않는다 — 빈 줄 투영")
    fun `본문 중간의 빈 줄이 있어도 지도로 짝짓는다`() {
        val original = originalOf("sample.docx", SourceFormat.DOCX)
        val body = "첫 문장.\n\n둘째 문장."
        val map =
            SegmentMap(
                sourceUnitCount = 2,
                easyUnitCount = 3,
                units =
                    listOf(
                        SegmentUnit(0, listOf(0), SegmentConfidence.HIGH),
                        SegmentUnit(1, emptyList(), SegmentConfidence.LOW),
                        SegmentUnit(2, listOf(1), SegmentConfidence.HIGH),
                    ),
            )

        val outcome = reflector.outline(original, body, map)!!
        val file = reflector.reflect(original, "안내", body, map)!!

        assertThat(outcome.placement).isEqualTo(ReflectionPlacement.MAPPED)
        assertThat(extractors.extract(file.filename, file.content).text).isEqualTo("첫 문장.\n둘째 문장.")
        assertThat(reflectedPreservation(outcome).status).isEqualTo(FormatPreservationStatus.AVAILABLE)
    }

    /** 머리말·꼬리말이 **있는** 원본. 두 형식의 머리말 자리가 다르다는 것이 여기 둘의 차이다. */
    private val headerFooterFixtures: Map<String, OriginalDocument> by lazy {
        mapOf(
            "sample_rich.docx" to
                OriginalDocument(SourceFormat.DOCX, PlainBytes(IngestFixtures.bytes("sample_rich.docx"))),
            "머리말이 본문 사이에 오는 hwpx" to
                OriginalDocument(SourceFormat.HWPX, PlainBytes(ExportFixtures.richHwpx())),
        )
    }

    private fun originalOf(
        name: String,
        format: SourceFormat,
    ): OriginalDocument = OriginalDocument(format, PlainBytes(IngestFixtures.bytes(name)))

    private fun countOf(
        haystack: String,
        needle: String,
    ): Int = haystack.split(needle).size - 1

    /**
     * 두 zip 산출물이 **같은 문서**인지 잰다 — 원문 바이트가 아니라 항목 차례와 항목별 내용으로.
     *
     * `reflect()` 두 번이 만든 zip 을 원문 바이트로 그대로 비교하면, 로컬/중앙 헤더에 찍히는
     * DOS 시각(2초 단위)이 호출 사이 경계를 넘을 때 항목마다 한 바이트씩 달라져 우연히 깨진다
     * (2026-09-06 CI 관찰). 판정 대상은 "지도가 없을 때와 같은 문서가 나오는가"이지 타임스탬프가
     * 아니므로, 항목 이름의 차례와 항목별 압축 해제 바이트로 재는 것이 맞는 잣대다.
     */
    private fun assertSameZipContent(
        actual: ByteArray,
        expected: ByteArray,
    ) {
        val actualEntries = IngestFixtures.entriesOf(actual)
        val expectedEntries = IngestFixtures.entriesOf(expected)
        assertThat(actualEntries.keys.toList())
            .describedAs("zip 항목 차례가 같아야 한다")
            .isEqualTo(expectedEntries.keys.toList())
        actualEntries.forEach { (name, bytes) ->
            assertThat(bytes)
                .describedAs("zip 항목 %s 의 바이트가 같아야 한다", name)
                .isEqualTo(expectedEntries.getValue(name))
        }
    }
}
