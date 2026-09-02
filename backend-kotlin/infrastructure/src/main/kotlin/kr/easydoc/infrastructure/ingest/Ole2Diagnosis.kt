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
     * 루트 레벨의 세 가지 사실만 담는다 — [classify] 의 `when` 이 이 값을 보고 사유·문구를
     * 고른다. 판정 로직 자체는 [readFacts] 의 try 블록 **밖**에 있다: POI 호출과 우리 분기
     * 로직을 같은 catch 아래 두면 분기 로직의 실패까지 "입력 오류"로 둔갑한다.
     */
    private data class Ole2RootFacts(
        val isHwp5: Boolean,
        val hasEncryptedPackage: Boolean,
        val hasWordDocument: Boolean,
    )

    /**
     * 네 갈래를 가른다. POI 호출([readFacts])이 던진 실패는 이미 [UNKNOWN_OLE2] 로 접힌
     * `null` 로 들어온다 — 여기 `when` 은 실패할 수 있는 연산이 없는 순수 분기다.
     */
    private fun classify(data: ByteArray): Pair<String, String> {
        val facts = readFacts(data) ?: return "ole2_container" to ExtractionMessages.UNKNOWN_OLE2
        return when {
            facts.isHwp5 -> "legacy_hwp_document" to ExtractionMessages.LEGACY_HWP
            facts.hasEncryptedPackage -> "encrypted_container" to ExtractionMessages.ENCRYPTED
            facts.hasWordDocument -> "legacy_ole2_document" to ExtractionMessages.LEGACY_DOC
            else -> "ole2_container" to ExtractionMessages.UNKNOWN_OLE2
        }
    }

    /**
     * POIFS 로 디렉터리를 열고 루트 레벨 사실만 읽는다. POIFS 는 스트림 전체를 메모리에 올려
     * 구조를 읽는데, 그 입력은 업로드 상한으로 이미 크기가 제한돼 있다. 디렉터리를 열지
     * 못하거나(OLE2 매직 뒤가 손상됐거나) 대상 스트림을 읽지 못하면 `null` 을 돌려줘
     * [classify] 가 [UNKNOWN_OLE2] 로 접게 한다.
     *
     * **이 try 블록 안에서 도는 것은 POI 호출뿐이다** — 우리 자신의 분기·문자열 선택 로직은
     * [classify] 로 빠져 있다. POI 가 실패를 항상 체크 예외로 알리지는 않는다: `IOException`
     * (POIFS 파싱·스트림 읽기 실패의 공통 상위 타입, `NotOLE2FileException` 포함)뿐 아니라,
     * 조작된 헤더 값에서 계산한 크기·색인·배열 길이가 말이 안 될 때는 온갖 **비검사
     * (unchecked)** 예외를 던진다 — 사전 프로브로 확인한 것만도 `IllegalArgumentException`
     * ("Unable read a >2gb file via an InputStream", BAT 섹터 수 조작),
     * `IllegalStateException`("Invalid format, cannot convert property ... to RootProperty",
     * 루트 속성 조작)이고, POI 소스는 같은 부류의 실패로 `RecordFormatException`
     * (`org.apache.poi.util`, 할당 크기 제한)·`BufferUnderflowException`·
     * `IndexOutOfBoundsException`·`NegativeArraySizeException`·`ArithmeticException` 도 던질
     * 수 있는 자리다(POI 이슈 트래커 사례). 이걸 하나씩 나열해 잡는 것은 **퍼저를 상대로 지는
     * 게임**이다 — POI 버전이 오르면 새 예외 타입이 생기고, 그때마다 이 목록이 뒤처진다.
     *
     * 그래서 `IOException` 과 함께 `RuntimeException` 을 **이 블록 범위로만 좁혀** 잡는다.
     * CLAUDE.md 는 비검사 예외를 보통 우리 코드의 **프로그래밍 오류**로 다뤄 잡지 말라고
     * 한다 — 그 규칙의 전제는 "우리 로직이 실패했다"이다. 이 블록 안에서 도는 코드는 전부
     * 서드파티 파서(POI)가 신뢰할 수 없는 입력(사용자가 업로드한 바이트, 이미 OLE2 매직으로
     * 형식만 확정된 상태)을 해석하는 코드이지 우리 로직이 아니다 — 그래서 여기서 나는 실패는
     * **정의상** 입력 오류이고, 그 전제가 성립하지 않는 [classify] 의 `when` 에는(우리 자신의
     * 분기이므로) 이 예외를 넓게 잡는 예외를 적용하지 않는다. `Error`·`Throwable` 은 잡지
     * 않는다 — `OutOfMemoryError` 같은 것은 여전히 새어 나가야 한다.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun readFacts(data: ByteArray): Ole2RootFacts? =
        try {
            POIFSFileSystem(ByteArrayInputStream(data)).use { fs ->
                val root = fs.root
                Ole2RootFacts(
                    isHwp5 = isHwp5(root),
                    hasEncryptedPackage = root.hasEntry(ENCRYPTED_STREAM_NAME),
                    hasWordDocument = root.hasEntry(WORD_STREAM_NAME),
                )
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    /** 루트의 `FileHeader` 스트림이 있고, 그 선두 바이트가 [HWP5_SIGNATURE] 와 같은지 본다. */
    private fun isHwp5(root: DirectoryNode): Boolean {
        if (!root.hasEntry(HWP_HEADER_STREAM_NAME)) return false
        val header =
            root.createDocumentInputStream(HWP_HEADER_STREAM_NAME).use { it.readNBytes(HWP5_SIGNATURE.size) }
        return header.contentEquals(HWP5_SIGNATURE)
    }
}
