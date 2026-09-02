package kr.easydoc.infrastructure.ingest

import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.DocumentExtractionException
import kr.easydoc.core.exceptions.UnsupportedFormatException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream

/** 디스패치 · 확장자 판별 · OLE2 4분기. */
class DocumentExtractorsTest {
    private val extractors = DocumentExtractors()

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "안내문.docx, docx",
        "안내문.DOCX, docx",
        "안내문.Pdf, pdf",
        "안내문.hwpx, hwpx",
        "안내문.txt, txt",
        "안내문.TXT, txt",
        "C:\\Users\\hong\\안내문.docx, docx",
        "/tmp/../안내문.pdf, pdf",
        "a.b.c.hwpx, hwpx",
    )
    @DisplayName("확장자로 형식을 가린다 — 대소문자 무시, 경로 흡수")
    fun `확장자로 형식을 가린다`(
        filename: String,
        expected: String,
    ) {
        assertThat(SourceFormat.ofUploadFilename(filename)?.wireName).isEqualTo(expected)
    }

    @ParameterizedTest(name = "\"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = ["안내문", "안내문.hwp", "안내문.doc", ".hwpx", "안내문.docx.exe", "안내문."])
    @DisplayName("지원하지 않는 이름은 형식이 아니다")
    fun `지원하지 않는 확장자는 형식이 없다`(filename: String?) {
        assertThat(SourceFormat.ofUploadFilename(filename)).isNull()
    }

    @Test
    @DisplayName("알 수 없는 확장자는 계약 문구 그대로 거절한다")
    fun `지원하지 않는 형식을 거절한다`() {
        assertThatThrownBy { extractors.extract("안내문.xyz", byteArrayOf()) }
            .isInstanceOf(UnsupportedFormatException::class.java)
            .hasMessage("지원 형식: docx, pdf, hwpx, txt")
    }

    @Test
    @DisplayName("구버전 doc 확장자는 일반 안내가 아니라 **전용 문구**로 거절한다 — 내용 진단 없이 확장자만으로 안다")
    fun `doc 확장자를 전용 문구로 거절한다`() {
        assertThatThrownBy { extractors.extract("안내문.doc", byteArrayOf()) }
            .isInstanceOf(UnsupportedFormatException::class.java)
            .hasMessage(ExtractionMessages.LEGACY_DOC)
    }

    @Test
    @DisplayName("구버전 hwp 확장자는 일반 안내가 아니라 **전용 문구**로 거절한다 — doc 와 같은 대접이다")
    fun `hwp 확장자를 전용 문구로 거절한다`() {
        assertThatThrownBy { extractors.extract("안내문.hwp", byteArrayOf()) }
            .isInstanceOf(UnsupportedFormatException::class.java)
            .hasMessage(ExtractionMessages.LEGACY_HWP)
    }

    @Test
    @DisplayName("안내 문구가 지원 형식 집합에서 유도된다 — 형식을 늘리면 안내도 는다")
    fun `안내 문구가 형식 집합에서 나온다`() {
        assertThat(ExtractionMessages.UNSUPPORTED_FORMAT)
            .isEqualTo("지원 형식: " + SourceFormat.UPLOAD_FORMATS.joinToString(", ") { it.wireName })
    }

    @Test
    @DisplayName("업로드 형식 집합이 **손으로 적은 값**과 같다 — 계약 대조는 UploadFormatContractTest 가 한다")
    fun `업로드 형식 집합이 못박은 값과 같다`() {
        assertThat(SourceFormat.UPLOAD_FORMATS.map { it.wireName }).containsExactly("docx", "pdf", "hwpx", "txt")
        assertThat(SourceFormat.entries.map { it.wireName })
            .containsExactly("text", "docx", "pdf", "hwpx", "txt")
        assertThat(SourceFormat.UPLOAD_FORMATS).doesNotContain(SourceFormat.TEXT)
    }

    @Test
    @DisplayName("zip 계열만 압축 예산을 지난다")
    fun `zip 컨테이너 판정`() {
        assertThat(SourceFormat.DOCX.isZipContainer).isTrue()
        assertThat(SourceFormat.HWPX.isZipContainer).isTrue()
        assertThat(SourceFormat.PDF.isZipContainer).isFalse()
        assertThat(SourceFormat.TEXT.isZipContainer).isFalse()
        assertThat(SourceFormat.TXT.isZipContainer).isFalse()
    }

    @Test
    @DisplayName("암호 걸린 OOXML 은 암호 안내로 거절한다 (OLE2 1분기 — 루트 EncryptedPackage 스트림)")
    fun `암호 컨테이너를 가려낸다`() {
        assertThatThrownBy { extractors.extract("안내문.docx", IngestFixtures.ole2With("EncryptedPackage")) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.ENCRYPTED)
    }

    @Test
    @DisplayName("구버전 doc 은 **전용 문구**로 거절한다 (OLE2 2분기 — 계약 legacy_doc_policy, 루트 WordDocument 스트림)")
    fun `구버전 doc 을 가려낸다`() {
        assertThatThrownBy { extractors.extract("안내문.docx", IngestFixtures.ole2With("WordDocument")) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.LEGACY_DOC)
    }

    @Test
    @DisplayName(
        "구버전 hwp(5.x) 를 hwpx 로 이름만 바꿔 올려도 **전용 문구**로 거절한다 " +
            "(OLE2 3분기 — 계약 legacy_hwp_policy, 서명 출처는 Ole2Diagnosis KDoc)",
    )
    fun `구버전 hwp 를 가려낸다`() {
        val hwp =
            IngestFixtures.ole2Of {
                createDocument("FileHeader", ByteArrayInputStream(IngestFixtures.hwp5FileHeader()))
            }

        assertThatThrownBy { extractors.extract("안내문.hwpx", hwp) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.LEGACY_HWP)
    }

    @Test
    @DisplayName(
        "HWP 가 임베드한 워드 개체는 오판을 부르지 않는다 — 루트 FileHeader 서명이 " +
            "임베드 스토리지 안의 WordDocument·EncryptedPackage 보다 앞선다 (Codex 리뷰 지적, 혼합 표지 케이스)",
    )
    fun `임베드 개체가 섞여도 루트 FileHeader 로 hwp 를 가려낸다`() {
        val hwpWithEmbeddedObjects =
            IngestFixtures.ole2Of {
                createDocument("FileHeader", ByteArrayInputStream(IngestFixtures.hwp5FileHeader()))
                val binData = createDirectory("BinData")
                binData.createDocument("WordDocument", ByteArrayInputStream(byteArrayOf(1, 2, 3)))
                binData.createDocument("EncryptedPackage", ByteArrayInputStream(byteArrayOf(4, 5, 6)))
            }

        assertThatThrownBy { extractors.extract("안내문.hwpx", hwpWithEmbeddedObjects) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.LEGACY_HWP)
    }

    @Test
    @DisplayName("루트에 FileHeader 가 있어도 서명이 다르면 hwp 로 단정하지 않는다 (OLE2 4분기)")
    fun `FileHeader 서명이 틀리면 미상으로 남는다`() {
        val wrongSignature =
            IngestFixtures.ole2Of {
                createDocument(
                    "FileHeader",
                    ByteArrayInputStream(IngestFixtures.hwp5FileHeader(signature = "Not The Real Signature")),
                )
            }

        assertThatThrownBy { extractors.extract("안내문.hwpx", wrongSignature) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.UNKNOWN_OLE2)
    }

    @Test
    @DisplayName("OLE2 이지만 아는 스트림이 하나도 없으면 단정하지 않는다 (OLE2 4분기)")
    fun `미상 OLE2 는 두 가능성을 함께 안내한다`() {
        assertThatThrownBy { extractors.extract("안내문.hwpx", IngestFixtures.ole2With("SomethingElse")) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.UNKNOWN_OLE2)
    }

    @Test
    @DisplayName("OLE2 매직 뒤가 손상돼 POIFS 가 디렉터리를 못 열어도 예외가 새지 않는다 (OLE2 4분기)")
    fun `POIFS 파싱 실패도 미상으로 떨어진다`() {
        val magic = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())
        val magicThenGarbage = magic + ByteArray(OLE2_PADDING_BYTES) { it.toByte() }

        assertThatThrownBy { extractors.extract("안내문.hwpx", magicThenGarbage) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.UNKNOWN_OLE2)
    }

    @Test
    @DisplayName(
        "POI 가 손상된 헤더에 비검사(unchecked) 예외를 던져도 500 으로 새지 않고 미상으로 떨어진다 " +
            "(OLE2 4분기, Codex 재리뷰 지적)",
    )
    fun `POI 비검사 예외도 미상으로 떨어진다`() {
        // BAT 섹터 수 필드를 파일 실제 크기로는 불가능한 값으로 바꾼 헤더 — POI 5.4.1 은 이
        // 패치에 `IllegalArgumentException`(체크 예외가 아니다)을 던진다(픽스처 KDoc 참고,
        // 사전 프로브로 확인). classify 가 IOException 만 잡던 예전 코드라면 이 예외가 그대로
        // 새어 500 이 됐다.
        val corrupted = Ole2ContainerFixtures.corruptedBatSectorCount()

        assertThatThrownBy { extractors.extract("안내문.hwpx", corrupted) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.UNKNOWN_OLE2)
    }

    /** 아래 손상된 헤더 표 테스트의 한 행. */
    private data class HostileHeaderCase(
        val label: String,
        val patch: (ByteArray) -> Unit,
        val expectedMessage: String,
    )

    @Test
    @DisplayName(
        "POI 가 온갖 모양으로 손상된 OLE2 헤더에 던지는 예외가 전부 잡힌다 — 타입을 하나씩 나열하지 " +
            "않고 RuntimeException 을 좁혀 잡는 이유를 표로 남긴다 (Codex stop-time 재리뷰 지적)",
    )
    fun `손상된 헤더 여덟 가지가 전부 DocumentExtractionException 으로 떨어진다`() {
        // 각 행의 주석은 POI 5.4.1 이 그 패치에 실제로 던진 예외를 사전 프로브로 확인한 값이다.
        // 셋(미니 섹터 시프트·미니 스트림 컷오프·첫 DIFAT 섹터)은 POI 가 값을 그대로 받아들여
        // 예외가 나지 않는다 — 원본 WordDocument 스트림은 그대로 읽혀 LEGACY_DOC 로 떨어진다.
        // "무엇도 새지 않는다"는 이 표의 목적은 만족하지만 UNKNOWN_OLE2 로 단정하면 거짓이라
        // 실측한 문구를 그대로 적는다(정책을 넓히지 않는다).
        val cases =
            listOf(
                // IOException("Unsupported blocksize  (2^31). Expected 2^9 or 2^12.")
                HostileHeaderCase("섹터 시프트=0x1F(2^31)", { it[0x1E] = 0x1F }, ExtractionMessages.UNKNOWN_OLE2),
                // IOException("Unsupported blocksize  (2^0). Expected 2^9 or 2^12.")
                HostileHeaderCase("섹터 시프트=0x00(2^0)", { it[0x1E] = 0x00 }, ExtractionMessages.UNKNOWN_OLE2),
                // 예외 없음 — POI 가 그대로 받아들인다.
                HostileHeaderCase("미니 섹터 시프트=0x1F", { it[0x20] = 0x1F }, ExtractionMessages.LEGACY_DOC),
                // 예외 없음.
                HostileHeaderCase("미니 섹터 시프트=0x00", { it[0x20] = 0x00 }, ExtractionMessages.LEGACY_DOC),
                // IOException("Block count 2147483647 is too high. POI maximum is 65535.")
                HostileHeaderCase(
                    "BAT 섹터 수=0x7FFFFFFF",
                    { writeIntLe(it, HOSTILE_BAT_SECTOR_COUNT_OFFSET, HOSTILE_ABSURD_INT) },
                    ExtractionMessages.UNKNOWN_OLE2,
                ),
                // IndexOutOfBoundsException("Block 2147483647 not found") — IOException 이 아니다.
                // Ole2Diagnosis.readFacts 가 IOException 만 잡았다면 여기서 새어 500 이 됐다.
                HostileHeaderCase(
                    "첫 디렉터리 섹터=0x7FFFFFFF",
                    { writeIntLe(it, HOSTILE_FIRST_DIR_SECTOR_OFFSET, HOSTILE_ABSURD_INT) },
                    ExtractionMessages.UNKNOWN_OLE2,
                ),
                // 예외 없음.
                HostileHeaderCase(
                    "미니 스트림 컷오프=0",
                    { writeIntLe(it, HOSTILE_MINI_STREAM_CUTOFF_OFFSET, 0) },
                    ExtractionMessages.LEGACY_DOC,
                ),
                // 예외 없음 — 첫 DIFAT 섹터는 헤더 안 DIFAT 배열이 다 찰 때까지는 안 쓰인다.
                HostileHeaderCase(
                    "첫 DIFAT 섹터=0x7FFFFFFF",
                    { writeIntLe(it, HOSTILE_FIRST_DIFAT_SECTOR_OFFSET, HOSTILE_ABSURD_INT) },
                    ExtractionMessages.LEGACY_DOC,
                ),
                // ArrayIndexOutOfBoundsException("Index -2 out of bounds for length 5") —
                // IllegalArgumentException 도 IllegalStateException 도 아니다(그 둘만 잡던
                // 이전 버전이 여기서 500 으로 샜다).
                HostileHeaderCase(
                    "DIFAT 섹터 수=0x7FFFFFFF",
                    { writeIntLe(it, HOSTILE_DIFAT_COUNT_OFFSET, HOSTILE_ABSURD_INT) },
                    ExtractionMessages.UNKNOWN_OLE2,
                ),
            )

        cases.forEach { case ->
            val bytes = Ole2ContainerFixtures.ole2With("WordDocument").also(case.patch)

            assertThatThrownBy { extractors.extract("안내문.hwpx", bytes) }
                .withFailMessage("%s 가 기대한 문구로 떨어지지 않았다", case.label)
                .isInstanceOf(DocumentExtractionException::class.java)
                .hasMessage(case.expectedMessage)
        }
    }

    @Test
    @DisplayName("헤더 뒤가 통째로 잘린 OLE2(512+1 바이트)도 미상으로 떨어진다 — IndexOutOfBoundsException 실증")
    fun `헤더만 있고 몸통이 잘린 OLE2 도 미상으로 떨어진다`() {
        // IndexOutOfBoundsException("Block 1 not found") — 사전 프로브로 확인.
        val truncated = Ole2ContainerFixtures.ole2With("WordDocument").copyOf(HEADER_PLUS_ONE_BYTE)

        assertThatThrownBy { extractors.extract("안내문.hwpx", truncated) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.UNKNOWN_OLE2)
    }

    @Test
    @DisplayName("디렉터리가 파일 끝을 넘어 가리키는 잘린 OLE2(4096 바이트)도 미상으로 떨어진다")
    fun `디렉터리가 파일 끝을 넘는 잘린 OLE2 도 미상으로 떨어진다`() {
        // IndexOutOfBoundsException("Block 4096 not found") — 사전 프로브로 확인.
        val bytes = Ole2ContainerFixtures.ole2With("WordDocument")
        writeIntLe(bytes, HOSTILE_FIRST_DIR_SECTOR_OFFSET, TRUNCATED_CONTAINER_SIZE)
        val truncated = bytes.copyOf(TRUNCATED_CONTAINER_SIZE)

        assertThatThrownBy { extractors.extract("안내문.hwpx", truncated) }
            .isInstanceOf(DocumentExtractionException::class.java)
            .hasMessage(ExtractionMessages.UNKNOWN_OLE2)
    }

    /** 리틀엔디안 4바이트 정수를 [offset] 에 써넣는다 — `Ole2ContainerFixtures` 의 헤더 조작과 같은 모양. */
    private fun writeIntLe(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = (value and BYTE_MASK).toByte()
        bytes[offset + 1] = ((value shr BYTE_BITS) and BYTE_MASK).toByte()
        bytes[offset + 2] = ((value shr (2 * BYTE_BITS)) and BYTE_MASK).toByte()
        bytes[offset + 3] = ((value shr (3 * BYTE_BITS)) and BYTE_MASK).toByte()
    }

    @Test
    @DisplayName("네 문구가 서로 다르다 — 4분기를 합치면 사용자가 취할 조치를 알 수 없다")
    fun `네 안내 문구가 서로 다르다`() {
        assertThat(
            setOf(
                ExtractionMessages.ENCRYPTED,
                ExtractionMessages.LEGACY_DOC,
                ExtractionMessages.LEGACY_HWP,
                ExtractionMessages.UNKNOWN_OLE2,
            ),
        ).hasSize(4)
    }

    @Test
    @DisplayName("정상 파일 세 형식이 포트를 통해 형식과 본문을 함께 돌려준다")
    fun `세 형식이 포트를 통해 나온다`() {
        val docx = extractors.extract("안내문.docx", IngestFixtures.bytes("sample.docx"))
        val pdf = extractors.extract("안내문.pdf", IngestFixtures.bytes("sample.pdf"))
        val hwpx = extractors.extract("안내문.hwpx", IngestFixtures.bytes("sample.hwpx"))

        assertThat(docx.format).isEqualTo(SourceFormat.DOCX)
        assertThat(pdf.format).isEqualTo(SourceFormat.PDF)
        assertThat(hwpx.format).isEqualTo(SourceFormat.HWPX)
        assertThat(docx.text).isEqualTo(IngestFixtures.expectedText(IngestFixtures.repoOracle, "sample.docx"))
        assertThat(pdf.text).isEqualTo(IngestFixtures.expectedText(IngestFixtures.repoOracle, "sample.pdf"))
        assertThat(hwpx.text).isEqualTo(IngestFixtures.expectedText(IngestFixtures.repoOracle, "sample.hwpx"))
    }

    @Test
    @DisplayName("txt 도 포트를 통해 형식과 본문을 함께 돌려준다")
    fun `txt 가 포트를 통해 나온다`() {
        val txt = extractors.extract("안내문.txt", "안내문 첫 줄\n둘째 줄".toByteArray(Charsets.UTF_8))

        assertThat(txt.format).isEqualTo(SourceFormat.TXT)
        assertThat(txt.text).isEqualTo("안내문 첫 줄\n둘째 줄")
    }

    @Test
    @DisplayName("추출 결과의 toString 이 본문을 찍지 않는다")
    fun `추출 결과가 본문을 찍지 않는다`() {
        val result = extractors.extract("안내문.docx", IngestFixtures.bytes("sample.docx"))

        assertThat(result.toString()).doesNotContain("쉬운 글 변환 안내")
        assertThat(result.toString()).contains("docx")
    }

    private companion object {
        /** OLE2 매직 뒤에 붙일, 유효한 디렉터리 구조가 아닌 임의 바이트 길이 — 파싱 실패 케이스 전용. */
        const val OLE2_PADDING_BYTES = 508

        // 아래는 손상된 헤더 표 테스트·잘린 컨테이너 테스트가 쓰는 오프셋 — MS-CFB §2.2, 값은
        // `Ole2ContainerFixtures` 의 같은 이름 오프셋 상수와 같다(테스트 소스셋이라 별도로 든다).
        const val HOSTILE_BAT_SECTOR_COUNT_OFFSET = 0x2C
        const val HOSTILE_FIRST_DIR_SECTOR_OFFSET = 0x30
        const val HOSTILE_MINI_STREAM_CUTOFF_OFFSET = 0x38
        const val HOSTILE_FIRST_DIFAT_SECTOR_OFFSET = 0x44
        const val HOSTILE_DIFAT_COUNT_OFFSET = 0x48

        /** 실제 파일 크기로는 있을 수 없는 값 — POI 가 이 필드들에 이 값이 오면 예외를 던진다. */
        const val HOSTILE_ABSURD_INT = 0x7FFFFFFF

        /** OLE2 헤더(512바이트) 바로 뒤 1바이트만 있는 크기 — 몸통이 통째로 잘린 케이스. */
        const val HEADER_PLUS_ONE_BYTE = 513

        /** 디렉터리가 파일 끝을 넘어 가리키는 잘림 케이스의 컨테이너 크기. */
        const val TRUNCATED_CONTAINER_SIZE = 4096

        const val BYTE_MASK = 0xFF
        const val BYTE_BITS = 8
    }
}
