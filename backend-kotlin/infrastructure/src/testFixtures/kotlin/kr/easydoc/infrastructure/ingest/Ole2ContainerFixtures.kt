package kr.easydoc.infrastructure.ingest

import org.apache.poi.poifs.filesystem.POIFSFileSystem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * `Ole2Diagnosis` 가 실제로 파싱하는 것과 같은 모양의 **진짜 OLE2 복합 문서**를 POIFS 로
 * 만든다 — OLE2 매직 바이트 뒤에 스트림 이름을 이어붙인 손 조립 블롭은 디렉터리 구조가 없어
 * POIFS 파싱에 실패하고 `UNKNOWN_OLE2` 로 떨어진다(계획 §5 D-12, Codex 리뷰).
 *
 * `infrastructure`·`api` 양쪽 테스트가 이 빌더를 함께 쓴다. 공개 시그니처에 POI 타입을
 * 노출하지 않는다 — `api` 는 `testFixtures(project(":infrastructure"))` 로만 이 산출물을
 * 당기고, POI 는 `testFixturesImplementation`(비공개 구현 의존성)이라 `api` 의 테스트
 * **컴파일** 클래스패스에는 없다. 실행 시점에는 이 오브젝트를 거쳐서만 POIFS 를 쓴다.
 */
object Ole2ContainerFixtures {
    /** 루트 레벨에 스트림 하나만 있는 최소 OLE2 컨테이너. */
    fun ole2With(
        streamName: String,
        content: ByteArray = byteArrayOf(0),
    ): ByteArray {
        val sink = ByteArrayOutputStream()
        POIFSFileSystem().use { fs ->
            fs.root.createDocument(streamName, ByteArrayInputStream(content))
            fs.writeFilesystem(sink)
        }
        return sink.toByteArray()
    }

    /** `Ole2Diagnosis.HWP5_SIGNATURE` 와 같은 값. */
    const val HWP5_SIGNATURE_TEXT = "HWP Document File"

    /**
     * HWP5 `FileHeader` 스트림 내용 — 서명(기본값은 진짜 서명)에 32바이트까지 널을 채운다.
     * 다른 [signature] 를 주면 "서명이 틀린 FileHeader" 케이스를 만들 수 있다.
     */
    fun hwp5FileHeader(signature: String = HWP5_SIGNATURE_TEXT): ByteArray {
        val bytes = signature.toByteArray(StandardCharsets.US_ASCII)
        val size = maxOf(bytes.size, HWP5_STREAM_PADDED_SIZE)
        return bytes + ByteArray(size - bytes.size)
    }

    /** 루트에 `FileHeader` 스트림 하나만 있는 최소 hwp5 컨테이너. */
    fun ole2WithHwp5FileHeader(signature: String = HWP5_SIGNATURE_TEXT): ByteArray =
        ole2With(HWP_HEADER_STREAM_NAME, hwp5FileHeader(signature))

    /**
     * 유효한 최소 OLE2 컨테이너의 헤더를 손상시켜 POIFS 가 **비검사(unchecked) 예외**를
     * 던지게 만든다 — OLE2 헤더의 BAT(FAT) 섹터 수 필드(오프셋 0x2C, 리틀엔디안 4바이트)를
     * 파일 실제 크기로는 있을 수 없는 값으로 바꾼다. `Ole2Diagnosis.classify` 가 `IOException`
     * 뿐 아니라 이런 예외도 잡아 `UNKNOWN_OLE2` 로 떨어뜨리는지 재는 픽스처다(Codex 재리뷰
     * 지적).
     *
     * POI 5.4.1 이 이 패치에 실제로 던지는 예외는 사전 프로브로 확인했다:
     * `IllegalArgumentException("Unable read a >2gb file via an InputStream")` — BAT 섹터
     * 수로부터 계산한 크기가 2GB 를 넘는다고 판단해 던진다.
     */
    fun corruptedBatSectorCount(streamName: String = "WordDocument"): ByteArray {
        val bytes = ole2With(streamName)
        val absurdFatSectorCount = ABSURD_FAT_SECTOR_COUNT
        bytes[BAT_SECTOR_COUNT_OFFSET] = (absurdFatSectorCount and BYTE_MASK).toByte()
        bytes[BAT_SECTOR_COUNT_OFFSET + 1] = ((absurdFatSectorCount shr BYTE_BITS) and BYTE_MASK).toByte()
        bytes[BAT_SECTOR_COUNT_OFFSET + 2] = ((absurdFatSectorCount shr (2 * BYTE_BITS)) and BYTE_MASK).toByte()
        bytes[BAT_SECTOR_COUNT_OFFSET + 3] = ((absurdFatSectorCount shr (3 * BYTE_BITS)) and BYTE_MASK).toByte()
        return bytes
    }

    private const val HWP_HEADER_STREAM_NAME = "FileHeader"
    private const val HWP5_STREAM_PADDED_SIZE = 32

    /** OLE2 헤더의 BAT(FAT) 섹터 수 필드 오프셋(리틀엔디안 4바이트) — MS-CFB §2.2. */
    private const val BAT_SECTOR_COUNT_OFFSET = 0x2C

    /** 실제 파일 크기로는 있을 수 없는 BAT 섹터 수 — POI 가 >2GB 로 오판해 예외를 던진다. */
    private const val ABSURD_FAT_SECTOR_COUNT = 60_000

    private const val BYTE_MASK = 0xFF
    private const val BYTE_BITS = 8
}
