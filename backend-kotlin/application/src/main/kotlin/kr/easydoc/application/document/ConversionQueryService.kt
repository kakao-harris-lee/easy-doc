package kr.easydoc.application.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.ConversionView
import kr.easydoc.core.document.FormatPreservation
import kr.easydoc.core.document.choiceExportPreservation
import kr.easydoc.core.document.noOriginalPreservation
import kr.easydoc.core.document.reflectedPreservation
import kr.easydoc.core.document.unreadableOriginalPreservation
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.privacy.maskText
import kr.easydoc.core.segment.SegmentMap
import kr.easydoc.core.segment.alignSegments
import kr.easydoc.core.segment.splitUnits
import java.util.UUID

/** 변환 조회 유스케이스 — **내** 변환 한 건의 상태와 결과를 읽는다. */
class ConversionQueryService(
    private val conversions: ConversionRepository,
    private val cipher: ContentCipher,
    private val maskedItems: MaskedItemReader,
    private val original: OriginalReflection,
    /** `segment_map` 을 유도하려고 원문을 읽는 협력자 — 계획 §6 S2. */
    private val documents: DocumentRepository,
    private val transaction: TransactionRunner,
) {
    /**
     * 내 변환 한 건의 **상태와 결과**를 읽는다. 완료 전에는 결과 필드가 비어 있다.
     *
     * 원본·원문을 **한 트랜잭션 안에서 함께** 읽는다. 판정에 원본이 필요한 갈래인지는 변환
     * 행을 읽어야 알 수 있고([needsOriginal]), 두 번 열면 그 사이에 행이 지워질 수 있다 —
     * `segment_map` 이 읽는 원문도 같은 이유로 이 경계 안에서 함께 읽는다(암호문만 — 복호화는
     * [completed] 가 경계 밖에서 한다, 다른 본문 세 열과 같은 규칙).
     */
    fun read(
        ownerId: UUID,
        conversionId: UUID,
    ): ConversionView {
        val loaded =
            transaction.inTransaction {
                val stored = conversions.findOwnedResult(ownerId, conversionId) ?: return@inTransaction null
                Triple(
                    stored,
                    if (needsOriginal(stored)) {
                        original.originals.read(ownerId, stored.documentId, stored.sourceFormat)
                    } else {
                        null
                    },
                    if (stored.status.exposesResult) documents.findOwnedSource(ownerId, stored.documentId) else null,
                )
            } ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
        val (stored, opened, source) = loaded
        return if (stored.status.exposesResult) completed(stored, opened, source) else beforeDone(stored)
    }

    /**
     * 서식 유지 판정에 원본 바이트가 필요한가.
     *
     * 셋을 **모두** 지나야 연다 — 최대 10MB 를 복호화하고 파싱하는 일이라 갈래를 좁게 둔다.
     * ⑴ 원본 행이 있고, ⑵ 같은 형식으로 내보낼 수단이 있고(선택지가 있는 원본 제외 —
     * PDF 는 오늘 원본을 열어 반영하지 않는다), ⑶ 판정의 다른 한쪽인 검수본이 이미 있다(완료).
     */
    private fun needsOriginal(stored: StoredConversion): Boolean =
        stored.hasStoredOriginal &&
            ExportFormat.ofSource(stored.sourceFormat) != null &&
            stored.status.exposesResult

    /**
     * 완료 전 응답 — **결과 필드는 하나도 실리지 않고** 배열 둘은 빈 목록이다(X-E3).
     *
     * **형식 셋은 여기서도 실린다.** 문서 메타라 변환 완료 여부와 무관하고, 계약이
     * `ConversionResponse` 설명에서 「형식 셋은 결과 필드가 아니다」로 그것을 고정한다 —
     * 실패한 변환 화면에서도 「이 문서는 DOCX 였다」는 사실은 여전히 참이다.
     */
    private fun beforeDone(stored: StoredConversion): ConversionView =
        ConversionView(
            id = stored.id,
            documentId = stored.documentId,
            status = stored.status,
            sourceFormat = stored.sourceFormat,
            exportFormat = ExportFormat.ofSource(stored.sourceFormat),
            exportFormatChoices = ExportFormat.choicesFor(stored.sourceFormat),
            formatPreservation = beforeDonePreservation(stored),
            easyText = null,
            editedText = null,
            reviewedAt = null,
            // 완료 전 변환에는 피드백을 낼 수 없다(#15 의 409). 행이 있을 수 없으므로
            // 읽어 온 값을 쓰지 않고 여기서도 결과 필드와 함께 비운다.
            feedbackSubmittedAt = null,
            maskedItems = emptyList(),
            missingPlaceholders = emptyList(),
            segmentMap = null,
            model = null,
            providerName = null,
            inputTokens = null,
            outputTokens = null,
            failureCode = stored.failureCode,
        )

    private fun completed(
        stored: StoredConversion,
        opened: OriginalDocument?,
        source: StoredSourceText?,
    ): ConversionView {
        val easyText = open(stored.id, stored.ciphertexts.easyText, EncryptedField.CONVERSION_EASY_TEXT)
        val editedText = open(stored.id, stored.ciphertexts.editedText, EncryptedField.CONVERSION_EDITED_TEXT)
        val body = editedText ?: easyText
        return ConversionView(
            id = stored.id,
            documentId = stored.documentId,
            status = stored.status,
            sourceFormat = stored.sourceFormat,
            exportFormat = ExportFormat.ofSource(stored.sourceFormat),
            exportFormatChoices = ExportFormat.choicesFor(stored.sourceFormat),
            formatPreservation = donePreservation(stored, opened, body),
            easyText = easyText,
            editedText = editedText,
            reviewedAt = stored.reviewedAt,
            feedbackSubmittedAt = stored.feedbackSubmittedAt,
            maskedItems =
                open(stored.id, stored.ciphertexts.maskedItems, EncryptedField.CONVERSION_MASKED_ITEMS)
                    ?.let(maskedItems::decode)
                    ?: emptyList(),
            missingPlaceholders = stored.missingPlaceholders,
            segmentMap = segmentMapOf(source, body),
            model = stored.model,
            providerName = stored.providerName,
            inputTokens = stored.inputTokens,
            outputTokens = stored.outputTokens,
            failureCode = stored.failureCode,
        )
    }

    /**
     * `segment_map` 을 유도한다 — 계획 §2 결정 2, §3. **저장하지 않는다**: 매 조회마다
     * (마스킹된 원문, 검수본 ?? 초안)에서 [alignSegments] 로 다시 계산한다.
     *
     * 앵커(마스킹 자리표시자·사실)가 원문·본문 양쪽에서 성립하려면 **같은 마스킹을 원문에
     * 다시 적용해야 한다** — `ConvertDocumentUseCase.Pass.run` 이 LLM 에 넘긴 것이 마스킹된
     * 원문이고, 그 결과 본문에 남는 것도 그 마스킹이 심은 자리표시자이기 때문이다
     * (`maskText` 는 결정적이고 줄 수를 바꾸지 않으므로 색인이 원문 그대로와도 일치한다).
     *
     * [source] 가 없거나(원문 행이 만료·삭제로 사라진 경합) [body] 가 없으면(초안도 검수본도
     * 없는 완료 행 — 오늘은 나올 수 없는 갈래) `null` 로 접는다. 예외로 튀지 않는다 — 이
     * 필드는 파생값이고, 조회 자체를 막을 이유가 아니다.
     *
     * 앵커는 **조회 시점의 현재 마스킹 규칙**으로 다시 만든다 — 저장된 값이 아니다. 그래서
     * 변환을 만든 이후에 마스킹 규칙이 바뀌면, 오래된 변환은 앵커가 더 적게 잡혀 `low`
     * confidence 로 보일 수 있다. 색인(`sourceUnitIndexes`, 줄 수 불변식)은 절대 깨지지
     * 않는다 — 다시 계산해도 어긋나는 건 confidence 뿐이다.
     */
    private fun segmentMapOf(
        source: StoredSourceText?,
        body: PlainBody?,
    ): SegmentMap? {
        if (source == null || body == null) return null
        val sourceText = cipher.decrypt(source.sourceText, source.documentId, EncryptedField.DOCUMENT_SOURCE_TEXT)
        val maskedSource = maskText(sourceText.value).maskedText.value
        return alignSegments(splitUnits(maskedSource), splitUnits(body.value))
    }

    /**
     * 완료 **전**의 서식 유지 판정.
     *
     * 선택지가 있는 원본(PDF)과 되살릴 원본이 없는 문서는 지금 이미 확실하다 — 그 판정은
     * 영구히 참이고 검수본을 기다릴 이유가 없다. 그 밖에 원본이 있으면 `null` 이고, 그것은
     * 「유지 불가」가 아니라 **아직 판정하지 않았다**는 뜻이다: 판정의 다른 한쪽인 검수본이
     * 아직 없고, 없는 값으로 세는 짝은 추측이다.
     *
     * **`checking` 을 내지 않는다.** 이 판정은 조회 한 번 안에서 동기로 끝나므로 클라이언트가
     * 지켜볼 진행 상태가 없다 — 여기서 「확인 중」을 내면 변환이 끝나야만 끝나는 스피너를
     * 서식 이름으로 약속하게 된다.
     */
    private fun beforeDonePreservation(stored: StoredConversion): FormatPreservation? =
        when {
            ExportFormat.choicesFor(stored.sourceFormat).isNotEmpty() -> choiceExportPreservation()
            !stored.hasStoredOriginal -> noOriginalPreservation()
            else -> null
        }

    /**
     * 완료 **후**의 서식 유지 판정 — 내보내기가 **실제로 할 반영**을 미리 재서 말한다.
     *
     * 판정과 내보내기가 같은 [OriginalStructureReflector] 를 지나고 그 안에서 같은 자리
     * 맞춤을 쓰므로, 여기서 말한 개수와 파일에 실제로 들어가는 개수가 갈릴 수 없다.
     *
     * 선택지가 있는 원본(PDF)은 언제나 `not_applicable` 이다 — 원본을 열어 반영한다는
     * 개념 자체가 적용되지 않는다(2.6.0 재결정, [choiceExportPreservation]). 「유지 불가」로
     * 접지 않는 이유는 그것이 원본 구조에 대한 판정이 아니라 애초에 반영을 시도하지 않는다는
     * 사실이기 때문이다.
     */
    private fun donePreservation(
        stored: StoredConversion,
        opened: OriginalDocument?,
        body: PlainBody?,
    ): FormatPreservation? =
        when {
            // 반영이라는 개념 자체가 적용되지 않는다(PDF) — 원본을 열지 않는다.
            ExportFormat.choicesFor(stored.sourceFormat).isNotEmpty() -> {
                choiceExportPreservation()
            }

            !stored.hasStoredOriginal -> {
                noOriginalPreservation()
            }

            // 같은 형식으로 내보낼 수단도 선택지도 없다(오늘은 없는 갈래) — 반영 결과를 말할
            // 대상 자체가 없다.
            ExportFormat.ofSource(stored.sourceFormat) == null -> {
                null
            }

            // 완료인데 초안이 없는 행은 내보내기도 열지 못한다(`ConversionExportService.requireDraft`).
            // 셀 문단을 셀 수 없으니 **판정하지 않는다** — 원본 쪽은 멀쩡하므로 「열 수 없다」가 아니다.
            body == null -> {
                null
            }

            // 원본 행이 있다고 읽었는데 같은 트랜잭션에서 열리지 않았다.
            opened == null -> {
                unreadableOriginalPreservation()
            }

            else -> {
                original.reflector
                    .outline(opened, body.value)
                    ?.let(::reflectedPreservation)
                    ?: unreadableOriginalPreservation()
            }
        }

    /** 암호문 한 열을 연다. 열이 비어 있으면 `null` — **빈 문자열로 접지 않는다.** */
    private fun open(
        record: UUID,
        column: EncryptedContent?,
        field: EncryptedField,
    ): PlainBody? = column?.let { cipher.decrypt(it, record, field) }
}
