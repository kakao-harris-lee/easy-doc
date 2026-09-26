package kr.easydoc.application.actionguide

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.document.MAX_SAFE_REVISION
import kr.easydoc.core.actionguide.GuideAnalysisPolicy
import kr.easydoc.core.actionguide.GuideAnalysisResult
import kr.easydoc.core.actionguide.GuideOutputMode
import kr.easydoc.core.actionguide.GuideSourceUnit
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.segment.splitUnits
import java.time.Instant
import java.util.UUID

/** Private plaintext values must never be included in logs. */
class GuideAnalysisInput(
    val contentRevision: Long,
    val sourceText: String,
    val savedBody: String,
    val readingLevel: String,
    val completed: Boolean,
) {
    override fun toString(): String = "GuideAnalysisInput(revision=$contentRevision)"
}

data class GuideAnalysisSnapshot(
    val analysisId: UUID,
    val basedOnContentRevision: Long,
    val analysisRevision: Long,
    val sourceUnits: List<GuideSourceUnit>,
    val savedBody: String,
    val readingLevel: String,
    val result: GuideAnalysisResult,
    val createdAt: Instant,
    val provenance: String = "fake",
    val analyzerVersion: String = "foundation-v1",
    val reviewRevision: Long = 0,
    val reviewed: Boolean = false,
    val signals: List<GuideReviewSignal> = emptyList(),
    val originJobId: UUID? = null,
) {
    override fun toString(): String = "GuideAnalysisSnapshot(id=$analysisId)"
}

data class GuideAnalysisView(
    val snapshot: GuideAnalysisSnapshot,
    val state: String,
) {
    val allowedModes: List<GuideOutputMode>
        get() {
            if (state != "current") return emptyList()
            val modes = GuideAnalysisPolicy.allowedModes(snapshot.result)
            return if (snapshot.signals.any { !it.resolved && it.kind in setOf("source_body", "fact_difference") }) {
                modes.filter { it == GuideOutputMode.ADDITIONAL_GUIDE }
            } else {
                modes
            }
        }
}

interface GuideAnalysisRepository {
    /** Locks the conversion; all reads/writes must also enforce current ownership and retention. */
    fun lockInput(
        ownerId: UUID,
        conversionId: UUID,
    ): GuideAnalysisInput?

    fun findRequest(
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID,
    ): GuideAnalysisSnapshot?

    fun findRevision(
        ownerId: UUID,
        conversionId: UUID,
        revision: Long,
    ): GuideAnalysisSnapshot?

    fun find(
        ownerId: UUID,
        conversionId: UUID,
        analysisId: UUID,
    ): GuideAnalysisSnapshot?

    fun latest(
        ownerId: UUID,
        conversionId: UUID,
    ): GuideAnalysisSnapshot?

    fun insert(
        ownerId: UUID,
        conversionId: UUID,
        snapshot: GuideAnalysisSnapshot,
    )

    fun replace(
        ownerId: UUID,
        conversionId: UUID,
        expectedAnalysisRevision: Long,
        expectedReviewRevision: Long,
        snapshot: GuideAnalysisSnapshot,
    ): Boolean = false

    fun bindRequest(
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID,
        analysisId: UUID,
    )
}

fun interface GuideAnalyzer {
    fun analyze(
        input: GuideAnalysisInput,
        sourceUnits: List<GuideSourceUnit>,
    ): GuideAnalysisResult
}

/** Only a local/test fake analyzer is connected in ER-28. No provider, credit reservation or generation. */
class ActionGuideAnalysisService(
    private val enabled: Boolean,
    private val repository: GuideAnalysisRepository,
    private val analyzer: GuideAnalyzer,
    private val transaction: TransactionRunner,
) {
    @Suppress("ThrowsCount") // Invalid revisions, idempotency conflicts and stale input have distinct responses.
    fun create(
        ownerId: UUID,
        conversionId: UUID,
        requestId: UUID,
        expectedRevision: Long,
    ): GuideAnalysisView {
        requireEnabled()
        if (expectedRevision !in 1..MAX_SAFE_REVISION) throw InvalidInputException("본문 버전이 올바르지 않습니다")
        return transaction.inTransaction {
            val input = ownedInput(ownerId, conversionId)
            repository.findRequest(ownerId, conversionId, requestId)?.let {
                if (it.basedOnContentRevision != expectedRevision) throw ConflictException("요청 키의 입력이 다릅니다")
                return@inTransaction view(it, input.contentRevision)
            }
            if (!input.completed || input.contentRevision != expectedRevision) {
                throw ConflictException("본문이 바뀌었거나 변환이 완료되지 않았습니다")
            }
            val snapshot =
                repository.findRevision(ownerId, conversionId, expectedRevision) ?: run {
                    val units = splitUnits(input.sourceText).mapIndexed { index, text -> GuideSourceUnit(index, text) }
                    val result = analyzer.analyze(input, units)
                    GuideAnalysisPolicy.validate(result, units.map { it.text })
                    GuideAnalysisSnapshot(
                        UUID.randomUUID(),
                        expectedRevision,
                        1,
                        units,
                        input.savedBody,
                        input.readingLevel,
                        result,
                        Instant.now(),
                    ).also {
                        repository.insert(ownerId, conversionId, it)
                    }
                }
            repository.bindRequest(ownerId, conversionId, requestId, snapshot.analysisId)
            view(snapshot, input.contentRevision)
        }
    }

    fun get(
        ownerId: UUID,
        conversionId: UUID,
        analysisId: UUID,
    ): GuideAnalysisView =
        transaction.inTransaction {
            val input = ownedInput(ownerId, conversionId)
            val snapshot = repository.find(ownerId, conversionId, analysisId) ?: notFound()
            view(snapshot, input.contentRevision)
        }

    fun latest(
        ownerId: UUID,
        conversionId: UUID,
    ): GuideAnalysisView? =
        transaction.inTransaction {
            val input = ownedInput(ownerId, conversionId)
            repository.latest(ownerId, conversionId)?.let { view(it, input.contentRevision) }
        }

    private fun ownedInput(
        ownerId: UUID,
        conversionId: UUID,
    ): GuideAnalysisInput = repository.lockInput(ownerId, conversionId) ?: notFound()

    private fun view(
        snapshot: GuideAnalysisSnapshot,
        revision: Long,
    ): GuideAnalysisView =
        GuideAnalysisView(snapshot, if (snapshot.basedOnContentRevision == revision) "current" else "stale")

    private fun requireEnabled() {
        if (!enabled) notFound()
    }

    private fun notFound(): Nothing = throw NotFoundException("행동 분석을 찾을 수 없습니다")
}
