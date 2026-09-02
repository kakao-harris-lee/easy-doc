package kr.easydoc.application.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.MaskedItemView
import kr.easydoc.core.easyread.ExportFile
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.privacy.MaskedItem
import kr.easydoc.core.privacy.ModelDraft
import kr.easydoc.core.privacy.ReviewedBody
import kr.easydoc.core.privacy.restoreForExport
import java.util.UUID

/**
 * 변환 결과 내보내기 — 소유 확인·**형식 합의**·완료 여부·자리표시자 복원을 한 자리에서
 * 판정한다. 파일 바이트 조립은 [DocumentExporter] 가 한다.
 *
 * **형식은 서버가 정한다**(계약 `x-export-format-derivation.enforcement`, `DESIGN.md` §6.5).
 * 유도 규칙을 여기 옮겨 적지 않고 [ExportFormat.ofSource] 하나를 쓴다 — 조회 응답의
 * `export_format` 도 같은 함수를 지나므로(`ConversionQueryService`) 두 자리가 갈릴 수 없다.
 */
class ConversionExportService(
    private val conversions: ConversionRepository,
    private val cipher: ContentCipher,
    private val maskedItems: MaskedItemReader,
    private val rendering: ExportRendering,
    private val transaction: TransactionRunner,
) {
    /**
     * 내 변환의 최종본을 파일로 만든다. 복호화는 트랜잭션 안, 패키지 조립은 밖에서 한다.
     *
     * [requested] 는 **주장이지 선택이 아니다.** `null` 이면 서버가 원본에서 정하고
     * (계약 `enforcement.on_absent`), 값을 주면 그 값과 **같아야** 한다. 나가는 형식은
     * 어느 쪽이든 [ExportFormat.ofSource] 가 정한 그 값이다.
     */
    fun export(
        ownerId: UUID,
        conversionId: UUID,
        requested: ExportFormat?,
    ): ExportFile {
        val prepared = transaction.inTransaction { prepare(ownerId, conversionId, requested) }
        // 선택지가 있는 원본(PDF)은 [PreparedExport.reflectOriginal] 이 `false` 다 — 원본을
        // **열지 않고** 고른 형식으로 새 문서를 조립한다(2.6.0 재결정). 이 분기는 그 의도를
        // 읽는다 — `original == null` 을 대신 재면 「원본이 없어서」와 「원본이 있어도 반영
        // 대상이 아니라서」가 코드에서 같은 자리에 놓인다.
        return if (!prepared.reflectOriginal) {
            rendering.exporter.export(prepared.title, prepared.body, prepared.format)
        } else {
            reflectOrAssemble(prepared)
        }
    }

    /** 원본을 열어 반영하되, 열 원본이 없으면(붙여넣기 · 옛 업로드) 새 문서를 만든다. */
    private fun reflectOrAssemble(prepared: PreparedExport): ExportFile {
        // 원본이 없는 문서도 새 문서를 만든다 — 그 갈래의 서식 유지 판정이 `not_applicable`
        // 이고, **영구히 참**이다.
        val opened =
            prepared.original
                ?: return rendering.exporter.export(prepared.title, prepared.body, prepared.format)
        // **텍스트 전용 파일로 조용히 대체하지 않는다**(§6.5). 원본을 열 수 없으면 그 사실이
        // 오류로 드러나고, 같은 사유가 조회 응답에서는 `failed` 로 이미 보인다.
        return rendering.reflection.reflector.reflect(opened, prepared.title, prepared.body)
            ?: throw StorageException(UNREADABLE_ORIGINAL_MESSAGE)
    }

    private fun prepare(
        ownerId: UUID,
        conversionId: UUID,
        requested: ExportFormat?,
    ): PreparedExport {
        val stored =
            conversions.findOwnedExport(ownerId, conversionId)
                ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
        val format = agreedFormat(stored, requested)
        requireDone(stored)
        val draft = requireDraft(stored)
        val reviewed =
            open(stored.result.id, stored.result.ciphertexts.editedText, EncryptedField.CONVERSION_EDITED_TEXT)
        val items =
            open(stored.result.id, stored.result.ciphertexts.maskedItems, EncryptedField.CONVERSION_MASKED_ITEMS)
                ?.let(maskedItems::decode)
                .orEmpty()
                .map(::toMaskedItem)
        // 선택지가 있는 원본(PDF)은 원본을 **읽지 않는다** — 반영이라는 개념 자체가 적용되지
        // 않으므로 굳이 복호화해 열 이유가 없다(§6.5 재결정, `choiceExportPreservation`).
        val reflectOriginal = ExportFormat.choicesFor(stored.result.sourceFormat).isEmpty()
        return PreparedExport(
            title = stored.documentTitle,
            format = format,
            body = restoredBody(draft, reviewed, items),
            reflectOriginal = reflectOriginal,
            // 조회의 판정과 **같은 원본**을 연다. 붙여넣기 문서에는 행이 없어 언제나 `null` 이다.
            original =
                if (!reflectOriginal) {
                    null
                } else {
                    rendering.reflection.originals.read(
                        ownerId,
                        stored.result.documentId,
                        stored.result.sourceFormat,
                    )
                },
        )
    }

    /**
     * 이 변환이 내보낼 형식. 요청이 그것과 다르면 **409**다.
     *
     * **소유 판정 뒤, 완료 판정 앞이다.** 뒤인 것은 남의 변환에서 형식 불일치를 알려 주면
     * 404로 가려 둔 사실이 형식 축으로 새기 때문이고(계약 `x-why-409-and-not-422`),
     * 앞인 것은 이 거절이 **기다려도 바뀌지 않는** 사실이기 때문이다 — 결국 거절될 요청에
     * 「끝난 뒤에 다시 오라」고 답하면 틀린 행동을 권하게 된다.
     *
     * **원본이 유도값을 내면**(오늘은 PDF 를 뺀 전부) 그 값이 정본이고 요청은 주장일 뿐이다
     * (계약 `enforcement.on_mismatch`). **유도값이 없으면**(오늘은 PDF 뿐) 선택지를 본다 —
     * 선택지가 있으면 요청이 그 안에 있어야 하고(생략은 거절, `on_absent_with_choices`·
     * `on_choice_mismatch`), 선택지도 없으면(오늘은 없는 갈래) 완전히 내보낼 수 없다
     * (`on_null_mapping`).
     */
    private fun agreedFormat(
        stored: StoredExport,
        requested: ExportFormat?,
    ): ExportFormat {
        val derived = ExportFormat.ofSource(stored.result.sourceFormat)
        if (derived != null) {
            if (requested != null && requested != derived) {
                throw ConflictException(EXPORT_FORMAT_MISMATCH_MESSAGE)
            }
            return derived
        }
        val choices = ExportFormat.choicesFor(stored.result.sourceFormat)
        val rejection = choiceRejection(choices, requested)
        if (rejection != null) {
            throw ConflictException(rejection)
        }
        // `choiceRejection` 이 `null` 이면 선택지가 있고 요청이 그 안에 있었다는 뜻이다 — 그
        // 세 조건을 다시 갈라 적지 않는다(위 판정과 두 벌로 두면 한쪽만 고쳐지는 날이 온다).
        return requireNotNull(requested) { "선택지 판정을 지났는데 요청이 null 이다 — choiceRejection 이 갈래를 놓쳤다" }
    }

    /**
     * 유도값이 없는 원본의 거절 사유 — 거절할 것이 없으면 `null`이다.
     *
     * 선택지가 없으면(`on_null_mapping`, 오늘은 없는 갈래) · 요청이 없으면(`on_absent_with_choices`)
     * · 요청이 선택지 밖이면(`on_choice_mismatch`) 순서로 본다.
     */
    private fun choiceRejection(
        choices: List<ExportFormat>,
        requested: ExportFormat?,
    ): String? =
        when {
            choices.isEmpty() -> EXPORT_FORMAT_UNAVAILABLE_MESSAGE
            requested == null -> EXPORT_FORMAT_CHOICE_REQUIRED_MESSAGE
            requested !in choices -> EXPORT_FORMAT_CHOICE_MISMATCH_MESSAGE
            else -> null
        }

    private fun requireDone(stored: StoredExport) {
        if (!stored.result.status.exposesResult) {
            throw ConflictException(EXPORT_NOT_DONE_MESSAGE)
        }
    }

    private fun requireDraft(stored: StoredExport): PlainBody =
        open(stored.result.id, stored.result.ciphertexts.easyText, EncryptedField.CONVERSION_EASY_TEXT)
            ?: throw StorageException(UNREADABLE_EXPORT_MESSAGE)

    private fun restoredBody(
        draft: PlainBody,
        reviewed: PlainBody?,
        items: List<MaskedItem>,
    ): String {
        // 검수본이 없으면 자리표시자를 복원하지 않는다. 계약 GET export 복원 규칙과 같다.
        val restoration =
            restoreForExport(
                ModelDraft(draft.value),
                reviewed?.let { ReviewedBody(it.value) },
                items,
            )
        if (reviewed == null && restoration.missing.isNotEmpty()) {
            throw ConflictException(EXPORT_MISSING_PLACEHOLDERS_MESSAGE)
        }
        return restoration.text
    }

    private fun open(
        record: UUID,
        column: EncryptedContent?,
        field: EncryptedField,
    ): PlainBody? = column?.let { cipher.decrypt(it, record, field) }

    private fun toMaskedItem(view: MaskedItemView): MaskedItem =
        MaskedItem(view.category, view.placeholder, view.original)

    private class PreparedExport(
        val title: String,
        val format: ExportFormat,
        val body: String,
        /**
         * 이 변환의 원본을 **열어 반영을 시도할 것인가.** `false` 면 [original] 은 언제나
         * `null` 이고(애초에 읽지 않는다), [export] 는 원본을 보지 않고 신문서를 조립한다 —
         * 선택지가 있는 원본(PDF)이 이 갈래다. `true` 인데 [original] 이 `null` 이면
         * 「반영을 시도했지만 원본이 없다」는 뜻이고(붙여넣기 · 옛 업로드), 그때도 신문서를
         * 조립한다 — 둘의 차이는 **의도**다: 전자는 반영을 아예 시도하지 않고, 후자는
         * 시도할 원본이 없어서 못 한다.
         */
        val reflectOriginal: Boolean,
        val original: OriginalDocument?,
    ) {
        override fun toString(): String =
            "PreparedExport(제목 ${title.length}자, ${format.extension}, 본문 ${body.length}자, " +
                "반영 $reflectOriginal, 원본 $original)"
    }

    private companion object {
        const val UNREADABLE_EXPORT_MESSAGE: String = "저장된 변환 결과를 읽을 수 없습니다"

        /**
         * 저장된 원본을 열 수 없다 — **500** 이다(`StorageException`).
         *
         * 업로드 때는 같은 바이트가 파서를 지나갔다(그래야 변환이 섰다). 그러니 지금 열리지
         * 않는 것은 사용자가 고칠 수 있는 입력 문제가 아니라 **서버가 보관한 바이트의 문제**이고,
         * 「저장된 변환 결과를 읽을 수 없습니다」와 같은 종류의 실패다. 기다리면 달라지는 일이
         * 아니라 409 도 아니다.
         */
        const val UNREADABLE_ORIGINAL_MESSAGE: String = "저장된 원본 파일을 읽을 수 없습니다"
    }
}
