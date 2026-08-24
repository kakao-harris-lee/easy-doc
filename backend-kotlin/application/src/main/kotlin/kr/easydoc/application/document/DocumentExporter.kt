package kr.easydoc.application.document

import kr.easydoc.core.easyread.ExportFile
import kr.easydoc.core.easyread.ExportFormat

/** 복원된 본문을 내려받을 파일 바이트로 만든다. zip 조립은 infrastructure 몫이다. */
fun interface DocumentExporter {
    fun export(
        title: String,
        body: String,
        format: ExportFormat,
    ): ExportFile
}
