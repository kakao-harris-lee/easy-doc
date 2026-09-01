package kr.easydoc.infrastructure.quality

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
     */
    fun save(
        documentId: String,
        text: String,
    ) {
        val dir = directory ?: return
        dir.resolve("$documentId$FILE_SUFFIX").writeText(text)
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
         * [env] 를 인자로 받는 이유는 [GoldenLlmLane.plan] 과 같다 — 선택 규칙을 유료 호출
         * 없이 시험하기 위해서다.
         *
         * 판정 순서:
         * ⑴ 디렉터리를 만들 수 없으면 거절한다.
         * ⑵ **비어 있지 않으면 거절한다.** 이전 실행(또는 다른 조건으로 돌린 실행)의
         * 변환문이 이미 있으면, 이번 실행이 건너뛴 문서의 자리를 그 파일이 조용히 채워
         * 이번 실행 결과로 오인하게 된다 — `backend-kotlin/build.gradle.kts` 가 `testLlm` 에
         * `outputs.upToDateWhen { false }` 를 건 이유(「돌리지 않은 값을 돌린 값으로
         * 읽는다」)와 같은 성질의 문제다. **여기서 기존 파일을 지우지 않는다** — 그 파일이
         * 다른 판정의 근거일 수 있다. 이 순서가 [probeWritable] 보다 먼저인 이유는 바로
         * 아래 KDoc에 있다.
         * ⑶ 비어 있어도 쓰기 권한이 없을 수 있으면 거절한다([probeWritable]).
         *
         * 셋 다 **유료 호출을 시작하기 전에** 거절한다. 조용히 넘어가면 사용자는 변환문이
         * 남는 줄 알고(또는 이전 실행 것을 이번 것으로 착각하고) 유료 호출을 다 쓰고
         * 아무것도 못 건진다 — `LaneDictionary.planDirectory`·`LaneDictionary.read` KDoc과
         * 같은 판단이다.
         */
        fun plan(env: (String) -> String?): LaneTranscriptPlan {
            val configured =
                env(DIRECTORY_ENV)?.takeIf(String::isNotBlank)
                    ?: return LaneTranscriptPlan.Ready(LaneTranscript(null))
            val directory = Path.of(configured)
            return try {
                Files.createDirectories(directory)
                rejectIfNotEmpty(configured, directory) ?: run {
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
