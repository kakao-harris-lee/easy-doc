package kr.easydoc.worker.operation

import kr.easydoc.application.accesslog.PersonalDataAccessReportService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 접속기록 CSV와 집계를 생성하는 일회성 명령.
 * `--from`, `--to`, `--out`을 생략하면 지난달과 기본 경로를 사용한다.
 * CSV는 엑셀 호환 UTF-8 BOM을 붙이며 실패는 종료 코드 1로 보고한다.
 */
class AccessLogReportRunner(private val service: PersonalDataAccessReportService) :
    ApplicationRunner,
    ExitCodeGenerator {
    private val log = LoggerFactory.getLogger(AccessLogReportRunner::class.java)

    @Volatile
    private var exitCode: Int = 0

    @Suppress("TooGenericExceptionCaught")
    override fun run(args: ApplicationArguments) {
        exitCode =
            try {
                val from = args.singleOptionValue(FROM_OPTION)
                val to = args.singleOptionValue(TO_OPTION)
                val outPath = args.singleOptionValue(OUT_OPTION) ?: DEFAULT_OUT_PATH

                val report = service.generate(from, to)
                writeCsvWithBom(outPath, report.csv)

                println(
                    "접속기록 점검 보고서 — 기간 ${report.from}~${report.to}, 총 ${report.totalCount}건, " +
                        "거절 ${report.rejectedCount}건, 경로 $outPath",
                )
                println("취급자별: " + report.countsByActor.entries.joinToString(", ") { (id, count) -> "$id=$count" })
                println("업무별: " + report.countsByOperation.entries.joinToString(", ") { (op, count) -> "$op=$count" })
                SUCCESS
            } catch (failure: Exception) {
                log.error("접속기록 점검 보고서 생성이 실패했다: {}", failure.message)
                FAILURE
            }
    }

    override fun getExitCode(): Int = exitCode

    private fun ApplicationArguments.singleOptionValue(name: String): String? =
        if (containsOption(name)) getOptionValues(name)?.firstOrNull() else null

    /** 엑셀 호환 UTF-8 BOM(`EF BB BF`)을 붙여 쓴다. */
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
        const val DEFAULT_OUT_PATH = "./access-log-report.csv"
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
