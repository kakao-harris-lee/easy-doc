package kr.easydoc.infrastructure.export

import kr.easydoc.core.easyread.ExportFile
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.core.easyread.exportFileOf
import kr.easydoc.core.text.stripControlChars
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry

/**
 * 복원된 본문을 최소 HWPX(OWPML) 패키지로 담는다.
 * 입력 추출기가 읽는 `Contents/sectionN.xml` 의 `p`/`t` 만 채운다.
 */
internal class HwpxPackageWriter {
    fun write(
        title: String,
        body: String,
    ): ExportFile = exportFileOf(title, ExportFormat.HWPX, packageBytes(sectionXml(stripControlChars(body))))

    private fun sectionXml(body: String): ByteArray {
        val paragraphs =
            exportParagraphs(body).joinToString("") { line ->
                "<hp:p><hp:run><hp:t>${escapeXml(line)}</hp:t></hp:run></hp:p>"
            }
        return (
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<hs:sec xmlns:hs="$SECTION_NS" xmlns:hp="$PARAGRAPH_NS">$paragraphs</hs:sec>"""
        ).toByteArray(StandardCharsets.UTF_8)
    }

    private fun packageBytes(section: ByteArray): ByteArray {
        val sink = ByteArrayOutputStream()
        ZipArchiveOutputStream(sink).use { zip ->
            zip.setEncoding(StandardCharsets.UTF_8.name())
            putStored(zip, MIMETYPE_NAME, MIMETYPE_BYTES)
            putDeflated(zip, "META-INF/container.xml", CONTAINER_XML)
            putDeflated(zip, "version.xml", VERSION_XML)
            putDeflated(zip, "Contents/content.hpf", CONTENT_HPF)
            putDeflated(zip, "Contents/section0.xml", section)
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
        entry.crc = crc32(bytes)
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

    private fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    private fun escapeXml(text: String): String =
        buildString(text.length) {
            text.forEach { ch ->
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(ch)
                }
            }
        }

    private companion object {
        const val SECTION_NS: String = "http://www.hancom.co.kr/hwpml/2011/section"
        const val PARAGRAPH_NS: String = "http://www.hancom.co.kr/hwpml/2011/paragraph"
        const val MIMETYPE_NAME: String = "mimetype"
        val MIMETYPE_BYTES: ByteArray = "application/hwp+zip".toByteArray(StandardCharsets.US_ASCII)
        val CONTAINER_XML: ByteArray =
            (
                """<?xml version="1.0" encoding="UTF-8"?>""" +
                    """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""" +
                    """<rootfiles>""" +
                    """<rootfile full-path="Contents/content.hpf" media-type="application/hwpml-package+xml"/>""" +
                    """</rootfiles></container>"""
            ).toByteArray(StandardCharsets.UTF_8)
        val VERSION_XML: ByteArray =
            (
                """<?xml version="1.0" encoding="UTF-8"?>""" +
                    """<hv:HCFVersion xmlns:hv="http://www.hancom.co.kr/hwpml/2011/version" """ +
                    """tagetApplication="WORDPROCESSOR" major="5" minor="1" micro="0" buildNumber="0"/>"""
            ).toByteArray(StandardCharsets.UTF_8)
        val CONTENT_HPF: ByteArray =
            (
                """<?xml version="1.0" encoding="UTF-8"?>""" +
                    """<opf:package xmlns:opf="http://www.idpf.org/2007/opf/" version="">""" +
                    """<opf:spine><opf:itemref idref="section0"/></opf:spine></opf:package>"""
            ).toByteArray(StandardCharsets.UTF_8)
    }
}
