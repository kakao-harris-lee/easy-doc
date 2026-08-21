package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.util.zip.ZipInputStream

/** 압축 해제 예산 (`migration-safety-gate` I-10 검증 3). */
class ZipBudgetTest {
    @Test
    @DisplayName("예산을 넘는 아카이브를 거절한다 — 기제 시험 (oversized.zip)")
    fun `예산 초과 아카이브를 거절한다`() {
        assertThatThrownBy {
            ZipBudget.ensureWithinBudget(IngestFixtures.bytes("oversized.zip"), SourceFormat.HWPX)
        }.isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.uncompressedTooLarge(SourceFormat.HWPX))
    }

    @Test
    @DisplayName("선언 크기를 믿지 않는다 — 위조 아카이브가 예산 밖 바이트를 읽지 못하게 한다")
    fun `선언 크기를 신뢰하지 않는다`() {
        val forged = IngestFixtures.bytes("forged_size.zip")

        val declared = declaredSizes(forged)
        assertThat(declared)
            .withFailMessage("위조 fixture 의 선언 크기가 사라졌다 — 이 fixture 가 시험하려던 성질이 없다.")
            .isNotEmpty()
        assertThat(declared.values.sum())
            .withFailMessage("선언 합계가 예산 이상이다 — 그러면 선언만 봐도 걸러지므로 판별력이 없다.")
            .isLessThan(ZIP_UNCOMPRESSED_BUDGET_BYTES)

        assertThatThrownBy { ZipBudget.ensureWithinBudget(forged, SourceFormat.HWPX) }
            .isInstanceOf(DocumentExtractionException::class.java)
    }

    @Test
    @DisplayName("취약점 실증 — **방어 없는** 읽기는 선언(1KB)의 수천 배를 실제로 푼다")
    fun `경계 없는 읽기는 선언보다 훨씬 많이 푼다`() {
        val forged = IngestFixtures.bytes("forged_size.zip")
        val declaredTotal = declaredSizes(forged).values.sum()

        val actualTotal = unboundedInflatedBytes(forged)

        assertThat(actualTotal)
            .withFailMessage {
                "위조 fixture 가 실제로는 선언만큼만 풀린다(선언 $declaredTotal / 실제 $actualTotal).\n" +
                    "  그렇다면 이 fixture 는 더 이상 I-10 검증 3 의 실증물이 아니다 — 교체하라."
            }.isGreaterThan(declaredTotal * FORGERY_FACTOR)
    }

    @Test
    @DisplayName("예산 안의 정상 아카이브는 통과한다 — 0건을 훑고 통과하는 상태와 구분한다")
    fun `정상 아카이브는 통과한다`() {
        ZipBudget.ensureWithinBudget(IngestFixtures.bytes("sample.hwpx"), SourceFormat.HWPX)
        ZipBudget.ensureWithinBudget(IngestFixtures.bytes("sample.docx"), SourceFormat.DOCX)
    }

    @Test
    @DisplayName("zip 이 아닌 바이트는 손상으로 끊는다 — 우리 코드 버그로 위장되지 않는다")
    fun `zip 이 아니면 손상으로 끊는다`() {
        assertThatThrownBy { ZipBudget.ensureWithinBudget("zip 이 아니다".toByteArray(), SourceFormat.DOCX) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.broken(SourceFormat.DOCX))
    }

    @Test
    @DisplayName("이름으로 고른 항목만 예산 안에서 읽어 온다")
    fun `선택 항목만 읽는다`() {
        val archive =
            IngestFixtures.zipOf(
                mapOf(
                    "keep/a.xml" to "가".toByteArray(),
                    "drop/b.bin" to "나".toByteArray(),
                ),
            )

        val read = ZipBudget.readEntries(archive, SourceFormat.HWPX) { it.startsWith("keep/") }

        assertThat(read.keys).containsExactly("keep/a.xml")
        assertThat(read.getValue("keep/a.xml").decodeToString()).isEqualTo("가")
    }

    /** 중앙 디렉터리가 선언한 크기. 위조 여부를 재는 기준이다. */
    private fun declaredSizes(archive: ByteArray): Map<String, Long> {
        val sizes = LinkedHashMap<String, Long>()
        ZipFile
            .builder()
            .setSeekableByteChannel(SeekableInMemoryByteChannel(archive))
            .get()
            .use { zip ->
                val entries = zip.entries
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory) sizes[entry.name] = entry.size.coerceAtLeast(0)
                }
            }
        return sizes
    }

    /** 경계 없이 끝까지 푼 실제 바이트 수. */
    private fun unboundedInflatedBytes(archive: ByteArray): Long {
        var total = 0L
        runCatching {
            ZipInputStream(archive.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) total += drain(zip)
                    entry = zip.nextEntry
                }
            }
        }
        return total
    }

    /** 스트림을 끝까지 읽어 바이트 수만 센다. 경계도 상한도 두지 않는다. */
    private fun drain(stream: InputStream): Long {
        val buffer = ByteArray(PROBE_CHUNK_BYTES)
        var total = 0L
        runCatching {
            var read = stream.read(buffer)
            while (read > 0) {
                total += read
                read = stream.read(buffer)
            }
        }
        return total
    }

    private companion object {
        /** 실제/선언 비가 이 배수를 넘어야 "위조"라고 부를 수 있다. */
        const val FORGERY_FACTOR = 100L
        const val PROBE_CHUNK_BYTES = 64 * 1024
    }
}
