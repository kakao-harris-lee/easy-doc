package kr.easydoc.infrastructure.export

import kr.easydoc.application.document.OriginalDocument
import kr.easydoc.application.document.OriginalStructureReflector
import kr.easydoc.core.document.ReflectionOutcome
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.easyread.ExportFile
import kr.easydoc.core.easyread.exportContentLines
import kr.easydoc.core.segment.SegmentMap
import kr.easydoc.infrastructure.ingest.ZipBudget

/** 형식별 원본 반영. 판정과 내보내기가 같은 자리 맞춤과 줄 투영 규칙을 공유한다. */
class PackagedOriginalReflector : OriginalStructureReflector {
    private val docx = DocxOriginalReflector()
    private val hwpx = HwpxOriginalReflector()

    override fun outline(
        original: OriginalDocument,
        body: String,
        map: SegmentMap?,
    ): ReflectionOutcome? {
        val lines = linesOf(body)
        return planOf(original, lines, projectedMapOf(body, lines, map), map != null)?.outcome()
    }

    override fun reflect(
        original: OriginalDocument,
        title: String,
        body: String,
        map: SegmentMap?,
    ): ExportFile? {
        val lines = linesOf(body)
        val projected = projectedMapOf(body, lines, map)
        val mapAttempted = map != null
        return when (original.format) {
            SourceFormat.DOCX -> guardedBudget(original) { docx.reflect(it, title, lines, projected, mapAttempted) }

            SourceFormat.HWPX -> guardedBudget(original) { hwpx.reflect(it, title, lines, projected, mapAttempted) }

            // 원본 구조를 반영할 수 없는 형식은 별도 변환 없이 건너뛴다.
            SourceFormat.PDF, SourceFormat.TEXT, SourceFormat.TXT -> null
        }
    }

    private fun planOf(
        original: OriginalDocument,
        lines: List<String>,
        map: SegmentMap?,
        mapAttempted: Boolean,
    ): ReflectionPlan? =
        when (original.format) {
            SourceFormat.DOCX -> guardedBudget(original) { docx.outline(it, lines, map, mapAttempted) }
            SourceFormat.HWPX -> guardedBudget(original) { hwpx.outline(it, lines, map, mapAttempted) }
            SourceFormat.PDF, SourceFormat.TEXT, SourceFormat.TXT -> null
        }

    /** [map] 을 [lines] 색인에 맞춰 투영한다. */
    private fun projectedMapOf(
        body: String,
        lines: List<String>,
        map: SegmentMap?,
    ): SegmentMap? = map?.let { projectToContentLines(it, body, lines.size) }

    /**
     * 압축 해제 예산을 **다시** 건 뒤 원본을 연다.
     *
     * 업로드 때 한 번 지났지만 그것은 그때의 판정이다. 저장된 바이트를 여는 자리마다 예산을
     * 거는 것이 zip 폭탄을 「업로드 경로 하나만 막는 방어」로 두지 않는 방법이다.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun <T> guardedBudget(
        original: OriginalDocument,
        use: (ByteArray) -> T?,
    ): T? {
        val data = original.bytes.value
        return try {
            ZipBudget.ensureWithinBudget(data, original.format)
            use(data)
        } catch (cause: Exception) {
            // **사유를 로그에 적지 않는다** — 예외 메시지에 문서 조각이 실려 나올 수 있다.
            null
        }
    }

    /** 판정과 반영이 **같은 함수**로 문단을 센다. */
    private fun linesOf(body: String): List<String> = exportContentLines(body)
}
