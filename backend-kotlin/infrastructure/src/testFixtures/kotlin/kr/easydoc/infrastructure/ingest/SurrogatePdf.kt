package kr.easydoc.infrastructure.ingest

/** **짝 없는 UTF-16 서로게이트를 `ToUnicode` 로 선언한 PDF** 를 즉석에서 만든다. */
object SurrogatePdf {
    /** CMap 이 `A`(0x41)에 **대응시킨다고 선언한** 값 — 홀로 있는 상위 서로게이트. */
    const val DECLARED_UNICODE: String = "\uD800"

    /** PDFBox 3.0.5 가 실제로 내놓는 값. 이 상수의 존재 자체가 실측 기록이다. */
    const val SUBSTITUTED_TEXT: String = "\uFFFD"

    /**
     * 한 글자짜리 PDF 바이트. CMap 은 [DECLARED_UNICODE] 를 선언하지만 오늘 조합에서
     * 추출되는 값은 [SUBSTITUTED_TEXT] 다.
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

    /** 목적값이 **두 바이트 `D8 00`** 이다 — 여기서 짝 없는 서로게이트가 태어난다. */
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
