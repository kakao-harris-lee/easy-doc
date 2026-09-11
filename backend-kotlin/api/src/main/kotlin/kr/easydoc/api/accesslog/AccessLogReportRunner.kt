package kr.easydoc.api.accesslog

import kr.easydoc.application.accesslog.PersonalDataAccessLogRow
import kr.easydoc.application.accesslog.PersonalDataAccessReportService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * `access-log-report` profile 의 실행부 — 컨텍스트가 뜬 뒤
 * [PersonalDataAccessReportService.generate] 를 한 번 불러 월 1회 점검용 보고서를 쓰고
 * 끝낸다. `UsageReportRunner`와 같은 one-shot 자리다.
 *
 * 인자는 `--from=YYYY-MM-DD --to=YYYY-MM-DD --out=path` 세 개(전부 선택) — 생략하면
 * [PersonalDataAccessReportService] 가 지난달 전체·`DEFAULT_OUT_PATH`로 채운다.
 *
 * **CSV(원시 목록)는 `--out`에, 집계(기간·취급자별 횟수·업무별 횟수·거절 건수)는
 * 표준출력에 낸다** — 점검자가 파일을 열지 않고도 요약을 볼 수 있고, 원시 목록은 필요할
 * 때만 연다(계획 `docs/plans/2026-09-11-access-log-retention.md` §3.5 「점검자가 눈으로
 * 볼 수 있는 원시 목록」). **점검을 수행했다는 사실 자체는 이 실행이 남기지 않는다** —
 * 이 보고서 파일 자체가 그 증거다(같은 절 「점검 수행 기록의 전산화는 범위 밖」).
 *
 * `UsageReportRunner`와 같은 이유로 CSV 는 UTF-8 BOM 을 붙인다.
 *
 * **예외를 밖으로 던지지 않는다** — 메시지만 남기고 종료 코드 1이다.
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
                writeCsvWithBom(outPath, renderCsv(report.rows))

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

    private fun renderCsv(rows: List<PersonalDataAccessLogRow>): String =
        buildString {
            append(HEADER)
            append(CRLF)
            rows.forEach { row ->
                append(csvLineOf(row))
                append(CRLF)
            }
        }

    private fun csvLineOf(row: PersonalDataAccessLogRow): String =
        listOf(
            row.id.toString(),
            row.actorUserId.toString(),
            row.accessedAt.toString(),
            row.clientIp,
            row.operation,
            row.subjectScope.orEmpty(),
            row.outcome.wireName,
        ).joinToString(",", transform = ::csvField)

    /** `UsageReportService.csvField`와 같은 CSV 인젝션 방어 + RFC 4180 quoting. */
    private fun csvField(value: String): String {
        val escaped = if (value.isNotEmpty() && value[0] in FORMULA_TRIGGER_CHARS) "'$value" else value
        return if (escaped.any(NEEDS_QUOTING_CHARS::contains)) {
            "\"" + escaped.replace("\"", "\"\"") + "\""
        } else {
            escaped
        }
    }

    /** UTF-8 BOM(`EF BB BF`)을 붙여 쓴다 — `UsageReportRunner`와 같은 이유(엑셀 호환). */
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
        const val CRLF = "\r\n"
        const val HEADER = "id,actor_user_id,accessed_at,client_ip,operation,subject_scope,outcome"
        val NEEDS_QUOTING_CHARS = charArrayOf(',', '"', '\n', '\r')
        val FORMULA_TRIGGER_CHARS = charArrayOf('=', '+', '-', '@', '\t')
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
