package kr.easydoc.infrastructure.ingest

/**
 * **짝 없는 UTF-16 서로게이트를 `ToUnicode` 로 선언한 PDF** 를 즉석에서 만든다.
 *
 * ## 왜 이 fixture 가 있는가
 *
 * 계약 `x-stored-text-domain.applies_to` 가 파일 모드를 *"PDF 가 가장 그럴듯한 유입
 * 경로다 — 깨진 `ToUnicode` CMap 이 홀로 있는 상위 서로게이트를 그대로 내놓을 수 있다"*
 * 로 적고 그 팔을 `status: measured` 로 두었다. **그 주장을 실행으로 확인하려고 만들었다.**
 *
 * ## 실측 결과 — 그 주장은 오늘 조합에서 참이 아니다 (2026-08-20)
 *
 * PDFBox 3.0.5 는 이 PDF 에서 [DECLARED_UNICODE] 를 그대로 내지 않고
 * **[SUBSTITUTED_TEXT](U+FFFD)로 치환한다.** 추출 결과의 코드 포인트를 그대로 찍어
 * 확인했다(`["U+FFFD"]`). 즉 **파일 모드로는 저장 정의역 위반이 도달하지 않는다** —
 * 도달하는 것은 붙여넣기(JSON `\u` 이스케이프) 경로 하나뿐이다.
 *
 * 그래서 이 fixture 는 「위반을 만드는 입력」이 아니라 **「라이브러리가 치환한다」는 사실을
 * 붙들어 두는 회귀**다. PDFBox 판올림이 치환을 그만두면 `PdfExtractorTest` 의 케이스가
 * 빨개지고, 그때 파일 모드 팔이 실제로 열린다 — 계약의 `applies_to` 표식과 DC-24 의 그
 * 팔을 그 커밋에서 다시 판정해야 한다.
 *
 * ## 어떻게 만드는가
 *
 * `ToUnicode` CMap 의 `bfchar` 목적값을 **UTF-16BE 두 바이트 `D8 00`** 으로 둔다. 폰트는
 * 표준 14 폰트라 임베딩이 없다. 깨진 파일·폭탄을 커밋하지 않고 즉석 생성하는 것은 fixture
 * README 의 규약이고, 원본 `tests/ingest/` 도 같은 방식이다.
 *
 * ## `testFixtures` 에 두는 이유
 *
 * 두 모듈이 쓴다 — `infrastructure` 의 추출기 회귀와 `api` 의 계약 테스트. 두 벌로 만들면
 * 한쪽만 고쳐지는 날 두 테스트가 서로 다른 입력을 재게 된다.
 */
object SurrogatePdf {
    /** CMap 이 `A`(0x41)에 **대응시킨다고 선언한** 값 — 홀로 있는 상위 서로게이트. */
    const val DECLARED_UNICODE: String = "\uD800"

    /** PDFBox 3.0.5 가 실제로 내놓는 값. 이 상수의 존재 자체가 실측 기록이다. */
    const val SUBSTITUTED_TEXT: String = "\uFFFD"

    /**
     * 한 글자짜리 PDF 바이트. CMap 은 [DECLARED_UNICODE] 를 선언하지만 오늘 조합에서
     * 추출되는 값은 [SUBSTITUTED_TEXT] 다.
     *
     * `xref` 표를 정확히 적는다. 브루트포스 복구에 기대면 PDFBox 판올림에서 동작이 갈리고,
     * 그때 이 fixture 가 「손상 파일」로 분류돼 **다른 갈래를 재게 된다.**
     */
    fun bytes(): ByteArray {
        val objects =
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] " +
                    "/Resources << /Font << /F1 4 0 R >> >> /Contents 6 0 R >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /ToUnicode 5 0 R >>",
                streamObject(TO_UNICODE_CMAP),
                streamObject(PAGE_CONTENT),
            )
        return assemble(objects)
    }

    private fun streamObject(body: String): String =
        "<< /Length ${body.toByteArray(Charsets.ISO_8859_1).size} >>\nstream\n$body\nendstream"

    /** 객체들을 번호 순서로 이어 붙이고 `xref`·`trailer`·`startxref` 를 정확히 적는다. */
    private fun assemble(objects: List<String>): ByteArray {
        val builder = StringBuilder(HEADER)
        val offsets = mutableListOf<Int>()
        objects.forEachIndexed { index, body ->
            offsets += builder.toByteArray().size
            builder.append("${index + 1} 0 obj\n").append(body).append("\nendobj\n")
        }
        val xrefOffset = builder.toByteArray().size
        builder.append("xref\n0 ${objects.size + 1}\n")
        builder.append("0000000000 65535 f \n")
        offsets.forEach { offset ->
            builder.append(offset.toString().padStart(OFFSET_WIDTH, '0')).append(XREF_ENTRY_SUFFIX)
        }
        builder.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\n")
        builder.append("startxref\n$xrefOffset\n%%EOF\n")
        return builder.toByteArray()
    }

    private fun StringBuilder.toByteArray(): ByteArray = toString().toByteArray(Charsets.ISO_8859_1)

    private const val HEADER = "%PDF-1.4\n"

    /** `xref` 항목의 오프셋 자릿수. PDF 규격이 10자리로 못박았다. */
    private const val OFFSET_WIDTH = 10

    /** `xref` 항목 한 줄의 꼬리 — 세대 번호·사용 표시·**규격이 요구하는 공백**. */
    private const val XREF_ENTRY_SUFFIX = " 00000 n \n"

    /**
     * 목적값이 **두 바이트 `D8 00`** 이다 — 여기서 짝 없는 서로게이트가 태어난다.
     *
     * 나머지는 CMap 이 성립하기 위한 최소 골격이다(코드 공간 1바이트, `bfchar` 한 항목).
     */
    private val TO_UNICODE_CMAP =
        """
        /CIDInit /ProcSet findresource begin
        12 dict begin
        begincmap
        1 begincodespacerange
        <00> <FF>
        endcodespacerange
        1 beginbfchar
        <41> <D800>
        endbfchar
        endcmap
        CMapName currentdict /CMap defineresource pop
        end
        end
        """.trimIndent()

    /** 코드 0x41(`A`) 한 글자를 찍는다. 그 한 글자가 위 CMap 을 타고 서로게이트가 된다. */
    private val PAGE_CONTENT = "BT /F1 12 Tf 20 100 Td (A) Tj ET"
}
