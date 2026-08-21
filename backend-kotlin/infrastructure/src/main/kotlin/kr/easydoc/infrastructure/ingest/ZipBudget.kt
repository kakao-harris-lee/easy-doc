package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

/** zip 컨테이너(docx·hwpx)의 **압축 해제 예산**. */
internal object ZipBudget {
    /** 아카이브 전체를 예산 안에서 **실제로 풀어 보고** 초과하면 거부한다. */
    fun ensureWithinBudget(
        data: ByteArray,
        format: SourceFormat,
    ) {
        var remaining = ZIP_UNCOMPRESSED_BUDGET_BYTES
        withArchive(data, format) { archive ->
            val entries = archive.entries
            while (entries.hasMoreElements() && remaining >= 0) {
                val entry: ZipArchiveEntry = entries.nextElement()
                if (entry.isDirectory) continue
                archive.getInputStream(entry).use { stream ->
                    remaining = countWithin(stream, remaining)
                }
            }
        }
        rejectIfOverBudget(remaining, data.size, format)
    }

    /** 이름이 [names] 에 맞는 항목을 **남은 예산을 나눠 쓰며** 읽는다. */
    fun readEntries(
        data: ByteArray,
        format: SourceFormat,
        names: (String) -> Boolean,
    ): Map<String, ByteArray> {
        var remaining = ZIP_UNCOMPRESSED_BUDGET_BYTES
        val contents = LinkedHashMap<String, ByteArray>()
        withArchive(data, format) { archive ->
            val entries = archive.entries
            while (entries.hasMoreElements() && remaining >= 0) {
                val entry: ZipArchiveEntry = entries.nextElement()
                if (entry.isDirectory || !names(entry.name)) continue
                val sink = ByteArrayOutputStream()
                archive.getInputStream(entry).use { stream ->
                    remaining = copyWithin(stream, remaining, sink)
                }
                contents[entry.name] = sink.toByteArray()
            }
        }
        rejectIfOverBudget(remaining, data.size, format)
        return contents
    }

    /** 아카이브를 열어 [block] 에 넘긴다. zip 계층의 실패만 좁혀 잡아 손상 파일로 바꾼다. */
    private fun withArchive(
        data: ByteArray,
        format: SourceFormat,
        block: (ZipFile) -> Unit,
    ) {
        try {
            ZipFile
                .builder()
                .setSeekableByteChannel(SeekableInMemoryByteChannel(data))
                .get()
                .use(block)
        } catch (exception: IOException) {
            throw broken(format, data.size, exception)
        } catch (exception: IllegalArgumentException) {
            throw broken(format, data.size, exception)
        }
    }

    /** 남은 예산까지만 읽으며 바이트 수를 센다. 내용을 들고 있지 않는다. */
    private fun countWithin(
        stream: InputStream,
        budget: Long,
    ): Long = consume(stream, budget) { _, _ -> }

    /** 남은 예산까지만 읽어 [sink] 에 옮긴다. */
    private fun copyWithin(
        stream: InputStream,
        budget: Long,
        sink: ByteArrayOutputStream,
    ): Long = consume(stream, budget) { buffer, read -> sink.write(buffer, 0, read) }

    private inline fun consume(
        stream: InputStream,
        budget: Long,
        handle: (ByteArray, Int) -> Unit,
    ): Long {
        var remaining = budget
        val buffer = ByteArray(ZIP_READ_CHUNK_BYTES)
        while (remaining >= 0) {
            // 예산 + 1 바이트까지만 읽는다. 넘긴 사실을 관측하는 데 한 바이트면 충분하고,
            // 그 이상 읽으면 검사 자체가 공격 표면이 된다.
            val limit = min(buffer.size.toLong(), remaining + 1).toInt()
            val read = stream.read(buffer, 0, limit)
            if (read <= 0) break
            handle(buffer, read)
            remaining -= read
        }
        return remaining
    }

    private fun rejectIfOverBudget(
        remaining: Long,
        uploadSize: Int,
        format: SourceFormat,
    ) {
        if (remaining >= 0) return
        ExtractionFailureLog.record(format, uploadSize, "uncompressed_too_large")
        throw DocumentExtractionException(ExtractionMessages.uncompressedTooLarge(format))
    }

    private fun broken(
        format: SourceFormat,
        uploadSize: Int,
        cause: Throwable,
    ): DocumentExtractionException {
        ExtractionFailureLog.recordCause(format, uploadSize, cause)
        return DocumentExtractionException(ExtractionMessages.broken(format))
    }
}
