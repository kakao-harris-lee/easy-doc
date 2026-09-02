package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import org.apache.poi.poifs.filesystem.DirectoryNode
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/** zip 이어야 할 자리에 **OLE2 복합 문서**가 온 이유를 가려낸다 (계획 §5 D-12). */
internal object Ole2Diagnosis {
    /** OLE2 복합 문서 매직. 암호가 걸린 OOXML 도, 구버전 `.doc`·`.hwp` 도 zip 이 아니라 OLE2 다. */
    private val OLE2_MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())

    private const val ENCRYPTED_STREAM_NAME = "EncryptedPackage"
    private const val WORD_STREAM_NAME = "WordDocument"
    private const val HWP_HEADER_STREAM_NAME = "FileHeader"

    /**
     * HWP 5.x(OLE2) `FileHeader` 스트림의 서명 — ASCII 문자열 `"HWP Document File"`을
     * 32바이트로(널 패딩) 담는다. 스트림 **이름**만으로는 판정에 부족하다 — 임베드된 개체도
     * 자기 이름의 스트림을 가지므로, 루트의 `FileHeader` 스트림 **내용**의 선두가 이 서명과
     * 같은지까지 확인해야 형식을 특정할 수 있다.
     *
     * 출처(둘 다 32바이트·같은 서명을 확인):
     * - 한글과컴퓨터 공개 명세 「한글 문서 파일 형식 5.0」 revision 1.3, 4.1절(FileHeader) —
     *   https://cdn.hancom.com/link/docs/한글문서파일형식_5.0_revision1.3.pdf
     * - `pyhwp`(오픈소스 HWP5 파서) `hwp5.filestructure`의
     *   `HWP5_SIGNATURE = b'HWP Document File' + b'\x00' * 15` —
     *   https://github.com/mete0r/pyhwp
     */
    private val HWP5_SIGNATURE = "HWP Document File".toByteArray(StandardCharsets.US_ASCII)

    /** 선두 매직으로 OLE2 인지 본다. */
    fun looksLikeOle2(data: ByteArray): Boolean {
        if (data.size < OLE2_MAGIC.size) return false
        return OLE2_MAGIC.indices.all { data[it] == OLE2_MAGIC[it] }
    }

    /**
     * 네 갈래로 가른 거절 예외를 만든다. 로그에는 사유 코드만 남는다.
     *
     * 전체 바이트를 훑는 부분 문자열 검색이 아니라 **OLE2 디렉터리를 파싱**해 **루트 레벨**
     * 항목만으로 가린다 — 워드 개체를 삽입해 담은 유효한 HWP 5.x 문서는 그 임베드 스트림도
     * `WordDocument`·`EncryptedPackage`라는 이름을 가질 수 있어, 원시 바이트 스캔은 루트
     * 항목과 임베드 항목을 구분하지 못하고 오판한다(예: 그런 HWP 를 "docx 로 저장"하라고
     * 잘못 안내한다). 가장 구체적인 형식(HWP)을 먼저 확인한다.
     */
    fun rejection(
        data: ByteArray,
        format: SourceFormat,
    ): DocumentExtractionException {
        val (reason, message) = classify(data)
        ExtractionFailureLog.record(format, data.size, reason)
        return DocumentExtractionException(message)
    }

    /**
     * POIFS 로 디렉터리를 연다. POIFS 는 스트림 전체를 메모리에 올려 구조를 읽는데, 그 입력은
     * 업로드 상한으로 이미 크기가 제한돼 있다. 디렉터리를 열지 못하거나(OLE2 매직 뒤가
     * 손상됐거나) 대상 스트림을 읽지 못하면 [UNKNOWN_OLE2] 로 떨어진다.
     *
     * POI 가 실패를 항상 체크 예외로 알리지는 않는다 — `IOException`(POIFS 파싱·스트림 읽기
     * 실패의 공통 상위 타입, `NotOLE2FileException` 포함)뿐 아니라, 조작된 헤더 값에서
     * 계산한 크기·색인이 말이 안 될 때는 **비검사(unchecked)** 예외도 던진다. 예: BAT(FAT)
     * 섹터 수가 실제 파일 크기로는 불가능한 값이면 `IllegalArgumentException`("Unable read a
     * >2gb file via an InputStream")을, 루트 디렉터리 속성을 해석하지 못하면
     * `IllegalStateException`("Invalid format, cannot convert property ... to RootProperty")을
     * 던진다(둘 다 POI 5.4.1 실물을 사전 프로브로 확인 — 앞의 예는 `Ole2ContainerFixtures.
     * corruptedBatSectorCount` 가 만드는 픽스처가 그대로 재현한다).
     *
     * `IllegalArgumentException`·`IllegalStateException` 은 보통 우리 코드의 **프로그래밍
     * 오류**를 뜻해 잡지 않는 것이 원칙이다(CLAUDE.md). 그러나 여기서는 우리 코드가 아니라
     * **POI 가 신뢰할 수 없는 입력(사용자가 올린 바이트)을 파싱하며** 던지는 것이고, 그
     * 입력이 이미 OLE2 매직으로 형식이 확정된 뒤 이 함수 안에서만 열린다 — 그래서 이 두
     * 예외를 이 지점에서만, POIFS 생성과 루트 항목 조회 범위로 좁혀 잡는다(우리 자신의
     * `when` 분기 로직은 이 try 블록 밖의 코드가 아니라 안에 있지만, 그 분기는 문자열 상수를
     * 고르기만 할 뿐 실패할 수 있는 연산이 없다 — 실질적으로 예외가 날 수 있는 지점은 POIFS
     * 호출뿐이다). `Throwable` 은 여전히 넓게 잡지 않는다.
     */
    private fun classify(data: ByteArray): Pair<String, String> =
        try {
            POIFSFileSystem(ByteArrayInputStream(data)).use { fs ->
                val root = fs.root
                when {
                    isHwp5(root) -> "legacy_hwp_document" to ExtractionMessages.LEGACY_HWP
                    root.hasEntry(ENCRYPTED_STREAM_NAME) -> "encrypted_container" to ExtractionMessages.ENCRYPTED
                    root.hasEntry(WORD_STREAM_NAME) -> "legacy_ole2_document" to ExtractionMessages.LEGACY_DOC
                    else -> "ole2_container" to ExtractionMessages.UNKNOWN_OLE2
                }
            }
        } catch (_: IOException) {
            "ole2_container" to ExtractionMessages.UNKNOWN_OLE2
        } catch (_: IllegalArgumentException) {
            "ole2_container" to ExtractionMessages.UNKNOWN_OLE2
        } catch (_: IllegalStateException) {
            "ole2_container" to ExtractionMessages.UNKNOWN_OLE2
        }

    /** 루트의 `FileHeader` 스트림이 있고, 그 선두 바이트가 [HWP5_SIGNATURE] 와 같은지 본다. */
    private fun isHwp5(root: DirectoryNode): Boolean {
        if (!root.hasEntry(HWP_HEADER_STREAM_NAME)) return false
        val header =
            root.createDocumentInputStream(HWP_HEADER_STREAM_NAME).use { it.readNBytes(HWP5_SIGNATURE.size) }
        return header.contentEquals(HWP5_SIGNATURE)
    }
}
