package kr.easydoc.infrastructure.quality

import kr.easydoc.core.quality.GoldenDocumentLoader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * 변환 결과 본문을 파일로 남기는 노브. 기본은 꺼짐이다 — [DIRECTORY_ENV] 를 설정했을 때만
 * 문서마다 `<디렉터리>/<문서id>.txt` 로 남긴다.
 *
 * 게이트 ⓪ 판정(`docs/kotlin-redevelopment-backlog.md` §1.2 「022 변환문 실물 확인 전에
 * 판정을 내리지 않는다」)은 팽창비 숫자 하나로 채울 수 없다 — 「충실한 축약」과 「내용을
 * 버린 요약」은 본문을 봐야 갈린다. 그렇다고 레인 리포트·로그에 본문을 실을 수는 없다
 * ([LaneMeasurement] KDoc 「숫자와 문서 id 만 담는다」, CLAUDE.md 관측 규칙). 이 노브는 그
 * 경계를 어기지 않고 본문을 **다른 자리**(파일)에 남기는 이음매다.
 *
 * 실행 디렉터리는 노브가 기본값을 만들지 않는다 — 호출자가 준 경로만 쓴다. 골든 원문은
 * 공개 행정문서지만 변환 결과를 저장소 기본 경로로 흘리지 않기 위해서다.
 */
internal class LaneTranscript private constructor(
    /** `null` 이면 off 다. */
    private val directory: Path?,
) {
    /** 이 실행이 변환문을 남기는가. */
    val enabled: Boolean get() = directory != null

    /** 측정 조건 요약에 붙는 한 줄. 본문은 담지 않는다 — 껐다/켰다와 어디에 썼는지뿐이다. */
    val description: String
        get() = if (directory == null) "transcript=off" else "transcript=$directory"

    /**
     * 변환 결과 본문을 `<디렉터리>/<문서id>.txt` 로 쓴다. off 면 아무 것도 하지 않는다.
     *
     * 변환이 실패한 문서는 쓸 본문이 없으므로 호출하지 않는다 — 그 대신
     * `LaneReport.recordTranscriptSkipped` 로 건너뛴 사실만 남긴다.
     *
     * [documentId] 는 [plan] 이 이미 [rejectUnsafeDocumentIds] 로 걸렀어야 하는 값이다 — 유일한
     * 프로덕션 호출부(`GoldenCorpusLlmEvaluationTest`)가 `plan` 에 넘긴 것과 같은 문서 목록을
     * 돈다. 그래도 여기서 다시 [safeFileNameOrNull] 로 확인하는 이유는, 이 줄이 바로 그
     * 취약점이 있던 자리이기 때문이다 — `plan` 의 사전 검사가 이 함수의 유일한 방어선이
     * 되게 두지 않는다. 통과했어야 할 값이 걸리면 조용히 다른 곳에 쓰는 대신 바로 터뜨린다.
     */
    fun save(
        documentId: String,
        text: String,
    ) {
        val dir = directory ?: return
        val fileName =
            safeFileNameOrNull(documentId, dir)
                ?: error(
                    "문서 id \"$documentId\" 는 변환문 파일명으로 쓸 수 없다 — plan() 이 미리 걸렀어야 " +
                        "한다. LaneTranscript 를 만든 곳을 확인하라.",
                )
        dir.resolve(fileName).writeText(text)
    }

    /**
     * 이 실행의 측정 조건 한 줄을 `<디렉터리>/[CONDITIONS_FILE_NAME]` 로 남긴다. off 면 아무
     * 것도 하지 않는다.
     *
     * 나중에 이 디렉터리를 여는 사람이 **무엇으로 잰 변환문인지** 알아야 한다 — 조건을 바꿔
     * 다시 잰 실행([plan] KDoc 「비어 있지 않으면 거절」이 막는 것과 같은 위험의 나머지
     * 절반)과 섞어 읽지 않기 위해서다. [description] 을 포함해 호출부가 리포트 헤더에
     * 싣는 것과 같은 문자열을 넘기면 되므로, 여기서도 비밀값·본문은 실리지 않는다.
     */
    fun writeConditions(description: String) {
        val dir = directory ?: return
        dir.resolve(CONDITIONS_FILE_NAME).writeText(description)
    }

    companion object {
        /** 문서별 변환 결과를 남길 디렉터리. 미설정이면 남기지 않는다. */
        const val DIRECTORY_ENV: String = "EASYDOC_LANE_TRANSCRIPT_DIR"

        /** 문서 id 하나에 파일 하나 — `<디렉터리>/<id>.txt`. [LaneDictionary.FILE_SUFFIX] 와 같다. */
        const val FILE_SUFFIX: String = ".txt"

        /** 측정 조건을 남기는 파일 이름. [writeConditions] KDoc. */
        const val CONDITIONS_FILE_NAME: String = "conditions.txt"

        private const val PROBE_FILE_NAME: String = ".lane-transcript-probe"

        /**
         * 파일명으로 안전한 문서 id 문법. [GoldenDocumentLoader.SAFE_DOCUMENT_ID] 를 그대로
         * 쓴다 — 이제 그 로더가 코퍼스를 읽는 시점에 같은 문법을 강제하므로, 여기서 따로
         * 문법을 적으면 값 출처가 둘이 된다. 이 검사가 여전히 남아 있는 이유는 [safeFileNameOrNull]
         * KDoc과 [save] KDoc에 있다 — 로더의 사전 검사를 이 함수의 유일한 방어선으로 두지
         * 않기 위해서다.
         */
        private val SAFE_DOCUMENT_ID: Regex = GoldenDocumentLoader.SAFE_DOCUMENT_ID

        /**
         * [env] 를 인자로 받는 이유는 [GoldenLlmLane.plan] 과 같다 — 선택 규칙을 유료 호출
         * 없이 시험하기 위해서다. [documentIds] 를 받는 이유는 [LaneDictionary.plan] 과 같다 —
         * 이 레인이 채점할 문서 id 전체를 계획 시점에 이미 알 수 있으니, 그중 하나라도 파일명으로
         * 위험하면 문서를 하나씩 돌기 전에, 즉 유료 호출을 시작하기 전에 걸러야 한다.
         *
         * 판정 순서:
         * ⑴ 디렉터리를 만들 수 없으면 거절한다.
         * ⑵ **비어 있지 않으면 거절한다.** 이전 실행(또는 다른 조건으로 돌린 실행)의
         * 변환문이 이미 있으면, 이번 실행이 건너뛴 문서의 자리를 그 파일이 조용히 채워
         * 이번 실행 결과로 오인하게 된다 — `backend-kotlin/build.gradle.kts` 가 `testLlm` 에
         * `outputs.upToDateWhen { false }` 를 건 이유(「돌리지 않은 값을 돌린 값으로
         * 읽는다」)와 같은 성질의 문제다. **여기서 기존 파일을 지우지 않는다** — 그 파일이
         * 다른 판정의 근거일 수 있다.
         * ⑶ **문서 id 중 하나라도 파일명으로 위험하면 거절한다**([rejectUnsafeDocumentIds]) —
         * 경로 순회, 예약 파일 이름과의 충돌, 같은 실행 안에서의 id 중복 셋 다 이 자리에서
         * 잡는다. 이 검사는 파일시스템을 건드리지 않으므로 [probeWritable] 보다 먼저 두어도
         * 안전하다.
         * ⑷ 비어 있어도 쓰기 권한이 없을 수 있으면 거절한다([probeWritable]). 이 순서가
         * ⑵ 보다 뒤인 이유는 바로 그 함수 KDoc에 있다.
         *
         * 넷 다 **유료 호출을 시작하기 전에** 거절한다. 조용히 넘어가면 사용자는 변환문이
         * 남는 줄 알고(또는 이전 실행 것을 이번 것으로 착각하고, 또는 다른 문서의 변환문이나
         * 측정 조건 파일을 조용히 덮어쓴 채로) 유료 호출을 다 쓰고 아무것도 못 건진다 —
         * `LaneDictionary.planDirectory`·`LaneDictionary.read` KDoc과 같은 판단이다.
         */
        fun plan(
            env: (String) -> String?,
            documentIds: List<String>,
        ): LaneTranscriptPlan {
            val configured =
                env(DIRECTORY_ENV)?.takeIf(String::isNotBlank)
                    ?: return LaneTranscriptPlan.Ready(LaneTranscript(null))
            val directory = Path.of(configured)
            return try {
                Files.createDirectories(directory)
                rejectIfNotEmpty(configured, directory)
                    ?: rejectUnsafeDocumentIds(documentIds, directory)
                    ?: run {
                        probeWritable(directory)
                        LaneTranscriptPlan.Ready(LaneTranscript(directory))
                    }
            } catch (exc: IOException) {
                LaneTranscriptPlan.Unusable(
                    "$DIRECTORY_ENV=$configured 에 쓸 수 없다: ${exc.message} — 이대로 돌면 변환문 없이 " +
                        "유료 호출을 시작하게 된다.",
                )
            }
        }

        /** [save] 가 실제로 쓸 파일 이름. 검증과 쓰기가 같은 계산을 쓰게 한다. */
        private fun fileNameFor(documentId: String): String = "$documentId$FILE_SUFFIX"

        /**
         * [documentId] 가 [directory] 안에 안전하게 `<id>.txt` 로 쓸 수 있으면 그 파일 이름을,
         * 아니면 `null` 을 돌려준다. [rejectUnsafeDocumentIds] 의 계획 시점 검사와 [save] 의
         * 쓰기 시점 검사가 같은 판정을 쓰게 하는 자리다.
         */
        private fun safeFileNameOrNull(
            documentId: String,
            directory: Path,
        ): String? {
            val fileName = fileNameFor(documentId)
            val normalizedDirectory = directory.normalize()
            val staysInsideDirectory = normalizedDirectory.resolve(fileName).normalize().parent == normalizedDirectory
            val isSafe =
                SAFE_DOCUMENT_ID.matches(documentId) &&
                    fileName != CONDITIONS_FILE_NAME &&
                    fileName != PROBE_FILE_NAME &&
                    staysInsideDirectory
            return fileName.takeIf { isSafe }
        }

        /**
         * [plan] KDoc 판정 순서 ⑶. **[rejectIfNotEmpty] 뒤, [probeWritable] 앞**에 부른다 —
         * 파일시스템을 건드리지 않는 순수 검사라 순서가 중요하지 않지만, 어차피 값싼 검사를
         * 값비싼(디스크 I/O) 검사보다 먼저 두는 편이 실패 시 사용자에게 더 정확한 이유를
         * 준다.
         *
         * 셋을 본다.
         *
         * ⑴ **경로 순회.** [SAFE_DOCUMENT_ID] 가 허용하지 않는 문자(특히 `/`, `\`)가 하나라도
         * 있으면 거절한다. 원래 결함은 문서 id `../report` 가 `<디렉터리>/../report.txt` 로
         * 풀려 디렉터리 밖에 쓰던 것이었다 — 이 문법 검사가 그 경로를 막는다.
         * ⑵ **정규화된 경로가 실제로 [directory] 바로 아래인지.** 문법 검사만으로는 놓치는
         * 경우를 대비한 두 번째 층이다 — 지금은 ⑴ 을 통과한 id 가 이 검사에서 걸릴 일이
         * 없지만(구분자가 아예 없으므로), [FILE_SUFFIX] 나 허용 문자 집합이 나중에 바뀌어도
         * 이 검사가 여전히 디렉터리 이탈을 잡아 준다.
         * ⑶ **예약 파일 이름과의 충돌.** `<id>.txt` 가 [CONDITIONS_FILE_NAME] 이나
         * [PROBE_FILE_NAME] 과 같아지면 거절한다 — id `conditions` 가 이 레인의 측정 조건
         * 파일을 변환문으로 덮어쓰는 경로다. 유료 호출이 시작된 뒤에야 측정 출처 기록이
         * 사라지는 쪽이 훨씬 아프므로 계획 시점에 막는다.
         *
         * 마지막으로 **같은 실행 안에서의 id 중복**을 본다. [rejectIfNotEmpty] 는 디렉터리에
         * *이미 있는* 파일만 막는다 — 코퍼스 자체에 같은 id 가 두 번 있으면 그 방어를 비켜
         * 간다. 정상 흐름에서는 절대 나면 안 되는 충돌이므로(코퍼스는 문서 id 로 파일 하나씩
         * 대응해야 한다), 이 시점에 나면 그 자체가 코퍼스 이상 신호다 — 조용히 두 번째
         * 문서가 첫 문서의 변환문을 지우게 두지 않고 거절한다.
         */
        private fun rejectUnsafeDocumentIds(
            documentIds: List<String>,
            directory: Path,
        ): LaneTranscriptPlan.Unusable? {
            val unsafeId = documentIds.firstOrNull { safeFileNameOrNull(it, directory) == null }
            val duplicates =
                documentIds
                    .groupingBy { it }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
            return when {
                unsafeId != null -> unsafeDocumentId(unsafeId)
                duplicates.isNotEmpty() -> duplicateDocumentIds(duplicates)
                else -> null
            }
        }

        /** [rejectUnsafeDocumentIds] KDoc ⑴⑵⑶ — [id] 가 파일명으로 안전하지 않을 때의 거절 사유. */
        private fun unsafeDocumentId(id: String): LaneTranscriptPlan.Unusable =
            LaneTranscriptPlan.Unusable(
                "문서 id \"$id\" 를 변환문 파일명으로 쓸 수 없다 — 영숫자·`.`·`_`·`-` 만 허용하고, " +
                    "`${CONDITIONS_FILE_NAME}`·`${PROBE_FILE_NAME}` 과 겹쳐서도 안 된다. 이대로 돌면 " +
                    "디렉터리 밖에 쓰거나 이 레인의 측정 조건 파일을 덮어쓴 채로 유료 호출을 시작하게 " +
                    "된다. 코퍼스의 이 문서 id 를 고쳐라.",
            )

        /** [rejectUnsafeDocumentIds] KDoc 마지막 문단 — 코퍼스 안 [ids] 중복의 거절 사유. */
        private fun duplicateDocumentIds(ids: Set<String>): LaneTranscriptPlan.Unusable =
            LaneTranscriptPlan.Unusable(
                "문서 id 가 코퍼스 안에서 중복된다: ${ids.sorted().joinToString()} — 이대로 돌면 같은 " +
                    "이름의 파일을 나중 문서가 조용히 덮어써 앞선 문서의 변환문이 사라진 채로 유료 호출을 " +
                    "시작하게 된다. 코퍼스의 id 중복을 없애라.",
            )

        /**
         * [plan] KDoc 판정 순서 ⑵. **[probeWritable] 보다 먼저 불러야 한다** — 그 쪽이 먼저
         * 쓰고 지우는 확인용 파일([PROBE_FILE_NAME])이 남아 있으면, 진짜로 비어 있던
         * 디렉터리조차 "비어 있지 않다" 로 잘못 거절하게 된다. 순서를 이렇게 두면 이 판정이
         * 실행될 때 디렉터리는 아직 확인용 파일도 쓰기 전이라 그 걱정이 없다.
         */
        private fun rejectIfNotEmpty(
            configured: String,
            directory: Path,
        ): LaneTranscriptPlan.Unusable? =
            Files.newDirectoryStream(directory).use { entries ->
                if (entries.iterator().hasNext()) {
                    LaneTranscriptPlan.Unusable(
                        "$DIRECTORY_ENV=$configured 이 비어 있지 않다 — 이전 실행(또는 다른 조건으로 돌린 " +
                            "실행)의 변환문이 남아 있으면 이번 실행 결과로 오인하게 된다. 빈 디렉터리를 새로 " +
                            "지정하거나 기존 파일을 다른 곳으로 옮겨라.",
                    )
                } else {
                    null
                }
            }

        /**
         * 디렉터리가 있어도 쓰기 권한이 없을 수 있다 — 빈 파일 하나를 쓰고 지워서 미리
         * 확인한다. [Files.createDirectories] 만으로는 이미 있는 디렉터리의 쓰기 권한까지
         * 확인하지 못한다.
         */
        private fun probeWritable(directory: Path) {
            val probe = directory.resolve(PROBE_FILE_NAME)
            probe.writeText("")
            Files.delete(probe)
        }
    }
}

/** 변환문을 보존할 수 있는가. [LaneDictionaryPlan] 과 같은 어휘를 쓴다. */
internal sealed interface LaneTranscriptPlan {
    /** 정했다. 남기지 않는 실행(off)도 여기로 온다 — 오류가 아니라 기본 조건이다. */
    class Ready(val transcript: LaneTranscript) : LaneTranscriptPlan

    /** 이 설정으로는 변환문을 남길 수 없다. 유료 호출 전에 실패로 알린다. */
    class Unusable(val reason: String) : LaneTranscriptPlan
}
