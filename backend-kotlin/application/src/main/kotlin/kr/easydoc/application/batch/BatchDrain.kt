package kr.easydoc.application.batch

/**
 * Executes a batch operation until the last batch is shorter than its requested size.
 *
 * Each caller owns the batch result and its accumulator because the result semantics differ
 * between purge jobs (some counters are cumulative while others describe the last batch). The
 * runner only centralizes the repeat and safety-boundary mechanics.
 */
internal fun <Batch> drainBatches(
    batchSize: Int,
    overflowMessage: (maxRounds: Int) -> String,
    nextBatch: () -> Batch,
    shouldContinue: (batch: Batch, batchSize: Int) -> Boolean,
    consume: (Batch) -> Unit,
) {
    var rounds = 0
    do {
        rounds++
        check(rounds <= MAX_BATCH_ROUNDS) { overflowMessage(MAX_BATCH_ROUNDS) }
        val batch = nextBatch()
        consume(batch)
    } while (shouldContinue(batch, batchSize))
}

private const val MAX_BATCH_ROUNDS: Int = 10_000
