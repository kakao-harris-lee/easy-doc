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

    private const val HWP_HEADER_STREAM_NAME = "FileHeader"
    private const val HWP5_STREAM_PADDED_SIZE = 32
}
