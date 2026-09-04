package kr.easydoc.core.easyread

import kr.easydoc.core.document.SourceFormat
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** 내보내기 순수 로직 — 파일명 정제 · RFC 5987 헤더 · TXT 바이트. */
class ExportTest {
    @Nested
    @DisplayName("파일명 정제")
    inner class Filename {
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource(
            "'기초연금 신청 안내', '기초연금 신청 안내-쉬운글.txt'",
            "'../etc/passwd', 'etc passwd-쉬운글.txt'",
            "'보고서 \"최종\"', '보고서 최종-쉬운글.txt'",
            "'...보고서...', '보고서-쉬운글.txt'",
            "'a:b*c?d<e>f|g', 'a b c d e f g-쉬운글.txt'",
        )
        @DisplayName("경로 구분자·따옴표·예약 문자를 공백으로 바꾸고 공백을 접는다")
        fun `금지 문자를 걷어낸다`(
            title: String,
            expected: String,
        ) {
            assertThat(exportFilename(title, ExportFormat.TXT)).isEqualTo(expected)
        }

        @Test
        @DisplayName("금지 문자는 지우지 않고 공백으로 바꾼다 — 지우면 낱말이 붙는다")
        fun `낱말을 붙이지 않는다`() {
            assertThat(exportFilename("가/나", ExportFormat.TXT)).isEqualTo("가 나-쉬운글.txt")
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = ["///", "   ", "...", "\u0000\u001F"])
        @DisplayName("제목이 통째로 지워지면 기본 이름을 쓴다 — 여기에는 `-쉬운글` 을 겹쳐 붙이지 않는다")
        fun `빈 제목은 기본 이름이다`(title: String) {
            assertThat(exportFilename(title, ExportFormat.TXT))
                .describedAs("원본 제목에서 나온 이름이 아니라 구분할 원본 파일명이 없다 — `쉬운 글-쉬운글` 은 겹말이다")
                .isEqualTo("쉬운 글.txt")
        }

        @Test
        @DisplayName("**원본과 구분하는 `-쉬운글` 표식이 붙는다** — `DESIGN.md` §6.5")
        fun `원본과 구분하는 표식이 붙는다`() {
            assertThat(exportFilename("청년월세안내", ExportFormat.DOCX))
                .describedAs("원본 서식을 유지해 내보내면 형식도 이름도 원본과 닮는다 — 표식이 없으면 둘을 가릴 수 없다")
                .isEqualTo("청년월세안내-쉬운글.docx")
        }

        @Test
        @DisplayName("길이 상한은 문자 수로 센다 — 한글에서 바이트로 세면 사용자가 보는 길이와 어긋난다")
        fun `상한을 문자 수로 센다`() {
            val stem = exportFilename("가".repeat(200), ExportFormat.TXT).removeSuffix(".txt")

            assertThat(stem)
                .describedAs("상한은 표식을 **포함한** 길이다 — 제목 몫으로만 읽으면 이름이 표식만큼 길어진다")
                .hasSize(80)
            assertThat(stem).endsWith("-쉬운글")
        }

        @Test
        @DisplayName("자른 자리에 점이 남으면 한 번 더 깎는다 — 표식은 그 **뒤에** 붙어 잘리지 않는다")
        fun `자른 뒤에도 점을 남기지 않는다`() {
            val stem = exportFilename("가".repeat(76) + "." + "나".repeat(10), ExportFormat.TXT)

            assertThat(stem).isEqualTo("가".repeat(76) + "-쉬운글.txt")
        }

        @Test
        @DisplayName("형식이 확장자를 정한다")
        fun `형식별 확장자를 붙인다`() {
            assertThat(exportFilename("보고서", ExportFormat.DOCX)).isEqualTo("보고서-쉬운글.docx")
            assertThat(exportFilename("보고서", ExportFormat.HWPX)).isEqualTo("보고서-쉬운글.hwpx")
        }

        @Test
        @DisplayName("계약 enum 값만 형식으로 읽는다 — 대소문자를 바꾸거나 pdf 를 받지 않는다")
        fun `와이어 이름으로 형식을 읽는다`() {
            assertThat(ExportFormat.ofWireName("docx")).isEqualTo(ExportFormat.DOCX)
            assertThat(ExportFormat.ofWireName("txt")).isEqualTo(ExportFormat.TXT)
            assertThat(ExportFormat.ofWireName("hwpx")).isEqualTo(ExportFormat.HWPX)
            assertThat(ExportFormat.ofWireName("DOCX")).isNull()
            assertThat(ExportFormat.ofWireName("pdf")).isNull()
            assertThat(ExportFormat.ofWireName("hwp")).isNull()
        }

        @Test
        @DisplayName("보충 평면 문자를 서로게이트 한가운데서 자르지 않는다")
        fun `코드포인트로 자른다`() {
            val name = exportFilename("가" + "\uD83D\uDE00".repeat(100), ExportFormat.TXT)
            val stem = name.removeSuffix(".txt")

            assertThat(stem.codePointCount(0, stem.length))
                .describedAs("코드포인트 상한을 넘었다")
                .isLessThanOrEqualTo(80)
            assertThat(String(stem.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
                .describedAs("UTF-8 왕복에서 값이 바뀌었다 — 짝 없는 서로게이트의 증거다")
                .isEqualTo(stem)
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = ["\u0000", "\u001F", "\u007F", "\u0080", "\u009F"])
        @DisplayName("C0·DEL·C1 제어문자가 파일명에 남지 않는다")
        fun `제어문자가 남지 않는다`(control: String) {
            val name = exportFilename("제${control}목", ExportFormat.TXT)

            assertThat(name)
                .describedAs("보이지 않는 제어문자가 Content-Disposition 값으로 나간다")
                .doesNotContain(control)
        }
    }

    @Nested
    @DisplayName("Content-Disposition (RFC 5987)")
    inner class Disposition {
        @Test
        @DisplayName("한글 이름은 퍼센트 인코딩하고 ASCII 대체 이름을 함께 둔다")
        fun `한글 이름을 담는다`() {
            val header = contentDisposition("기초연금 신청 안내.txt")

            assertThat(header).isEqualTo(
                "attachment; filename=\"easy-read.txt\"; " +
                    "filename*=UTF-8''%EA%B8%B0%EC%B4%88%EC%97%B0%EA%B8%88%20" +
                    "%EC%8B%A0%EC%B2%AD%20%EC%95%88%EB%82%B4.txt",
            )
        }

        @Test
        @DisplayName("공백은 `+` 가 아니라 %20 이다 — form 인코딩과 다르다")
        fun `공백을 플러스로 쓰지 않는다`() {
            assertThat(contentDisposition("a b.txt")).contains("filename*=UTF-8''a%20b.txt")
            assertThat(contentDisposition("a b.txt")).doesNotContain("+")
        }

        @Test
        @DisplayName("unreserved 문자는 그대로 둔다")
        fun `안전 문자는 인코딩하지 않는다`() {
            assertThat(contentDisposition("a-b_c.d~e.txt")).contains("filename*=UTF-8''a-b_c.d~e.txt")
        }

        @Test
        @DisplayName("헤더 값은 latin-1 로 실려 나갈 수 있어야 한다")
        fun `헤더가 latin1 로 인코딩된다`() {
            val header = contentDisposition("기초연금.txt")

            assertThat(Charsets.ISO_8859_1.newEncoder().canEncode(header)).isTrue()
        }

        @Test
        @DisplayName("확장자가 없으면 대체 이름에도 붙이지 않는다")
        fun `확장자 없는 이름도 다룬다`() {
            assertThat(contentDisposition("보고서")).startsWith("attachment; filename=\"easy-read\";")
        }
    }

    @Nested
    @DisplayName("TXT 바이트")
    inner class Txt {
        @Test
        @DisplayName("BOM 을 붙이지 않는다")
        fun `BOM 이 없다`() {
            val file = renderTxt("제목", "본문")

            assertThat(file.content.take(3)).isNotEqualTo(listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
            assertThat(String(file.content, Charsets.UTF_8)).isEqualTo("본문")
        }

        @Test
        @DisplayName("제목을 본문에 얹지 않는다 — 파일명에만 쓴다")
        fun `제목은 본문에 없다`() {
            val file = renderTxt("제목입니다", "본문입니다")

            assertThat(String(file.content, Charsets.UTF_8)).isEqualTo("본문입니다")
            assertThat(file.filename).isEqualTo("제목입니다-쉬운글.txt")
        }

        @Test
        @DisplayName("제어문자만 빠지고 탭·개행은 남는다")
        fun `본문을 더 지우지 않는다`() {
            val file = renderTxt("제목", "한 줄.\u000A두 줄.\u0009끝\u0000")

            assertThat(String(file.content, Charsets.UTF_8)).isEqualTo("한 줄.\u000A두 줄.\u0009끝")
        }

        @Test
        @DisplayName("제목의 제어문자는 **지워진다** — 파일명 정제만 거칠 때와 다르다")
        fun `제목도 정규화한다`() {
            assertThat(renderTxt("제\u0000목", "본문").filename).isEqualTo("제목-쉬운글.txt")
            assertThat(exportFilename("제\u0000목", ExportFormat.TXT)).isEqualTo("제 목-쉬운글.txt")
        }

        @Test
        @DisplayName("미디어 타입이 charset 을 명시한다")
        fun `charset 을 명시한다`() {
            assertThat(renderTxt("제목", "본문").mediaType).isEqualTo("text/plain; charset=utf-8")
        }

        @Test
        @DisplayName("toString 이 본문을 찍지 않는다")
        fun `본문을 로그로 흘리지 않는다`() {
            val rendered = renderTxt("제목", "비밀 본문입니다").toString()

            assertThat(rendered).doesNotContain("비밀 본문입니다")
            assertThat(rendered).contains("바이트")
        }
    }

    /** `choicesFor` — PDF 재결정(2.6.0)의 정본. */
    @Nested
    @DisplayName("선택지 (2.6.0 PDF 재결정)")
    inner class Choices {
        @Test
        @DisplayName("`ofSource` 가 값을 내는 원본은 선택지가 없다 — 서버가 이미 정했다")
        fun `유도값이 있으면 선택지가 없다`() {
            SourceFormat.entries
                .filter { ExportFormat.ofSource(it) != null }
                .forEach { source ->
                    assertThat(ExportFormat.choicesFor(source))
                        .withFailMessage("%s 는 유도값이 있는데 선택지도 있다 — 고를 이유가 없다", source)
                        .isEmpty()
                }
        }

        @Test
        @DisplayName("PDF 는 DOCX·HWPX 중 하나를 고른다 — 유도값이 없는 유일한 갈래")
        fun `PDF 는 두 형식 중 하나다`() {
            assertThat(ExportFormat.ofSource(SourceFormat.PDF))
                .describedAs("PDF 는 여전히 유도값이 없다 — 서버가 하나로 정하지 않는다")
                .isNull()
            assertThat(ExportFormat.choicesFor(SourceFormat.PDF))
                .containsExactlyInAnyOrder(ExportFormat.DOCX, ExportFormat.HWPX)
        }

        @Test
        @DisplayName("모든 원본이 유도값 또는 선택지 중 **적어도 하나**를 낸다 — 어느 쪽도 없는 원본은 없다")
        fun `모든 원본에 처분이 있다`() {
            SourceFormat.entries.forEach { source ->
                val derived = ExportFormat.ofSource(source)
                val choices = ExportFormat.choicesFor(source)
                assertThat(derived != null || choices.isNotEmpty())
                    .withFailMessage("%s 가 유도값도 선택지도 없다 — 내보낼 방법이 없다", source)
                    .isTrue()
            }
        }
    }
}
