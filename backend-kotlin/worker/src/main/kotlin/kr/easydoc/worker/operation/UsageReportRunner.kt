package kr.easydoc.worker.operation

import kr.easydoc.application.accesslog.RecordPersonalDataAccess
import kr.easydoc.application.auth.UserRepository
import kr.easydoc.application.usage.UsageReportService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * 사용자·워크스페이스 사용량 CSV를 생성하는 일회성 명령.
 * `--from`, `--to`, `--out`을 생략하면 지난달과 기본 경로를 사용하며 `--actor-email`은
 * 필수다. CSV는 엑셀 호환 UTF-8 BOM을 붙인다. 이름과 이메일은 CSV에만 기록하고
 * 표준출력이나 로그에는 남기지 않는다.
 *
 * 데이터 조회 직후 접속기록을 남기므로 이후 파일 쓰기가 실패해도 조회 기록은 유지된다.
 * 실패는 종료 코드 1로 보고한다.
 */
@Suppress("TooGenericExceptionCaught")
class UsageReportRunner(
    private val service: UsageReportService,
    private val users: UserRepository,
    private val accessLog: RecordPersonalDataAccess,
) : ApplicationRunner,
    ExitCodeGenerator {
    private val log = LoggerFactory.getLogger(UsageReportRunner::class.java)

    @Volatile
    private var exitCode: Int = 0

    override fun run(args: ApplicationArguments) {
        val actorId = resolveActorOrFail(args) ?: return
        exitCode = generateAndWriteReport(args, actorId)
    }

    /** 읽기 전 실패(이메일 해석) — 기록 없이 종료 코드 1. */
    private fun resolveActorOrFail(args: ApplicationArguments): UUID? =
        try {
            CliActor.resolveActorId(users, CliActor.parseEmail(args))
        } catch (failure: Exception) {
            log.error("사용량 리포트 생성이 실패했다: {}", failure.message)
            exitCode = FAILURE
            null
        }

    private fun generateAndWriteReport(
        args: ApplicationArguments,
        actorId: UUID,
    ): Int =
        try {
            val from = args.singleOptionValue(FROM_OPTION)
            val to = args.singleOptionValue(TO_OPTION)
            val outPath = args.singleOptionValue(OUT_OPTION) ?: DEFAULT_OUT_PATH

            val report = service.generateCsv(from, to)
            // 여기가 실제로 읽은 시점이다 — 클래스 KDoc. 파일 쓰기가 이 뒤에 실패해도
            // 이 기록은 되돌리지 않는다.
            accessLog.recordSuccess(actorId, CliActor.clientIp(), OPERATION, "from=${report.from}&to=${report.to}")

            writeCsvWithBom(outPath, report.csv)

            println("사용량 리포트 — 기간 ${report.from}~${report.to}, ${report.rowCount}행, 경로 $outPath")
            SUCCESS
        } catch (failure: Exception) {
            // 메시지만 남긴다 — 소유자 이메일·워크스페이스 이름을 담지 않는다(도메인
            // 예외 메시지는 형식·범위 오류뿐이라 이 값들을 포함하지 않는다). Exception
            // 전체를 잡는 이유는 클래스 KDoc 참고 — 쓰기 불가능한 --out 이
            // FileNotFoundException(checked)으로 온다.
            log.error("사용량 리포트 생성이 실패했다: {}", failure.message)
            FAILURE
        }

    override fun getExitCode(): Int = exitCode

    private fun ApplicationArguments.singleOptionValue(name: String): String? =
        if (containsOption(name)) getOptionValues(name)?.firstOrNull() else null

    /** UTF-8 BOM(`EF BB BF`)을 붙여 쓴다 — KDoc 「CSV 는 UTF-8 BOM」 참고. */
    private fun writeCsvWithBom(
        path: String,
        csv: String,
    ) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.outputStream().use { stream ->
            stream.write(UTF8_BOM)
            stream.write(csv.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private companion object {
        const val SUCCESS = 0
        const val FAILURE = 1
        const val FROM_OPTION = "from"
        const val TO_OPTION = "to"
        const val OUT_OPTION = "out"
        const val DEFAULT_OUT_PATH = "./usage-report.csv"

        /** 접속기록 `operation` 열에 쓰는 명령 이름. */
        const val OPERATION = "usage-report"
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
