package kr.easydoc.application.document

import kr.easydoc.application.crypto.ContentCipher
import org.slf4j.LoggerFactory
import java.util.UUID

/** 키 회전 배치 정책. 값은 설정(`easydoc.encryption.rotation.batch-size`)에서 온다. */
class KeyRotationPolicy(val batchSize: Int) {
    init {
        require(batchSize >= 1) { "키 회전 배치 크기가 1보다 작다" }
    }
}

/**
 * 가족 하나(예: `documents`)의 회전 집계. **행 식별자·본문은 담지 않는다** — 로그·감사가
 * 개수만 보게 한다(운영 진입점의 요구사항 「행 id 를 로그에 남기지 않는다」).
 *
 * [rotated]·[skipped]·[remaining] 셋이 로그 어휘다:
 * - [rotated] 는 이번 실행이 새 세대로 다시 봉인한 행 수.
 * - [skipped] 는 애초에 할 일이 없던 행 수([alreadyCurrent]+[missing]+[nothingSealed] —
 *   이미 현재 세대이거나, 회전을 시도한 순간 행이 없었거나, 봉인된 것이 없는 행).
 * - [remaining] 은 [contended] 다 — 동시 쓰기와 겹쳐 이번엔 회전하지 못한 행. 세대가 그대로
 *   남으므로 **다음 실행이 처음부터 다시 훑으며 자동으로 다시 고른다**(재시도를 이 배치가
 *   직접 하지 않는다 — [KeyRotationBatch] KDoc).
 */
class FamilyRotationOutcome(
    val family: String,
    val rotated: Int,
    val alreadyCurrent: Int,
    val missing: Int,
    val contended: Int,
    val nothingSealed: Int,
) {
    val skipped: Int get() = alreadyCurrent + missing + nothingSealed
    val remaining: Int get() = contended

    override fun toString(): String =
        "FamilyRotationOutcome($family, rotated=$rotated, skipped=$skipped, remaining=$remaining)"
}

/** 회전 배치 한 번 전체의 결과 — 가족 넷([SealedStores] 전수)의 집계. */
class KeyRotationResult(val families: List<FamilyRotationOutcome>) {
    override fun toString(): String = "KeyRotationResult($families)"
}

/** 회전 결과를 감사·메트릭으로 남긴다. 개수만 받는다 — 본문·id 는 애초에 이 자리에 없다. */
fun interface KeyRotationObserver {
    fun record(result: KeyRotationResult)
}

/** 가족별 rotated/skipped/remaining 만 남긴다. */
class LoggingKeyRotationObserver : KeyRotationObserver {
    private val log = LoggerFactory.getLogger(LoggingKeyRotationObserver::class.java)

    override fun record(result: KeyRotationResult) {
        result.families.forEach { family ->
            log.info(
                "키 회전 [{}]: rotated={} skipped={} remaining={}",
                family.family,
                family.rotated,
                family.skipped,
                family.remaining,
            )
        }
    }
}

/**
 * [EnvelopeRotation] 의 행 단위 메서드([EnvelopeRotation.rotateDocument] 등)를 [SealedStores]
 * 전수(가족 넷)에 배치로 적용하는 운영 진입점의 핵심 — backlog §1.1 「키 회전에 운영
 * 진입점이 없음」.
 *
 * **행을 어떻게 회전하는가는 여기서 새로 정하지 않는다** — 그것은 [EnvelopeRotation] 이 이미
 * 정했다(잠금·낙관적 조건·`CONTENDED` 판정 전부). 이 클래스가 더하는 것은 후보를 고르고
 * [KeyRotationPolicy.batchSize] 단위로 그것을 순회하는 일뿐이다.
 *
 * **커서로 순회한다, 후보 집합을 다시 훑지 않는다.** 각 저장소의 `idsOlderThan` 류 포트는
 * id 오름차순으로 [after] 뒤의 행만 돌려주고, 이 클래스는 반환된 마지막 id 를 다음 호출의
 * [after] 로 넘긴다. 그래서 한 가족의 순회 횟수는 **그 가족의 시작 시점 후보 수**로 유한하게
 * 정해진다 — [kr.easydoc.application.document.RotationOutcome.CONTENDED] 로 회전에 실패한
 * 행(세대가 그대로라 여전히 후보다)도 커서가 이미 지나갔으므로 같은 실행 안에서 무한히
 * 다시 뽑히지 않는다. 대신 **다음 실행**(새 실행은 커서를 처음부터 다시 세운다)이 그 행을
 * 자연스럽게 다시 고른다 — 그래서 이 배치는 **재시도를 직접 구현하지 않고도** 재시도가
 * 되고, 「다시 실행하면 남은 것만 돈다」가 곧 idempotent 의 근거다.
 */
class KeyRotationBatch(
    private val stores: SealedStores,
    private val rotation: EnvelopeRotation,
    private val cipher: ContentCipher,
    private val policy: KeyRotationPolicy,
    private val observer: KeyRotationObserver,
) {
    fun run(): KeyRotationResult {
        val target = cipher.writeKeyVersion
        val families =
            listOf(
                rotateFamily(
                    family = FAMILY_DOCUMENTS,
                    fetch = { after, limit -> stores.documents.idsOlderThan(target, after, limit) },
                    rotateOne = rotation::rotateDocument,
                ),
                rotateFamily(
                    family = FAMILY_DOCUMENT_ORIGINALS,
                    fetch = { after, limit -> stores.originals.documentIdsOlderThan(target, after, limit) },
                    rotateOne = rotation::rotateDocumentOriginal,
                ),
                rotateFamily(
                    family = FAMILY_CONVERSIONS,
                    fetch = { after, limit -> stores.conversions.idsOlderThan(target, after, limit) },
                    rotateOne = rotation::rotateConversion,
                ),
                rotateFamily(
                    family = FAMILY_CONVERSION_FEEDBACK,
                    fetch = { after, limit -> stores.feedback.conversionIdsOlderThan(target, after, limit) },
                    rotateOne = rotation::rotateFeedback,
                ),
            )
        val result = KeyRotationResult(families)
        observer.record(result)
        return result
    }

    private fun rotateFamily(
        family: String,
        fetch: (after: UUID, limit: Int) -> List<UUID>,
        rotateOne: (UUID) -> RotationOutcome,
    ): FamilyRotationOutcome {
        var rotated = 0
        var alreadyCurrent = 0
        var missing = 0
        var contended = 0
        var nothingSealed = 0
        var cursor = ZERO_UUID
        // 단일 종료 조건(`batch.isNotEmpty()`)으로 두 판단을 합친다 — 「더 없다」와 「이번 배치가
        // 상한보다 작았다(= 다음 조회도 비어 있을 것이다)」를 미리 계산해 다음 반복의 fetch를
        // 건너뛴다. `break` 를 두 곳에 두지 않으면서도 짧은 배치를 만났을 때 낭비 조회
        // 한 번을 그대로 아낀다.
        var batch = fetch(cursor, policy.batchSize)

        while (batch.isNotEmpty()) {
            batch.forEach { id ->
                when (rotateOne(id)) {
                    RotationOutcome.ROTATED -> rotated++
                    RotationOutcome.ALREADY_CURRENT -> alreadyCurrent++
                    RotationOutcome.MISSING -> missing++
                    RotationOutcome.CONTENDED -> contended++
                    RotationOutcome.NOTHING_SEALED -> nothingSealed++
                }
            }
            cursor = batch.last()
            batch = if (batch.size < policy.batchSize) emptyList() else fetch(cursor, policy.batchSize)
        }

        return FamilyRotationOutcome(family, rotated, alreadyCurrent, missing, contended, nothingSealed)
    }

    private companion object {
        const val FAMILY_DOCUMENTS = "documents"
        const val FAMILY_DOCUMENT_ORIGINALS = "document_originals"
        const val FAMILY_CONVERSIONS = "conversions"
        const val FAMILY_CONVERSION_FEEDBACK = "conversion_feedback"

        /** 가장 작은 UUID — 각 가족 순회의 시작 커서. */
        val ZERO_UUID: UUID = UUID(0L, 0L)
    }
}
