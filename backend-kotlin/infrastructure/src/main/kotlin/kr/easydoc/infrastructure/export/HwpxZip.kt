package kr.easydoc.infrastructure.export

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel

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
