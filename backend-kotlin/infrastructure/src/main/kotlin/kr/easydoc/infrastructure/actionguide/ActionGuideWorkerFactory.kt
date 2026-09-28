package kr.easydoc.infrastructure.actionguide

import kr.easydoc.application.actionguide.ActionGuideContentRepository
import kr.easydoc.application.actionguide.ActionGuideCreditPort
import kr.easydoc.application.actionguide.ActionGuideJobRepository
import kr.easydoc.application.actionguide.ActionGuideJobRunner
import kr.easydoc.application.actionguide.ActionGuideJobWorkerPolicy
import kr.easydoc.application.actionguide.ActionGuideLlmCallLedger
import kr.easydoc.application.actionguide.ActionGuideOperation
import kr.easydoc.application.actionguide.GuideAnalysisRepository
import kr.easydoc.application.actionguide.ProcessActionGuideJob
import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher

/** 세 worker profile이 공유하는 행동 안내 처리기 조립부. 실행기는 profile에서 선택한다. */
@Suppress("LongParameterList")
internal class ActionGuideWorkerFactory(
    private val jobs: ActionGuideJobRepository,
    private val credits: ActionGuideCreditPort,
    private val ledger: ActionGuideLlmCallLedger,
    private val contents: ActionGuideContentRepository,
    private val cipher: ContentCipher,
    private val transaction: TransactionRunner,
) {
    fun create(
        runner: ActionGuideJobRunner,
        policy: ActionGuideJobWorkerPolicy,
        analyses: GuideAnalysisRepository?,
        analysisEnabled: Boolean,
    ): ProcessActionGuideJob =
        ProcessActionGuideJob(
            jobs,
            credits,
            ledger,
            contents,
            cipher,
            runner,
            transaction,
            policy,
            analyses = analyses,
            operationEnabled = { it != ActionGuideOperation.ANALYSIS || analysisEnabled },
        )
}
