package kr.easydoc.application.actionguide

import kr.easydoc.core.actionguide.ActionGuideSourceAnchor
import kr.easydoc.core.actionguide.ExtractedGuideAction
import kr.easydoc.core.actionguide.GuideActionPresence
import kr.easydoc.core.actionguide.GuideAnalysisResult
import kr.easydoc.core.actionguide.GuideCoverageStatus
import kr.easydoc.core.actionguide.GuideInformation
import kr.easydoc.core.actionguide.GuideInformationStatus
import kr.easydoc.core.actionguide.GuideOutputMode
import kr.easydoc.core.actionguide.GuideSourceUnit
import kr.easydoc.core.actionguide.GuideSuitability
import kr.easydoc.core.actionguide.GuideUnitAssessment
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class GuideWorkflowTest {
    private val owner = UUID.randomUUID()
    private val conversion = UUID.randomUUID()
    private val source = "대상자는 2025년 3월에 신청한 사람입니다.\n방문하기 1주 전에 담당자에게 문의하세요."
    private val absent = GuideInformation(GuideInformationStatus.NOT_IN_SOURCE, null, emptyList())
    private val action =
        ExtractedGuideAction(
            "contact",
            info("담당자에게 문의하세요.", 1, "담당자에게 문의하세요."),
            absent,
            info("2025년 3월에 신청한 사람", 0, "2025년 3월에 신청한 사람"),
            listOf(info("방문하기 1주 전", 1, "방문하기 1주 전")),
            absent,
            absent,
            absent,
            emptyList(),
            emptyList(),
        )

    private fun info(
        text: String,
        unit: Int,
        quote: String,
    ) = GuideInformation(GuideInformationStatus.PRESENT, text, listOf(ActionGuideSourceAnchor(listOf(unit), quote)))

    private fun snapshot(body: String = source): GuideAnalysisSnapshot {
        val result =
            GuideAnalysisResult(
                GuideSuitability.GUIDE,
                GuideActionPresence.FOUND,
                "문의할 행동이 있습니다",
                emptyList(),
                listOf(action),
                listOf(
                    GuideUnitAssessment(0, GuideCoverageStatus.ACTION, listOf("contact")),
                    GuideUnitAssessment(1, GuideCoverageStatus.ACTION, listOf("contact")),
                ),
                emptyList(),
                false,
            )
        val snapshot =
            GuideAnalysisSnapshot(
                UUID.randomUUID(),
                1,
                1,
                source.lines().mapIndexed {
                    index,
                    text,
                    ->
                    GuideSourceUnit(index, text)
                },
                body,
                "grade_3_4",
                result,
                Instant.now(),
                "provider",
                "grounded-v1",
            )
        return snapshot.copy(signals = GuideReviewSignals.create(snapshot))
    }

    @Test
    fun `review signal ids remain path safe for arbitrary action ids`() {
        val original = snapshot()
        val unusualAction = action.copy(id = "visit/phone?#")
        val snapshot = original.copy(result = original.result.copy(actions = listOf(unusualAction)))
        val signals = GuideReviewSignals.create(snapshot).filter { it.kind == "missing_information" }
        assertThat(signals).hasSize(4)
        assertThat(signals.map { it.id }).allMatch { it.matches(Regex("[a-z0-9-]+")) }
        assertThat(signals.map { it.actionId }).containsOnly(unusualAction.id)
        assertThat(signals.map { it.id }.distinct()).hasSize(signals.size)
    }

    @Test
    fun `provider completion cannot waive extraction absence or original body checks`() {
        val snapshot = snapshot("담당자에게 문의하세요.")
        assertThat(snapshot.signals.filter { it.kind == "fact_difference" }).hasSize(1)
        assertThat(snapshot.signals.filter { it.kind == "missing_information" }).hasSize(4)
        val rows = AnalysisRows(snapshot)
        val review = GuideAnalysisReviewService(rows, DirectTransaction())
        assertThatThrownBy {
            review.review(owner, conversion, snapshot.analysisId, GuideReviewRevision(1, 1, 0))
        }.isInstanceOf(ConflictException::class.java)
        assertThatThrownBy {
            review.resolve(
                owner,
                conversion,
                snapshot.analysisId,
                GuideReviewRevision(1, 1, 0),
                "missing-facts",
                "확인",
                emptyList(),
                null,
            )
        }.isInstanceOf(InvalidInputException::class.java)
        assertThatThrownBy {
            review.resolve(
                owner,
                conversion,
                snapshot.analysisId,
                GuideReviewRevision(1, 1, 0),
                "source-0",
                "확인",
                listOf(0),
                "존재하지 않는 인용",
            )
        }.isInstanceOf(InvalidInputException::class.java)
    }

    @Test
    fun `additional can preserve action conditions while full blocks unresolved original body omissions`() {
        val initial = snapshot("담당자에게 문의하세요.")
        val resolved =
            initial.copy(
                signals =
                    initial.signals.map {
                        it.copy(
                            resolved =
                                it.kind !in setOf("source_body", "fact_difference"),
                        )
                    },
            )
        val rows = AnalysisRows(resolved)
        val reviewed =
            GuideAnalysisReviewService(
                rows,
                DirectTransaction(),
            ).review(owner, conversion, initial.analysisId, GuideReviewRevision(1, 1, 0))
        assertThat(reviewed.allowedModes).containsExactly(GuideOutputMode.ADDITIONAL_GUIDE)
        val service = GuideDraftService(true, rows, DraftRows(), DirectTransaction())
        val draft =
            service.create(
                owner,
                conversion,
                UUID.randomUUID(),
                initial.analysisId,
                GuideReviewRevision(1, 1, 1),
                GuideOutputMode.ADDITIONAL_GUIDE,
            )
        assertThat(draft.body).contains("2025년 3월", "방문하기 1주 전", "원문에 적혀 있지 않음")
        assertThatThrownBy {
            service.create(
                owner,
                conversion,
                UUID.randomUUID(),
                initial.analysisId,
                GuideReviewRevision(1, 1, 1),
                GuideOutputMode.FULL_DOCUMENT,
            )
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    @Suppress("LongMethod") // Exercise create, review and apply against the same immutable body fixture.
    fun `full draft preserves current edits exactly and review and apply bind all revisions`() {
        val initial = snapshot(source + "\n담당자가 추가한 배경입니다.")
        val verified =
            initial.copy(
                reviewed = true,
                reviewRevision = 9,
                result =
                    initial.result.copy(
                        extractionReviewComplete = true,
                    ),
                signals =
                    initial.signals.map {
                        it.copy(resolved = true)
                    },
            )
        val rows = AnalysisRows(verified)
        val drafts = DraftRows()
        val service = GuideDraftService(true, rows, drafts, DirectTransaction())
        val request = UUID.randomUUID()
        val draft =
            service.create(
                owner,
                conversion,
                request,
                initial.analysisId,
                GuideReviewRevision(1, 1, 9),
                GuideOutputMode.FULL_DOCUMENT,
            )
        assertThat(draft.body).startsWith(verified.savedBody + "\n\n행동 안내")
        assertThat(
            service.create(
                owner,
                conversion,
                request,
                initial.analysisId,
                GuideReviewRevision(1, 1, 9),
                GuideOutputMode.FULL_DOCUMENT,
            ),
        ).isEqualTo(draft)
        assertThatThrownBy {
            service.requireApplicable(
                owner,
                conversion,
                GuideDraftApplyCommand(draft.draftId, UUID.randomUUID(), 1, 1, 1, 9),
            )
        }.isInstanceOf(ConflictException::class.java)
        val confirmed =
            service.review(
                owner,
                conversion,
                draft.draftId,
                GuideReviewRevision(1, 1, 9),
                1,
                listOf("action-contact"),
            )
        assertThat(
            service
                .requireApplicable(
                    owner,
                    conversion,
                    GuideDraftApplyCommand(draft.draftId, UUID.randomUUID(), 1, 1, confirmed.draftRevision, 9),
                ).value,
        ).isEqualTo(draft.body)
        rows.snapshot = verified.copy(reviewRevision = 10)
        assertThatThrownBy {
            service.export(
                owner,
                conversion,
                draft.draftId,
            )
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `grounded correction resets every prior review and supports false absence repair`() {
        val initial = snapshot()
        val rows =
            AnalysisRows(initial.copy(reviewed = true, signals = initial.signals.map { it.copy(resolved = true) }))
        val corrected = initial.result.copy(actions = listOf(action.copy(actor = info("대상자", 0, "대상자"))))
        val result =
            GuideAnalysisReviewService(
                rows,
                DirectTransaction(),
            ).correct(owner, conversion, initial.analysisId, GuideReviewRevision(1, 1, 0), corrected)
        assertThat(result.snapshot.analysisRevision).isEqualTo(2)
        assertThat(result.snapshot.signals).allMatch { !it.resolved }
        assertThat(result.snapshot.reviewed).isFalse()
        assertThat(result.allowedModes).isEmpty()
    }

    @Test
    fun `same provider analysis is reused before review without another reservation and fake keys cannot collide`() {
        val jobs = FakeActionGuideJobs(defaultContext().copy(contentRevision = 1))
        jobs.rows[JOB] =
            storedJob(status = kr.easydoc.core.actionguide.ActionGuideJobStatus.SUCCEEDED)
                .copy(operation = ActionGuideOperation.ANALYSIS, basedOnContentRevision = 1)
        val credits = RecordingActionGuideCredits()
        val transaction = DirectTransaction(jobs)
        val jobService = ActionGuideJobService(true, jobs, credits, transaction, analysisEnabled = true)
        val rows = AnalysisRows(snapshot().copy(originJobId = JOB))
        val intake = GuideAnalysisIntakeService(true, rows, jobService, jobs, transaction)
        val alias = UUID.randomUUID()
        assertThat(intake.create(OWNER, CONVERSION, alias, 1).job.jobId).isEqualTo(JOB)
        assertThat(intake.create(OWNER, CONVERSION, alias, 1).job.jobId).isEqualTo(JOB)
        assertThat(credits.reserveCalls).isZero()
        assertThat(jobs.rows).hasSize(1)
        assertThatThrownBy { intake.create(OWNER, CONVERSION, alias, 2) }.isInstanceOf(ConflictException::class.java)
        rows.snapshot = rows.snapshot.copy(provenance = "fake", originJobId = null)
        assertThatThrownBy { intake.create(OWNER, CONVERSION, alias, 1) }.isInstanceOf(ConflictException::class.java)
        assertThat(credits.reserveCalls).isZero()
    }

    private class AnalysisRows(var snapshot: GuideAnalysisSnapshot) : GuideAnalysisRepository {
        private val aliases = mutableSetOf<UUID>()

        override fun lockInput(
            ownerId: UUID,
            conversionId: UUID,
        ) = GuideAnalysisInput(
            1,
            snapshot.sourceUnits.joinToString("\n") {
                it.text
            },
            snapshot.savedBody,
            snapshot.readingLevel,
            true,
        )

        override fun findRequest(
            ownerId: UUID,
            conversionId: UUID,
            requestId: UUID,
        ): GuideAnalysisSnapshot? = snapshot.takeIf { requestId in aliases }

        override fun findRevision(
            ownerId: UUID,
            conversionId: UUID,
            revision: Long,
        ) = snapshot

        override fun find(
            ownerId: UUID,
            conversionId: UUID,
            analysisId: UUID,
        ) = snapshot.takeIf {
            it.analysisId ==
                analysisId
        }

        override fun latest(
            ownerId: UUID,
            conversionId: UUID,
        ) = snapshot

        override fun insert(
            ownerId: UUID,
            conversionId: UUID,
            snapshot: GuideAnalysisSnapshot,
        ) {
            this.snapshot =
                snapshot
        }

        override fun bindRequest(
            ownerId: UUID,
            conversionId: UUID,
            requestId: UUID,
            analysisId: UUID,
        ) {
            aliases.add(requestId)
        }

        override fun replace(
            ownerId: UUID,
            conversionId: UUID,
            expectedAnalysisRevision: Long,
            expectedReviewRevision: Long,
            snapshot: GuideAnalysisSnapshot,
        ): Boolean {
            if (this.snapshot.analysisRevision != expectedAnalysisRevision ||
                this.snapshot.reviewRevision != expectedReviewRevision
            ) {
                return false
            }
            this.snapshot = snapshot
            return true
        }
    }

    private class DraftRows : GuideDraftRepository {
        private val rows = mutableMapOf<UUID, GuideDraft>()
        private val requests = mutableMapOf<UUID, UUID>()

        override fun findOwned(
            ownerId: UUID,
            conversionId: UUID,
            draftId: UUID,
        ) = rows[draftId]

        override fun listOwned(
            ownerId: UUID,
            conversionId: UUID,
        ) = rows.values.toList()

        override fun findRequest(
            ownerId: UUID,
            conversionId: UUID,
            requestId: UUID,
        ) = requests[requestId]?.let(rows::get)

        override fun insertOwned(
            ownerId: UUID,
            conversionId: UUID,
            requestId: UUID,
            draft: GuideDraft,
        ) {
            rows[draft.draftId] =
                draft
            requests[requestId] = draft.draftId
        }

        override fun replaceOwned(
            ownerId: UUID,
            conversionId: UUID,
            expectedDraftRevision: Long,
            draft: GuideDraft,
        ): Boolean {
            rows[draft.draftId] =
                draft
            return true
        }
    }
}
