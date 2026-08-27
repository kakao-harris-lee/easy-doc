package kr.easydoc.infrastructure.export

import kr.easydoc.infrastructure.ingest.IngestFixtures

/**
 * 반영 테스트가 함께 쓰는 **머리말이 있는 합성 원본**.
 *
 * 두 형식의 머리말 자리가 다르다는 것이 여기 있는 이유다. DOCX 는 머리글 파트가 본문 **뒤**라
 * (`sample_rich.docx` 가 이미 그렇다) 머리말 자리가 검수본의 끝줄과 겹치지만, HWPX 는 머리말이
 * 첫 문단의 컨트롤로 들어가 본문 **사이**에 온다 — 자리 맞춤이 그 차이에서 갈리므로 두 형식을
 * 같은 시험으로 재려면 HWPX 쪽 fixture 가 필요하다.
 */
internal object ExportFixtures {
    /**
     * 머리말 컨트롤·표·두 구역을 담은 합성 HWPX. 개인정보가 없는 문장만 담는다.
     *
     * 추출 순서는 이렇다(`HwpxExtractor` 규칙): `머리말 문구` → `첫 문단입니다.` →
     * `표 셀 하나` → `표 셀 둘` → `표 뒤 문단입니다.` → `둘째 구역의 문장입니다.`
     */
    fun richHwpx(): ByteArray =
        IngestFixtures.withEntryReplaced(
            IngestFixtures.bytes("sample.hwpx"),
            "Contents/section0.xml",
            RICH_SECTION.toByteArray(Charsets.UTF_8),
        )

    /** 줄바꿈은 **태그 사이에만** 둔다 — 태그 안에서 끊으면 속성이 붙어 버린다. */
    private val RICH_SECTION =
        """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section" xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph">
        <hp:p id="1" paraPrIDRef="0" styleIDRef="0"><hp:run charPrIDRef="0"><hp:ctrl>
        <hp:header id="900"><hp:subList>
        <hp:p id="901" paraPrIDRef="0" styleIDRef="0"><hp:run charPrIDRef="0"><hp:t>머리말 문구</hp:t></hp:run></hp:p>
        </hp:subList></hp:header>
        </hp:ctrl></hp:run></hp:p>
        <hp:p id="2" paraPrIDRef="0" styleIDRef="0"><hp:run charPrIDRef="0"><hp:t>첫 문단입니다.</hp:t></hp:run></hp:p>
        <hp:p id="3" paraPrIDRef="0" styleIDRef="0"><hp:run charPrIDRef="0">
        <hp:tbl id="800" borderFillIDRef="1"><hp:tr>
        <hp:tc><hp:subList>
        <hp:p id="801" paraPrIDRef="0" styleIDRef="0"><hp:run charPrIDRef="0"><hp:t>표 셀 하나</hp:t></hp:run></hp:p>
        </hp:subList></hp:tc>
        <hp:tc><hp:subList>
        <hp:p id="802" paraPrIDRef="0" styleIDRef="0"><hp:run charPrIDRef="0"><hp:t>표 셀 둘</hp:t></hp:run></hp:p>
        </hp:subList></hp:tc>
        </hp:tr></hp:tbl></hp:run></hp:p>
        <hp:p id="4" paraPrIDRef="0" styleIDRef="0"><hp:run charPrIDRef="0"><hp:t>표 뒤 문단입니다.</hp:t></hp:run></hp:p>
        </hs:sec>
        """.trimIndent()
}
