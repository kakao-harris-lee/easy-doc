package kr.easydoc.infrastructure.illustration.generation

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.illustration.generation.GeneratedIllustrationAssetRepository
import kr.easydoc.application.illustration.generation.IllustrationGenerationCreditPort
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobRepository
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobRunner
import kr.easydoc.application.illustration.generation.IllustrationGenerationJobWorkerPolicy
import kr.easydoc.application.illustration.generation.IllustrationGenerationLlmCallLedger
import kr.easydoc.application.illustration.generation.ProcessIllustrationGenerationJob

@Suppress("LongParameterList")
internal class IllustrationGenerationWorkerFactory(
    private val jobs: IllustrationGenerationJobRepository,
    private val credits: IllustrationGenerationCreditPort,
    private val ledger: IllustrationGenerationLlmCallLedger,
    private val assets: GeneratedIllustrationAssetRepository,
    private val cipher: ContentCipher,
    private val transaction: TransactionRunner,
) {
    fun create(
        runner: IllustrationGenerationJobRunner,
        policy: IllustrationGenerationJobWorkerPolicy,
    ): ProcessIllustrationGenerationJob =
        ProcessIllustrationGenerationJob(jobs, credits, ledger, assets, cipher, runner, transaction, policy)
}
