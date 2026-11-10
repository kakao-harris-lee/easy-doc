package kr.easydoc.infrastructure.quality

import kr.easydoc.core.quality.GoldenDocumentLoader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * P0-1 평가 산출물을 남기는 디렉터리(계획 §12.1 「산출물」).
 *
 * 문서마다 `<id>/easy.txt`·`<id>/suggestions.json`·`<id>/image-<n>.png`·`<id>/image-<n>.json`
 * 을, 실행 전체로 `conditions.txt`·`ledger.csv` 를 남긴다. 표본이 공개 행정 문서라 원문·쉬운
 * 글·제안 본문을 파일에 남기는 것은 허용되지만(§12.1), **API 키와 요청 헤더는 어디에도 남기지
 * 않는다** — 이 클래스가 받는 값에 그 둘이 없다.
 *
 * 디렉터리 판정은 [LaneTranscript.plan] 과 같은 순서다: 만들 수 없으면 거절, 비어 있지 않으면
 * 거절(이전 실행 결과를 이번 실행으로 오인하지 않게), 문서 id 가 디렉터리 이름으로 위험하면
 * 거절, 쓰기 권한이 없으면 거절. 넷 다 **유료 호출을 시작하기 전에** 판정한다.
 */
internal class IllustrationLaneArtifacts private constructor(private val directory: Path) {
    /** 측정 조건 줄에 붙는 한 줄. 경로만 담는다. */
    val description: String get() = "out=$directory"

    fun writeConditions(text: String) {
        directory.resolve(CONDITIONS_FILE_NAME).writeText(text)
    }

    fun writeLedger(csv: String) {
        directory.resolve(LEDGER_FILE_NAME).writeText(csv)
    }

    fun writeEasyText(
        documentId: String,
        text: String,
    ) {
        documentDirectory(documentId).resolve("easy.txt").writeText(text)
    }

    fun writeSuggestions(
        documentId: String,
        json: String,
    ) {
        documentDirectory(documentId).resolve("suggestions.json").writeText(json)
    }

    fun writeImage(
        documentId: String,
        index: Int,
        png: ByteArray,
    ) {
        documentDirectory(documentId).resolve("image-$index.png").writeBytes(png)
    }

    fun writeImageMetadata(
        documentId: String,
        index: Int,
        json: String,
    ) {
        documentDirectory(documentId).resolve("image-$index.json").writeText(json)
    }

    /**
     * 문서 한 건의 하위 디렉터리. [plan] 이 이미 이름을 검사했지만 여기서 다시 확인한다 —
     * 계획 시점 검사를 쓰기 시점의 유일한 방어선으로 두지 않는다([LaneTranscript.save] 와
     * 같은 판단).
     */
    private fun documentDirectory(documentId: String): Path {
        check(isSafeDirectoryName(documentId, directory)) {
            "문서 id \"$documentId\" 는 산출물 디렉터리 이름으로 쓸 수 없다 — plan() 이 미리 걸렀어야 한다."
        }
        return Files.createDirectories(directory.resolve(documentId))
    }

    companion object {
        /** 산출물 디렉터리. 미설정이면 [DEFAULT_RELATIVE] 를 저장소 루트에서 푼다. */
        const val ENV: String = "EASYDOC_LANE_OUT"

        /** 계획 §12.4 2차 평가의 기본 경로 — 1차 산출물 디렉터리를 덮지 않도록 따로 둔다. */
        const val DEFAULT_RELATIVE: String = "docs/reports/2026-11-10-illustration-p0-1-eval-2-artifacts"

        const val CONDITIONS_FILE_NAME: String = "conditions.txt"

        const val LEDGER_FILE_NAME: String = "ledger.csv"

        private const val PROBE_FILE_NAME: String = ".illustration-lane-probe"

        /** 저장소 루트를 찾는 표지. 체크아웃 전체가 있어야만 존재한다. */
        private const val REPOSITORY_MARKER: String = "contracts/easy-doc-v1.yaml"

        private const val UNKNOWN_COMMIT: String = "unknown"

        private const val GIT_TIMEOUT_SECONDS: Long = 5

        fun plan(
            env: (String) -> String?,
            documentIds: List<String>,
        ): IllustrationLaneArtifactsPlan {
            val configured = env(ENV)?.takeIf(String::isNotBlank)
            val directory = configured?.let(Path::of) ?: repositoryRoot().resolve(DEFAULT_RELATIVE)
            // 문서 id 검사는 파일시스템을 건드리지 않는다 — 먼저 돌려서, 거절할 실행이 디렉터리를
            // 만들어 두고 끝나지 않게 한다(다음 실행의 「비어 있지 않다」 거절을 미리 만드는 꼴).
            rejectUnsafeDocumentIds(documentIds, directory)?.let { return it }
            return try {
                Files.createDirectories(directory)
                rejectIfNotEmpty(directory)
                    ?: run {
                        probeWritable(directory)
                        IllustrationLaneArtifactsPlan.Ready(IllustrationLaneArtifacts(directory))
                    }
            } catch (exc: IOException) {
                IllustrationLaneArtifactsPlan.Unusable(
                    "$ENV=$directory 에 쓸 수 없다: ${exc.message} — 이대로 돌면 산출물 없이 유료 호출을 시작하게 된다.",
                )
            }
        }

        /**
         * 저장소 루트. Gradle 테스트의 작업 디렉터리는 모듈 디렉터리라 [REPOSITORY_MARKER] 가
         * 보일 때까지 거슬러 올라간다 — 올라갈 단계 수를 적어 두면 모듈이 옮겨질 때 조용히
         * 어긋난다(`JdbcRetainedTransactionRepositoryTest` 의 게시 문안 경로와 같은 방식).
         */
        fun repositoryRoot(): Path {
            var directory: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
            while (directory != null) {
                if (Files.isRegularFile(directory.resolve(REPOSITORY_MARKER))) return directory
                directory = directory.parent
            }
            error("$REPOSITORY_MARKER 을 찾지 못했다 — 저장소 전체를 체크아웃한 상태에서 돌려야 한다")
        }

        /**
         * 평가 기록이 어느 리비전을 잰 것인지(계획 §12.1 「산출물」의 commit sha).
         *
         * `.git` 을 직접 읽는다 — 테스트가 프로세스를 띄우지 않게 한다. packed-refs 만 있는
         * 체크아웃처럼 읽어 낼 수 없는 경우는 [UNKNOWN_COMMIT] 이다: 모르는 값을 지어내지
         * 않는다.
         */
        fun commitSha(root: Path): String =
            try {
                readCommitSha(root)
            } catch (exc: IOException) {
                // git 이 없거나 저장소가 아니다. 모르는 값을 지어내지 않는다.
                println("commit sha 조회 실패: ${exc.message}")
                UNKNOWN_COMMIT
            } catch (exc: InterruptedException) {
                Thread.currentThread().interrupt()
                println("commit sha 조회 중단: ${exc.message}")
                UNKNOWN_COMMIT
            }

        /**
         * `git` 에게 묻는다. `.git` 을 직접 읽으면 **연결된 worktree**(`.git` 이 디렉터리가 아니라
         * `gitdir:` 한 줄을 담은 파일이고, 참조는 공용 디렉터리에 있다)와 packed-refs 에서 조용히
         * `unknown` 이 된다 — 평가 기록이 어느 리비전을 잰 것인지가 바로 그 두 경우에 사라진다.
         */
        private fun readCommitSha(root: Path): String {
            val process =
                ProcessBuilder("git", "rev-parse", "HEAD")
                    .directory(root.toFile())
                    .redirectErrorStream(false)
                    .start()
            val output = process.inputStream.bufferedReader().use { reader -> reader.readText().trim() }
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy()
                return UNKNOWN_COMMIT
            }
            return if (process.exitValue() == 0 && output.isNotEmpty()) output else UNKNOWN_COMMIT
        }

        /** 문서 id 가 디렉터리 이름으로 안전한가 — 문법은 [GoldenDocumentLoader.SAFE_DOCUMENT_ID] 가 정본이다. */
        private fun isSafeDirectoryName(
            documentId: String,
            directory: Path,
        ): Boolean {
            val normalized = directory.normalize()
            return GoldenDocumentLoader.SAFE_DOCUMENT_ID.matches(documentId) &&
                documentId != CONDITIONS_FILE_NAME &&
                documentId != LEDGER_FILE_NAME &&
                documentId != PROBE_FILE_NAME &&
                normalized.resolve(documentId).normalize().parent == normalized
        }

        private fun rejectUnsafeDocumentIds(
            documentIds: List<String>,
            directory: Path,
        ): IllustrationLaneArtifactsPlan.Unusable? {
            val unsafe = documentIds.firstOrNull { !isSafeDirectoryName(it, directory) }
            if (unsafe != null) return unsafeDocumentId(unsafe)
            val duplicates =
                documentIds
                    .groupingBy { it }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
            return if (duplicates.isEmpty()) null else duplicateDocumentIds(duplicates)
        }

        private fun unsafeDocumentId(id: String): IllustrationLaneArtifactsPlan.Unusable =
            IllustrationLaneArtifactsPlan.Unusable(
                "문서 id \"$id\" 를 산출물 디렉터리 이름으로 쓸 수 없다 — 영숫자·`.`·`_`·`-` 만 허용하고 " +
                    "${CONDITIONS_FILE_NAME}·${LEDGER_FILE_NAME} 과 겹쳐서도 안 된다.",
            )

        private fun duplicateDocumentIds(ids: Set<String>): IllustrationLaneArtifactsPlan.Unusable =
            IllustrationLaneArtifactsPlan.Unusable(
                "표본에 같은 문서 id 가 두 번 이상 있다: ${ids.sorted().joinToString()} — 이대로 돌면 나중 " +
                    "문서가 앞선 문서의 산출물을 덮어쓴다.",
            )

        private fun rejectIfNotEmpty(directory: Path): IllustrationLaneArtifactsPlan.Unusable? =
            Files.newDirectoryStream(directory).use { entries ->
                if (entries.iterator().hasNext()) {
                    IllustrationLaneArtifactsPlan.Unusable(
                        "$directory 이 비어 있지 않다 — 이전 실행(또는 다른 조건으로 돌린 실행)의 산출물이 " +
                            "남아 있으면 이번 실행 결과로 오인하게 된다. 빈 디렉터리를 지정하거나 기존 " +
                            "산출물을 다른 곳으로 옮겨라.",
                    )
                } else {
                    null
                }
            }

        private fun probeWritable(directory: Path) {
            val probe = directory.resolve(PROBE_FILE_NAME)
            probe.writeText("")
            Files.delete(probe)
        }
    }
}

/** 산출물을 남길 수 있는가. [LaneTranscriptPlan] 과 같은 어휘를 쓴다. */
internal sealed interface IllustrationLaneArtifactsPlan {
    class Ready(val artifacts: IllustrationLaneArtifacts) : IllustrationLaneArtifactsPlan

    /** 이 설정으로는 산출물을 남길 수 없다. 유료 호출 전에 실패로 알린다. */
    class Unusable(val reason: String) : IllustrationLaneArtifactsPlan
}
