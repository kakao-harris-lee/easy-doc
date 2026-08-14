package kr.easydoc.core.easyread

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * 내보내기 순수 로직 — 파일명 정제 · RFC 5987 헤더 · TXT 바이트.
 *
 * 검증 축이 셋이다.
 *
 * 1. **파일명이 파일 시스템을 벗어나지 않는다** — 경로 구분자·제어문자가 남으면 저장이
 *    실패하거나 디렉터리를 벗어난다.
 * 2. **헤더가 RFC 5987 로 해석된다** — 한글 이름이 깨지거나 헤더 인코딩 오류가 나면
 *    사용자는 내려받기 자체를 못 한다.
 * 3. **본문이 한 글자도 더 지워지지 않는다** — 제어문자만 빠진다. 과잉 제거는 조용하다.
 *
 * 제어문자는 소스에 리터럴로 적지 않는다 — 전부 `<E>`XXXX 다.
 */
class ExportTest {
    @Nested
    @DisplayName("파일명 정제")
    inner class Filename {
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource(
            "'기초연금 신청 안내', '기초연금 신청 안내.txt'",
            "'../etc/passwd', 'etc passwd.txt'",
            "'보고서 \"최종\"', '보고서 최종.txt'",
            "'...보고서...', '보고서.txt'",
            "'a:b*c?d<e>f|g', 'a b c d e f g.txt'",
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
            // `가/나` 가 `가나` 가 되면 원래 없던 낱말이 만들어진다.
            assertThat(exportFilename("가/나", ExportFormat.TXT)).isEqualTo("가 나.txt")
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = ["///", "   ", "...", "\u0000\u001F"])
        @DisplayName("제목이 통째로 지워지면 기본 이름을 쓴다")
        fun `빈 제목은 기본 이름이다`(title: String) {
            // 확장자만 남은 이름(`.txt`)은 숨김 파일이 된다.
            assertThat(exportFilename(title, ExportFormat.TXT)).isEqualTo("쉬운 글.txt")
        }

        @Test
        @DisplayName("길이 상한은 문자 수로 센다 — 한글에서 바이트로 세면 사용자가 보는 길이와 어긋난다")
        fun `상한을 문자 수로 센다`() {
            val stem = exportFilename("가".repeat(200), ExportFormat.TXT).removeSuffix(".txt")

            assertThat(stem).hasSize(80)
        }

        @Test
        @DisplayName("자른 자리에 점이 남으면 한 번 더 깎는다")
        fun `자른 뒤에도 점을 남기지 않는다`() {
            // 80번째 문자가 점이면 `name..txt` 가 되어 윈도우가 거부한다.
            val stem = exportFilename("가".repeat(79) + "." + "나".repeat(10), ExportFormat.TXT)

            assertThat(stem).isEqualTo("가".repeat(79) + ".txt")
        }

        @Test
        @DisplayName("형식이 확장자를 정한다")
        fun `형식별 확장자를 붙인다`() {
            assertThat(exportFilename("보고서", ExportFormat.DOCX)).isEqualTo("보고서.docx")
            assertThat(exportFilename("보고서", ExportFormat.HWPX)).isEqualTo("보고서.hwpx")
        }

        @Test
        @DisplayName("보충 평면 문자를 서로게이트 한가운데서 자르지 않는다")
        fun `코드포인트로 자른다`() {
            // 이모지는 UTF-16 두 칸이다. 코드 유닛으로 80을 세면 40번째 이모지가 반으로
            // 잘려 짝 없는 서로게이트가 파일명에 남는다 — 그 뒤 RFC 5987 인코딩이 그것을
            // UTF-8 로 바꾸면 대체 문자 바이트가 헤더로 나간다.
            // **홀수 자리에서 잘리게 만든다.** 이모지만 100개면 각 2칸이라 80칸이 정확히
            // 40개로 떨어져 쪼개지지 않는다 — 그 입력으로는 결함이 재현되지 않는다.
            // 앞에 BMP 문자 하나를 두어 경계를 서로게이트 쌍 한가운데로 민다.
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
            // `java.net.URLEncoder` 를 그대로 쓰면 여기서 `+` 가 나온다. RFC 5987 의
            // ext-value 는 form 인코딩이 아니다.
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
            // 이것이 깨지면 서버가 헤더를 쓰는 순간 예외이거나 이름이 깨진다.
            // 이 테스트가 없으면 "인코딩했다"는 주장만 남는다.
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

            // BOM(EF BB BF)이 붙으면 그 세 바이트가 본문 첫 글자 앞에 보이는 편집기가 있다.
            assertThat(file.content.take(3)).isNotEqualTo(listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
            assertThat(String(file.content, Charsets.UTF_8)).isEqualTo("본문")
        }

        @Test
        @DisplayName("제목을 본문에 얹지 않는다 — 파일명에만 쓴다")
        fun `제목은 본문에 없다`() {
            val file = renderTxt("제목입니다", "본문입니다")

            assertThat(String(file.content, Charsets.UTF_8)).isEqualTo("본문입니다")
            assertThat(file.filename).isEqualTo("제목입니다.txt")
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
            // 두 진입점의 동작이 다르고, 그 차이가 의도된 것이다.
            //   renderTxt      : stripControlChars 로 **지운 뒤** 파일명을 만든다 → "제목"
            //   exportFilename : 금지 문자를 **공백으로 바꾼다**              → "제 목"
            // 렌더링 경로가 먼저 지우는 이유는 docx·txt 가 같은 본문·같은 이름을 내야 하기
            // 때문이다(제어문자 제거를 진입부 한 곳에서 한다). parity fixture 의 파일명
            // 케이스는 `exportFilename` 을 직접 부르므로 공백 쪽을 기대한다.
            assertThat(renderTxt("제\u0000목", "본문").filename).isEqualTo("제목.txt")
            assertThat(exportFilename("제\u0000목", ExportFormat.TXT)).isEqualTo("제 목.txt")
        }

        @Test
        @DisplayName("미디어 타입이 charset 을 명시한다")
        fun `charset 을 명시한다`() {
            // 없으면 브라우저가 로캘 기본 인코딩으로 열어 한글이 깨진다.
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
}
