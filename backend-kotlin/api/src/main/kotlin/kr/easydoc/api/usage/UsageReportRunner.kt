package kr.easydoc.api.usage

import kr.easydoc.application.usage.UsageReportService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * `usage-report` profile 의 실행부 — 컨텍스트가 뜬 뒤 [UsageReportService] 를 한 번 불러
 * CSV 파일을 쓰고 끝낸다. `ApiApplication.main` 이 [ExitCodeGenerator] 를 읽어
 * `SpringApplication.exit` 로 종료 코드를 낸다(`KeyRotationRunner`와 같은 자리).
 *
 * 인자는 `--from=YYYY-MM-DD --to=YYYY-MM-DD --out=path` 세 개(전부 선택) — 생략하면
 * [UsageReportService] 가 지난달 전체·`DEFAULT_OUT_PATH`로 채운다.
 *
 * **CSV 는 UTF-8 BOM 을 붙여 쓴다.** 저장소에 CSV 내보내기 선례가 없어 새로 정한다 —
 * 이 리포트를 여는 도구는 엑셀(청구 담당자 워크플로)이고, BOM 없는 UTF-8 CSV 를 엑셀이
 * 열면 한글 열 이름·워크스페이스 이름이 깨진 문자로 보인다(euc-kr 로 오판).
 *
 * **표준출력에는 건수·기간·경로만 낸다** — 소유자 이메일·워크스페이스 이름은 CSV
 * 파일에만 실리고 로그·표준출력에는 나가지 않는다(계획 §2 결정 7 "로그에는 남기지 않는다").
 *
 * **예외를 밖으로 던지지 않는다** — `KeyRotationRunner`와 같은 이유(실패 분석 스택트레이스가
 * 무엇을 남길지 이 클래스가 보장할 수 없다). 메시지만 남기고 종료 코드 1이다.
 *
 * **`RuntimeException`뿐 아니라 `Exception` 전체를 잡는다.** [writeCsvWithBom]이 쓰기 불가능한
 * `--out`(존재하지 않는 상위 디렉터리를 만들 수 없거나, 경로가 이미 디렉터리인 경우)를
 * 만나면 `java.io.FileNotFoundException`(checked, `RuntimeException`이 아니다)을 던진다 —
 * 그 경로도 같은 한 줄 메시지·종료 코드 1로 끝나야 위 KDoc의 약속이 지켜진다.
 */
class UsageReportRunner(private val service: UsageReportService) :
    ApplicationRunner,
    ExitCodeGenerator {
    private val log = LoggerFactory.getLogger(UsageReportRunner::class.java)

    @Volatile
    private var exitCode: Int = 0

    @Suppress("TooGenericExceptionCaught")
    override fun run(args: ApplicationArguments) {
        exitCode =
            try {
                val from = args.singleOptionValue(FROM_OPTION)
                val to = args.singleOptionValue(TO_OPTION)
                val outPath = args.singleOptionValue(OUT_OPTION) ?: DEFAULT_OUT_PATH

                val report = service.generateCsv(from, to)
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
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
