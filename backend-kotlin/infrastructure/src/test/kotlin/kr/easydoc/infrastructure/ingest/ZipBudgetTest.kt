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

/**
 * 압축 해제 예산 (`migration-safety-gate` I-10 검증 3).
 *
 * ## 이 클래스가 재는 것은 「거부되는가」가 **아니다**
 *
 * fixture README 가 네 번 틀린 끝에 남긴 교훈이 그것이다 — 무제한으로 읽는 구현도 결국
 * 예외를 내지만, **예외는 메모리를 다 쓴 뒤에 난다.** 그래서 위조 크기 입력에서는
 * 「무엇이 거부되는가」가 아니라 **「무엇이 소모되는가」**를 묻는다.
 *
 * 그리고 취약점을 재는 프로브는 **방어를 갖고 있으면 안 된다** — 경계 읽기를 흉내 낸
 * 프로브로 재면 "아무도 안 다친다"는 결론이 나온다(그 프로브가 바로 시험 대상인 방어다).
 */
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

        // ⑴ 선언은 거짓이다. 이 전제가 깨지면 아래 판정이 무의미하다.
        val declared = declaredSizes(forged)
        assertThat(declared)
            .withFailMessage("위조 fixture 의 선언 크기가 사라졌다 — 이 fixture 가 시험하려던 성질이 없다.")
            .isNotEmpty()
        assertThat(declared.values.sum())
            .withFailMessage("선언 합계가 예산 이상이다 — 그러면 선언만 봐도 걸러지므로 판별력이 없다.")
            .isLessThan(ZIP_UNCOMPRESSED_BUDGET_BYTES)

        // ⑵ 그런데 실제 내용은 예산을 넘는다 → 예산 검사가 실제 바이트로 세야만 걸린다.
        //    (commons-compress 가 CRC/크기 불일치를 먼저 잡아 손상으로 끝날 수도 있다.
        //     둘 다 거부이며, 어느 쪽이든 **경계 밖 바이트를 읽지 않는다**는 것이 요구다.)
        assertThatThrownBy { ZipBudget.ensureWithinBudget(forged, SourceFormat.HWPX) }
            .isInstanceOf(DocumentExtractionException::class.java)
    }

    @Test
    @DisplayName("취약점 실증 — **방어 없는** 읽기는 선언(1KB)의 수천 배를 실제로 푼다")
    fun `경계 없는 읽기는 선언보다 훨씬 많이 푼다`() {
        val forged = IngestFixtures.bytes("forged_size.zip")
        val declaredTotal = declaredSizes(forged).values.sum()

        // 방어를 흉내 내지 않는 프로브다 — 경계 없이 끝까지 읽는다. 이 값이 선언과 크게
        // 갈리는 것이 곧 "선언 크기를 상한으로 쓰면 안 되는" 근거다.
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

    /**
     * 중앙 디렉터리가 **선언한** 크기. 위조 여부를 재는 기준이다.
     *
     * 내용을 풀지 않는다 — 푸는 순간 JDK `ZipInputStream` 이 크기 불일치로 던져(실측:
     * `invalid entry size (expected 1024 but got 83886080 bytes)`) 선언값을 읽어 올 수 없다.
     * 그 예외 자체가 위조의 증거이지만, 여기서 필요한 것은 **선언값** 그 자체다.
     */
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

    /**
     * **경계 없이** 끝까지 푼 실제 바이트 수.
     *
     * 방어를 흉내 내지 않는다 — 이 프로브가 경계 읽기를 하면 시험 대상인 방어를 프로브가
     * 갖게 되어 "아무도 안 다친다"는 거짓 결론이 나온다(fixture README 의 네 번째 오류).
     * 크기 불일치 예외는 **다 푼 뒤에** 나므로 그때까지 센 바이트가 곧 피해량이다.
     * 다만 이 테스트 자신이 OOM 으로 죽지 않도록 내용은 버리며 센다.
     */
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

    /**
     * 스트림을 끝까지 읽어 바이트 수만 센다. 경계도 상한도 두지 않는다.
     *
     * 실패를 **여기서** 잡는 이유: JDK `ZipInputStream` 은 선언 크기 불일치를 마지막
     * `read()` 에서 던진다(`invalid entry size (expected 1024 but got 83886080 bytes)`).
     * 밖에서 잡으면 그때까지 센 값이 함께 날아가 **피해량이 0 으로 보인다** — 실제로
     * 그렇게 됐다(첫 판이 "선언 1024 / 실제 0" 으로 빨개졌다).
     */
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
