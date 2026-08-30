package kr.easydoc.infrastructure.export

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry

/** HWPX zip 항목을 이름 → 바이트로 읽는다. 디렉터리는 건너뛴다. */
internal fun hwpxZipEntries(archive: ByteArray): LinkedHashMap<String, ByteArray> {
    val parts = LinkedHashMap<String, ByteArray>()
    openHwpxZip(archive).use { zip -> copyEntries(zip, parts) }
    return parts
}

private fun openHwpxZip(archive: ByteArray): ZipFile =
    ZipFile.builder().setSeekableByteChannel(SeekableInMemoryByteChannel(archive)).get()

private fun copyEntries(
    zip: ZipFile,
    parts: MutableMap<String, ByteArray>,
) {
    for (entry in zip.entries.toList()) copyFileEntry(zip, entry, parts)
}

private fun copyFileEntry(
    zip: ZipFile,
    entry: ZipArchiveEntry,
    parts: MutableMap<String, ByteArray>,
) {
    if (entry.isDirectory) return
    parts[entry.name] = zip.getInputStream(entry).use { it.readBytes() }
}

/** 개방형 컨테이너 규칙이 요구하는 첫 항목 이름. */
internal const val MIMETYPE_NAME: String = "mimetype"

/**
 * HWPX 항목들을 패키지 하나로 쓴다 — **`mimetype` 을 압축하지 않은 채 맨 앞에** 둔다.
 *
 * 개방형 HWPX/OCF 가 그 배치를 요구한다. 나머지 항목은 받은 순서 그대로 실린다: 원본을 고쳐
 * 쓸 때 순서가 바뀌면 「본문 말고는 손대지 않았다」가 파일 수준에서 거짓이 된다.
 */
internal fun hwpxPackageOf(parts: LinkedHashMap<String, ByteArray>): ByteArray {
    val mimetype = parts[MIMETYPE_NAME] ?: error("HWPX 패키지에 mimetype 이 없다")
    val sink = ByteArrayOutputStream()
    ZipArchiveOutputStream(sink).use { zip ->
        zip.setEncoding(StandardCharsets.UTF_8.name())
        putStored(zip, MIMETYPE_NAME, mimetype)
        parts.forEach { (name, bytes) -> if (name != MIMETYPE_NAME) putDeflated(zip, name, bytes) }
    }
    return sink.toByteArray()
}

private fun putStored(
    zip: ZipArchiveOutputStream,
    name: String,
    bytes: ByteArray,
) {
    val entry = ZipArchiveEntry(name)
    entry.method = ZipEntry.STORED
    entry.size = bytes.size.toLong()
    entry.crc = CRC32().apply { update(bytes) }.value
    zip.putArchiveEntry(entry)
    zip.write(bytes)
    zip.closeArchiveEntry()
}

private fun putDeflated(
    zip: ZipArchiveOutputStream,
    name: String,
    bytes: ByteArray,
) {
    zip.putArchiveEntry(ZipArchiveEntry(name))
    zip.write(bytes)
    zip.closeArchiveEntry()
}
