package kr.easydoc.application.document

import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.easyread.ReviewItemState
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class ReviewSupportServiceTest {
    @Test
    fun `기능이 꺼지면 조회와 분석 모두 자원 존재를 숨기고 404다`() {
        val world = World(enabled = false)
        val conversionId = world.seedDone()

        assertThatThrownBy { world.service.get(OWNER, conversionId) }
            .isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { world.service.analyze(OWNER, conversionId, 1) }
            .isInstanceOf(NotFoundException::class.java)
        assertThat(world.assessments.insertions).isZero()
        assertThat(world.conversions.depthWhenLocked).isEmpty()
    }

    @Test
    fun `같은 본문과 분석기 버전은 저장된 분석을 재사용한다`() {
        val world = World()
        val conversionId = world.seedDone()

        val first = world.service.analyze(OWNER, conversionId, 1)
        val second = world.service.analyze(OWNER, conversionId, 1)

        assertThat(second.assessment?.assessmentId).isEqualTo(first.assessment?.assessmentId)
        assertThat(world.assessments.insertions).isEqualTo(1)
        assertThat(first.assessment?.items).hasSizeGreaterThanOrEqualTo(5)
    }

    @Test
    fun `modified UTF 한계를 넘는 유효 URL 근거도 저장하고 다시 읽는다`() {
        val world = World()
        val longUrl = "https://example.test/" + "😀".repeat(17_000)
        val conversionId = world.seedDone(sourceText = longUrl, easyText = "주소는 원문에서 확인해 주세요.")

        val generated = world.service.analyze(OWNER, conversionId, 1).assessment!!
        val reopened = world.service.get(OWNER, conversionId).assessment!!

        assertThat(
            generated.items
                .first { it.ruleCode == "missing_email_or_url" }
                .sourceAnchors
                .single()
                .quote,
        ).isEqualTo(longUrl)
        assertThat(reopened).isEqualTo(generated)
    }

    @Test
    fun `분석의 본문 CAS와 항목 갱신의 검수 CAS가 각각 409다`() {
        val world = World()
        val conversionId = world.seedDone()

        assertThatThrownBy { world.service.analyze(OWNER, conversionId, 2) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage(CONTENT_REVISION_CONFLICT_MESSAGE)

        val generated = world.service.analyze(OWNER, conversionId, 1).assessment!!
        assertThatThrownBy {
            world.service.updateItem(
                OWNER,
                conversionId,
                generated.items.first().itemId,
                generated.assessmentId,
                1,
                1,
                ReviewItemState.CONFIRMED,
                null,
            )
        }.isInstanceOf(ConflictException::class.java)
            .hasMessage(REVIEW_REVISION_CONFLICT_MESSAGE)
    }

    @Test
    fun `해당 없음은 사유가 필요하고 성공 저장은 검수 버전을 올린다`() {
        val world = World()
        val conversionId = world.seedDone()
        val generated = world.service.analyze(OWNER, conversionId, 1).assessment!!
        val itemId = generated.items.first().itemId

        assertThatThrownBy {
            world.service.updateItem(
                OWNER,
                conversionId,
                itemId,
                generated.assessmentId,
                1,
                0,
                ReviewItemState.NOT_APPLICABLE,
                "  ",
            )
        }.isInstanceOf(InvalidInputException::class.java)
            .hasMessage(REVIEW_REASON_REQUIRED_MESSAGE)

        val updated =
            world.service
                .updateItem(
                    OWNER,
                    conversionId,
                    itemId,
                    generated.assessmentId,
                    1,
                    0,
                    ReviewItemState.NOT_APPLICABLE,
                    "원문에 해당 조건이 없음",
                ).assessment!!
        assertThat(updated.reviewRevision).isEqualTo(1)
        assertThat(updated.items.first().reason).isEqualTo("원문에 해당 조건이 없음")
        assertThat(updated.items.first().confirmedBy).isEqualTo(OWNER)
    }

    @Test
    fun `항목 상태별 변경은 history event와 당시 본문 산출물을 함께 기록한다`() {
        val world = World()
        val conversionId = world.seedDone()
        val generated = world.service.analyze(OWNER, conversionId, 1).assessment!!
        val itemId = generated.items.first().itemId

        world.service.updateItem(
            OWNER,
            conversionId,
            itemId,
            generated.assessmentId,
            1,
            0,
            ReviewItemState.CONFIRMED,
            null,
        )
        world.service.updateItem(
            OWNER,
            conversionId,
            itemId,
            generated.assessmentId,
            1,
            1,
            ReviewItemState.NEEDS_REVIEW,
            null,
        )
        world.service.updateItem(
            OWNER,
            conversionId,
            itemId,
            generated.assessmentId,
            1,
            2,
            ReviewItemState.NOT_APPLICABLE,
            "원문과 무관",
        )

        assertThat(world.history.itemCalls.map { it.type })
            .containsExactly(
                ReviewHistoryEventType.ITEM_CONFIRMED,
                ReviewHistoryEventType.ITEM_REOPENED,
                ReviewHistoryEventType.ITEM_NOT_APPLICABLE,
            )
        assertThat(world.history.itemCalls).allSatisfy { call ->
            assertThat(call.contentRevision).isEqualTo(1)
            assertThat(call.contentText).isEqualTo("쉬운 글에는 신청 방법만 있습니다.")
            assertThat(call.artifactJson).startsWith("{\"coverage\"")
        }
    }

    @Test
    fun `배치는 여러 항목을 하나의 CAS로 변경하고 항목별 이력을 보존한다`() {
        val world = World(focusedReviewEnabled = true)
        val conversionId =
            world.seedDone(
                sourceText = "신청서와 동의서를 모두 제출하고, 문의는 02-1234-5678로 하세요.",
                easyText = "신청서나 동의서 중 하나만 내세요.",
            )
        val generated = world.service.analyze(OWNER, conversionId, 1).assessment!!
        val itemIds = generated.items.map { it.itemId }

        val updated =
            world.service
                .updateItems(
                    OWNER,
                    conversionId,
                    itemIds,
                    generated.assessmentId,
                    1,
                    0,
                    ReviewItemState.CONFIRMED,
                    null,
                ).assessment!!

        assertThat(updated.reviewRevision).isEqualTo(1)
        assertThat(updated.items).allMatch { it.state == ReviewItemState.CONFIRMED }
        assertThat(world.assessments.updates).isEqualTo(1)
        assertThat(world.history.itemCalls.map { it.itemId }).containsExactlyElementsOf(itemIds)
        assertThat(world.history.itemCalls.map { it.reviewRevision }).containsOnly(1)
    }

    @Test
    fun `배치에 없는 항목이 하나라도 있으면 일부 저장하지 않는다`() {
        val world = World(focusedReviewEnabled = true)
        val conversionId = world.seedDone(sourceText = "문의는 02-1234-5678로 하세요.", easyText = "문의하세요.")
        val generated = world.service.analyze(OWNER, conversionId, 1).assessment!!

        assertThatThrownBy {
            world.service.updateItems(
                OWNER,
                conversionId,
                listOf(generated.items.single().itemId, UUID.randomUUID()),
                generated.assessmentId,
                1,
                0,
                ReviewItemState.CONFIRMED,
                null,
            )
        }.isInstanceOf(NotFoundException::class.java)
            .hasMessage(REVIEW_ITEM_NOT_FOUND_MESSAGE)

        val unchanged = world.service.get(OWNER, conversionId).assessment!!
        assertThat(unchanged.reviewRevision).isZero()
        assertThat(unchanged.items).allMatch { it.state == ReviewItemState.NEEDS_REVIEW }
        assertThat(world.assessments.updates).isZero()
        assertThat(world.history.itemCalls).isEmpty()
    }

    @Test
    fun `배치는 빈 목록과 중복 항목을 저장 전에 거절한다`() {
        val world = World(focusedReviewEnabled = true)
        val conversionId = world.seedDone(sourceText = "문의는 02-1234-5678로 하세요.", easyText = "문의하세요.")
        val generated = world.service.analyze(OWNER, conversionId, 1).assessment!!
        val itemId = generated.items.single().itemId

        assertThatThrownBy {
            world.service.updateItems(
                OWNER,
                conversionId,
                emptyList(),
                generated.assessmentId,
                1,
                0,
                ReviewItemState.CONFIRMED,
                null,
            )
        }.isInstanceOf(InvalidInputException::class.java)
            .hasMessage(REVIEW_ITEM_IDS_INVALID_MESSAGE)
        assertThatThrownBy {
            world.service.updateItems(
                OWNER,
                conversionId,
                listOf(itemId, itemId),
                generated.assessmentId,
                1,
                0,
                ReviewItemState.CONFIRMED,
                null,
            )
        }.isInstanceOf(InvalidInputException::class.java)
            .hasMessage(REVIEW_ITEM_IDS_INVALID_MESSAGE)
        assertThat(world.assessments.updates).isZero()
    }

    @Test
    fun `집중 검토 토글은 분석기 버전을 분리하고 롤백한 스냅샷을 재사용한다`() {
        val legacy = World(focusedReviewEnabled = false)
        val conversionId = legacy.seedDone(sourceText = "신청서를 제출하세요.", easyText = "신청서를 내세요.")
        val legacyAssessment = legacy.service.analyze(OWNER, conversionId, 1).assessment!!
        val focused = legacy.serviceWithFocusedReview(true)
        val focusedAssessment = focused.analyze(OWNER, conversionId, 1).assessment!!
        val rolledBackAssessmentId =
            legacy.service
                .get(OWNER, conversionId)
                .assessment
                ?.assessmentId

        assertThat(legacyAssessment.analyzerVersion).isEqualTo(ReviewSupportService.LEGACY_ANALYZER_VERSION)
        assertThat(focusedAssessment.analyzerVersion).isEqualTo(ReviewSupportService.FOCUSED_ANALYZER_VERSION)
        assertThat(focusedAssessment.items).isEmpty()
        assertThat(rolledBackAssessmentId).isEqualTo(legacyAssessment.assessmentId)
    }

    @Test
    fun `분석과 항목 갱신 revision 하한을 422로 검증한다`() {
        val world = World()
        val conversionId = world.seedDone()

        assertThatThrownBy { world.service.analyze(OWNER, conversionId, 0) }
            .isInstanceOf(InvalidInputException::class.java)
            .hasMessage(CONTENT_REVISION_INVALID_MESSAGE)

        assertThatThrownBy {
            world.service.updateItem(
                OWNER,
                conversionId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                0,
                0,
                ReviewItemState.CONFIRMED,
                null,
            )
        }.isInstanceOf(InvalidInputException::class.java)
            .hasMessage(CONTENT_REVISION_INVALID_MESSAGE)

        assertThatThrownBy {
            world.service.updateItem(
                OWNER,
                conversionId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                -1,
                ReviewItemState.CONFIRMED,
                null,
            )
        }.isInstanceOf(InvalidInputException::class.java)
            .hasMessage(REVIEW_REVISION_INVALID_MESSAGE)
    }

    private class World(
        enabled: Boolean = true,
        focusedReviewEnabled: Boolean = false,
    ) {
        val transaction = RecordingTransactionRunner()
        val cipher = FakeContentCipher(writeKeyVersion = 1, transaction = transaction)
        private val originals = FakeDocumentOriginalRepository(transaction)
        val conversions = FakeConversionRepository(transaction, originals)
        private val documents = FakeQueryDocumentRepository(transaction)
        val assessments = FakeReviewAssessmentRepository()
        val history = RecordingReviewHistoryAppender()
        val service =
            ReviewSupportService(
                enabled,
                conversions,
                documents,
                assessments,
                cipher,
                transaction,
                focusedReviewEnabled = focusedReviewEnabled,
                reviewHistory = history,
            )

        fun serviceWithFocusedReview(enabled: Boolean): ReviewSupportService =
            ReviewSupportService(
                enabled = true,
                conversions = conversions,
                documents = documents,
                assessments = assessments,
                cipher = cipher,
                transaction = transaction,
                focusedReviewEnabled = enabled,
                reviewHistory = history,
            )

        fun seedDone(
            sourceText: String = "신청자는 2026년 10월 1일까지 30,000원을 내야 합니다.",
            easyText: String = "쉬운 글에는 신청 방법만 있습니다.",
        ): UUID {
            val conversionId = UUID.randomUUID()
            val documentId = UUID.randomUUID()
            val easyText =
                cipher.encrypt(
                    PlainBody(easyText),
                    conversionId,
                    EncryptedField.CONVERSION_EASY_TEXT,
                )
            val envelope =
                ConversionEnvelope(
                    conversionId,
                    cipher.writeScheme,
                    cipher.writeKeyVersion,
                    ConversionCiphertexts(easyText, null),
                )
            conversions.lockedForReview[OWNER to conversionId] = LockedConversion(ConversionStatus.DONE, envelope, 1)
            conversions.owned[OWNER to conversionId] =
                StoredConversion(
                    conversionId,
                    documentId,
                    ConversionStatus.DONE,
                    SourceFormat.TEXT,
                    false,
                    envelope.ciphertexts,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    1,
                )
            documents.seed(OWNER, documentId, sourceText)
            return conversionId
        }
    }

    private class FakeReviewAssessmentRepository : ReviewAssessmentRepository {
        private val rows = linkedMapOf<UUID, StoredReviewAssessment>()
        var insertions = 0
            private set
        var updates = 0
            private set

        override fun findLatestOwned(
            ownerId: UUID,
            conversionId: UUID,
        ): StoredReviewAssessment? =
            if (ownerId == OWNER) rows.values.lastOrNull { it.conversionId == conversionId } else null

        override fun findExact(
            ownerId: UUID,
            conversionId: UUID,
            contentRevision: Long,
            analyzerVersion: String,
        ): StoredReviewAssessment? =
            if (ownerId == OWNER) {
                rows.values.singleOrNull {
                    it.conversionId == conversionId && it.contentRevision == contentRevision &&
                        it.analyzerVersion == analyzerVersion
                }
            } else {
                null
            }

        override fun lockOwned(
            ownerId: UUID,
            conversionId: UUID,
            assessmentId: UUID,
        ): StoredReviewAssessment? =
            if (ownerId == OWNER) rows[assessmentId]?.takeIf { it.conversionId == conversionId } else null

        override fun insert(
            ownerId: UUID,
            assessment: StoredReviewAssessment,
        ): Boolean {
            val duplicate =
                findExact(
                    ownerId,
                    assessment.conversionId,
                    assessment.contentRevision,
                    assessment.analyzerVersion,
                ) != null
            return if (ownerId != OWNER || duplicate) {
                false
            } else {
                insertions++
                rows[assessment.assessmentId] = assessment
                true
            }
        }

        override fun update(
            ownerId: UUID,
            assessmentId: UUID,
            expectedReviewRevision: Long,
            payload: EncryptedContent,
            updatedReviewRevision: Long,
        ): Boolean {
            val current = rows[assessmentId]
            if (ownerId != OWNER || current?.reviewRevision != expectedReviewRevision) return false
            updates++
            rows[assessmentId] = current.copy(reviewRevision = updatedReviewRevision, payload = payload)
            return true
        }

        override fun lockEnvelope(assessmentId: UUID): StoredReviewAssessment? = rows[assessmentId]

        override fun rewriteEnvelope(
            expected: StoredReviewAssessment,
            payload: EncryptedContent,
        ): Boolean {
            if (rows[expected.assessmentId] != expected) return false
            rows[expected.assessmentId] = expected.copy(payload = payload)
            return true
        }

        override fun idsOlderThan(
            keyVersion: Int,
            after: UUID,
            limit: Int,
        ): List<UUID> = emptyList()
    }

    private companion object {
        val OWNER: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000c1")
    }
}
