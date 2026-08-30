package kr.easydoc.application.document

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBytes
import kr.easydoc.core.document.ReflectionOutcome
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.easyread.ExportFile
import java.util.UUID

/**
 * 열어 놓은 업로드 원본 한 건 — **형식과 평문 바이트**.
 *
 * 바이트를 든 채 계층을 넘는 값이라 [toString] 이 크기만 남긴다. 파일 안에는 본문 말고도
 * 작성자·수정 이력·주석이 들어 있어(`EncryptedField.DOCUMENT_ORIGINAL_BYTES` KDoc) 로그에
 * 흘릴 수 있는 값이 하나도 없다.
 */
class OriginalDocument(
    val format: SourceFormat,
    val bytes: PlainBytes,
) {
    override fun toString(): String = "OriginalDocument(${format.wireName}, ${bytes.value.size}바이트)"
}

/**
 * 업로드 원본 **구조에 검수본을 반영하는** 포트.
 *
 * 벤더 타입(POI 의 `XWPFDocument`, hwpxlib 의 `HWPXFile`)은 이 경계를 넘지 않는다 — 오가는
 * 것은 바이트와 [ReflectionOutcome] 의 **개수**, 그리고 [ExportFile] 뿐이다.
 *
 * 두 팔이 **같은 자리 맞춤**을 써야 한다는 것이 이 포트의 계약이다. [outline] 이 미리 센 것과
 * [reflect] 가 실제로 하는 것이 갈리면 응답의 서식 유지 판정이 파일과 다른 것을 말하게 된다.
 */
interface OriginalStructureReflector {
    /**
     * [body] 를 원본 구조에 반영하면 **무엇이 달라지는지** 미리 센다. 파일은 만들지 않는다.
     * 열 수 없으면 `null` — 판정은 그때 [kr.easydoc.core.document.unreadableOriginalPreservation] 이다.
     *
     * 같은 형식으로 내보낼 수단이 없는 형식(PDF)에도 `null` 이다. 부르는 쪽이 그 갈래를
     * **먼저** 걸러야 한다(`ExportFormat.ofSource` 가 `null` 인 형식).
     */
    fun outline(
        original: OriginalDocument,
        body: String,
    ): ReflectionOutcome?

    /**
     * 원본 구조에 [body] 를 반영한 파일을 만든다. 열 수 없으면 `null` — 부르는 쪽이 그것을
     * 오류로 바꾼다. **텍스트 전용 파일로 조용히 대체하지 않는다**(`DESIGN.md` §6.5).
     */
    fun reflect(
        original: OriginalDocument,
        title: String,
        body: String,
    ): ExportFile?
}

/**
 * 저장된 업로드 원본을 **열어 주는** 협력자 — 소유 술어와 복호화가 한 자리에 선다.
 *
 * 조회(서식 유지 판정)와 내보내기가 같은 값을 필요로 하고, 둘이 각자 복호화하면 결속
 * (record·field)을 두 벌로 적게 된다. `documents` 가 아니라 `document_originals` 를 읽으므로
 * 붙여넣기 문서에는 언제나 `null` 이다.
 *
 * **호출자의 트랜잭션 안에서 돈다** — 저장소 팔이 그렇게 선언돼 있다.
 */
class StoredOriginalReader(
    private val originals: DocumentOriginalRepository,
    private val cipher: ContentCipher,
) {
    /** **내** 문서의 원본을 연다. 없거나 내 것이 아니면 `null` — 두 경우를 구분하지 않는다. */
    fun read(
        ownerId: UUID,
        documentId: UUID,
        format: SourceFormat,
    ): OriginalDocument? =
        originals.findOwned(ownerId, documentId)?.let { sealed ->
            OriginalDocument(
                format = format,
                bytes =
                    cipher.decryptBytes(sealed.bytes, documentId, EncryptedField.DOCUMENT_ORIGINAL_BYTES),
            )
        }
}

/**
 * 저장된 원본을 다루는 협력자 **한 쌍** — 여는 쪽과 반영하는 쪽.
 *
 * 둘은 따로 설 자리가 없다. 원본을 여는 유일한 이유가 반영이고, 반영은 열지 않고 할 수
 * 없다. 유스케이스 생성자에 둘을 따로 늘어놓으면 「함께 있어야 한다」가 어디에도 적히지
 * 않은 채 인자 수만 늘어난다 — [SealedStores]·[DocumentStorage] 가 함께 서야 하는
 * 협력자를 묶는 것과 같은 판단이다.
 */
class OriginalReflection(
    val originals: StoredOriginalReader,
    val reflector: OriginalStructureReflector,
)

/**
 * 내보낼 파일을 만드는 **두 갈래**.
 *
 * 원본이 남아 있으면 그 구조에 검수본을 반영하고([reflection]), 없으면 본문으로 새 문서를
 * 만든다([exporter]). 한 유스케이스가 **둘 중 하나를 고르는** 관계라 둘은 언제나 함께 선다 —
 * 따로 주입하면 「원본이 없을 때만 저쪽」이라는 관계가 생성자 어디에도 적히지 않는다.
 *
 * 두 갈래를 포트 하나로 합치지 않는 이유: 합치면 「원본이 없다」와 「원본을 열지 못했다」가
 * 어댑터 안에서 같은 자리에 놓이고, 유스케이스가 둘을 갈라 한쪽만 오류로 만들 수 없게 된다.
 * §6.5 의 「조용히 텍스트로 대체하지 않는다」가 정확히 그 구분에 기대고 있다.
 */
class ExportRendering(
    val reflection: OriginalReflection,
    val exporter: DocumentExporter,
)
