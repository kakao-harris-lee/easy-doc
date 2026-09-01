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

    companion object {
        /** 문서별 변환 결과를 남길 디렉터리. 미설정이면 남기지 않는다. */
        const val DIRECTORY_ENV: String = "EASYDOC_LANE_TRANSCRIPT_DIR"

        /** 문서 id 하나에 파일 하나 — `<디렉터리>/<id>.txt`. [LaneDictionary.FILE_SUFFIX] 와 같다. */
        const val FILE_SUFFIX: String = ".txt"

        private const val PROBE_FILE_NAME: String = ".lane-transcript-probe"

        /**
         * [env] 를 인자로 받는 이유는 [GoldenLlmLane.plan] 과 같다 — 선택 규칙을 유료 호출
         * 없이 시험하기 위해서다.
         *
         * 디렉터리를 만들 수 없거나 만들었어도 쓸 수 없으면 **유료 호출을 시작하기 전에**
         * 거절한다. 조용히 넘어가면 사용자는 변환문이 남는 줄 알고 유료 호출을 다 쓰고
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
                probeWritable(directory)
                LaneTranscriptPlan.Ready(LaneTranscript(directory))
            } catch (exc: IOException) {
                LaneTranscriptPlan.Unusable(
                    "$DIRECTORY_ENV=$configured 에 쓸 수 없다: ${exc.message} — 이대로 돌면 변환문 없이 " +
                        "유료 호출을 시작하게 된다.",
                )
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
