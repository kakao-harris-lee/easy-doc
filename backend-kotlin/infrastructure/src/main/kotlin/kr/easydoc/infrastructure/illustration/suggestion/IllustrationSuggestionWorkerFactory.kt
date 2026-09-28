package kr.easydoc.infrastructure.illustration.suggestion

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionCreditPort
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobRepository
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobRunner
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionJobWorkerPolicy
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionLlmCallLedger
import kr.easydoc.application.illustration.suggestion.IllustrationSuggestionResultRepository
import kr.easydoc.application.illustration.suggestion.ProcessIllustrationSuggestionJob

/** 세 worker profile이 공유하는 그림 제안 처리기 조립부. 실행기는 profile에서 선택한다. */
@Suppress("LongParameterList")
internal class IllustrationSuggestionWorkerFactory(
    private val jobs: IllustrationSuggestionJobRepository,
    private val credits: IllustrationSuggestionCreditPort,
    private val ledger: IllustrationSuggestionLlmCallLedger,
    private val results: IllustrationSuggestionResultRepository,
    private val cipher: ContentCipher,
    private val transaction: TransactionRunner,
) {
    fun create(
        runner: IllustrationSuggestionJobRunner,
        policy: IllustrationSuggestionJobWorkerPolicy,
    ): ProcessIllustrationSuggestionJob =
        ProcessIllustrationSuggestionJob(
            jobs,
            credits,
            ledger,
            results,
            cipher,
            runner,
            transaction,
            policy,
        )
}
