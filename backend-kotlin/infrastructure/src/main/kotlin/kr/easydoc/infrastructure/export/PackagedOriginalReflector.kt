package kr.easydoc.infrastructure.export

import kr.easydoc.application.document.OriginalDocument
import kr.easydoc.application.document.OriginalStructureReflector
import kr.easydoc.core.document.ReflectionOutcome
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.easyread.ExportFile
import kr.easydoc.core.easyread.exportContentLines
import kr.easydoc.infrastructure.ingest.ZipBudget

/**
 * 형식별 원본 반영. **판정과 내보내기가 같은 자리 맞춤을 지난다** — 두 팔이 [linesOf] 와
 * [planOf] 를 함께 쓰고, 그 사이에 규칙이 하나도 갈라지지 않는다.
 */
class PackagedOriginalReflector : OriginalStructureReflector {
    private val docx = DocxOriginalReflector()
    private val hwpx = HwpxOriginalReflector()

    override fun outline(
        original: OriginalDocument,
        body: String,
    ): ReflectionOutcome? = planOf(original, linesOf(body))?.outcome()

    override fun reflect(
        original: OriginalDocument,
        title: String,
        body: String,
    ): ExportFile? {
        val lines = linesOf(body)
        return when (original.format) {
            SourceFormat.DOCX -> guardedBudget(original) { docx.reflect(it, title, lines) }

            SourceFormat.HWPX -> guardedBudget(original) { hwpx.reflect(it, title, lines) }

            // 같은 형식으로 내보낼 수단이 없다(PDF) 또는 원본이 없다(붙여넣기).
            // 부르는 쪽이 이 갈래를 먼저 걸러야 한다 — 여기서 다른 형식으로 접지 않는다.
            SourceFormat.PDF, SourceFormat.TEXT -> null
        }
    }

    private fun planOf(
        original: OriginalDocument,
        lines: List<String>,
    ): ReflectionPlan? =
        when (original.format) {
            SourceFormat.DOCX -> guardedBudget(original) { docx.outline(it, lines) }
            SourceFormat.HWPX -> guardedBudget(original) { hwpx.outline(it, lines) }
            SourceFormat.PDF, SourceFormat.TEXT -> null
        }

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
