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

/**
 * zip 컨테이너(docx·hwpx)의 **압축 해제 예산**.
 *
 * 원본: `app/ingest/extractors.py::_ensure_zip_within_budget`.
 *
 * ## 헤더의 선언 크기는 상한으로 쓸 수 없다 (`migration-safety-gate` I-10 검증 3)
 *
 * `ZipArchiveEntry.getSize()` 는 아카이브가 스스로 적은 값이라 **위조된다.** 선언을 1KB 로
 * 적어 둔 81KB 짜리 파일이 무제한 읽기 구현에서 힙 190MB 를 먹는다(fixture
 * `forged_size.zip`, Python 쪽 실측). 예외는 메모리를 다 쓴 **뒤에** 난다 — 거부되는 것과
 * 안전한 것은 다르다.
 *
 * 믿을 수 있는 것은 **우리가 남은 예산까지만 읽은 바이트**뿐이다. 그래서 모든 읽기가
 * `read(buf, 0, min(chunk, remaining + 1))` 형태다. `+1` 은 "예산을 넘었다"를 관측하기
 * 위한 한 바이트다.
 *
 * ## 왜 `java.util.zip.ZipInputStream` 이 아닌가 (spike S-6)
 *
 * 그쪽은 **로컬 헤더만** 본다. Python `infolist()`(중앙 디렉터리)와 순회 대상이 갈릴 수
 * 있고, 중앙 디렉터리와 로컬 헤더가 어긋난 아카이브에서 무엇을 읽는지가 구현에 좌우된다.
 * commons-compress `ZipFile` + [SeekableInMemoryByteChannel] 이 메모리 안에서 중앙
 * 디렉터리를 읽는 대응물이다.
 *
 * ## POI 자신의 방어와 겹치는 것이 아니다
 *
 * [PoiZipDefenses] 는 POI 가 스스로 압축을 풀 때의 backstop 이고, 이 예산은 **파서에
 * 넘기기 전** 단계다. python-docx 처럼 압축 해제를 스스로 하는 라이브러리에 넘기기 전에
 * 이 검사를 통과해야 한다 — 통과했다면 실제 해제량이 예산 안이라는 뜻이므로 이후 재파싱도
 * 안전하다.
 */
internal object ZipBudget {
    /**
     * 아카이브 전체를 예산 안에서 **실제로 풀어 보고** 초과하면 거부한다.
     *
     * 검사는 바이트 **수**만 세면 되므로 조각을 들고 있지 않는다.
     */
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

    /**
     * 이름이 [names] 에 맞는 항목을 **남은 예산을 나눠 쓰며** 읽는다.
     *
     * 항목별로 예산을 새로 주지 않는다 — 그러면 항목 100개짜리 아카이브가 예산의 100배를
     * 쓴다. 원본 `_read_hwpx_sections` 는 예산을 공유하되 항목 하나를 `read(budget + 1)`
     * **한 번에** 읽어, 구역 하나가 수십 MB 를 단번에 할당할 수 있었다. 여기서는 두 자리
     * 모두 청크로 읽는다(계획 §9 질문 ⑪ — 요구는 "실제 읽은 바이트로 센다"이지
     * "Python 과 같다"가 아니다).
     *
     * @return 항목 이름 → 내용. 순서는 [names] 판정이 아니라 호출자가 정렬한다.
     */
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

    /**
     * 아카이브를 열어 [block] 에 넘긴다. zip 계층의 실패만 좁혀 잡아 손상 파일로 바꾼다.
     *
     * 잡는 범위를 [IOException] · [IllegalArgumentException] 으로 좁히는 이유는 원본과 같다 —
     * 나머지(우리 코드 버그)는 500 으로 드러나야지 사용자 입력 탓으로 위장되면 안 된다.
     */
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
