package kr.easydoc.worker.operation

import kr.easydoc.application.invoice.InvoiceRequestService
import kr.easydoc.application.invoice.requireValidOperatorNote
import kr.easydoc.core.exceptions.EasyDocException
import kr.easydoc.core.invoice.InvoiceRequestStatus
import kr.easydoc.core.privacy.CONTENT_MASK
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import java.util.UUID

/**
 * 세금계산서 요청을 발급 또는 거절 상태로 처리하는 일회성 명령.
 * `--id`, `--status=issued|rejected`, 선택적 `--note`를 받는다. 출력에는 요청 ID와 상태만
 * 포함하고 사업자 정보는 남기지 않는다. 실패는 종료 코드 1로 보고한다.
 */
class InvoiceHandleRunner(private val service: InvoiceRequestService) :
    ApplicationRunner,
    ExitCodeGenerator {
    private val log = LoggerFactory.getLogger(InvoiceHandleRunner::class.java)

    @Volatile
    private var exitCode: Int = 0

    @Suppress("TooGenericExceptionCaught")
    override fun run(args: ApplicationArguments) {
        exitCode =
            try {
                val handleArgs = InvoiceHandleArgs.parse(args)
                val view = service.handle(handleArgs.id, handleArgs.status, handleArgs.note)

                println("세금계산서 요청 처리 — id=${view.id} status=${view.status.wireName}")
                SUCCESS
            } catch (failure: Exception) {
                // 도메인 예외([EasyDocException])·인자 검증([IllegalArgumentException])만
                // 메시지를 남긴다 — 둘 다 형식·범위 오류뿐이라 사업자 정보를 담지 않는다.
                // 그 밖의 예외(예: Spring `DataAccessException`)는 메시지에 SQL 조각·행
                // 값이 실릴 수 있어 클래스 이름만 남긴다(`UsageReportRunner`가 `Exception`
                // 전체를 잡으면서도 이 구분을 두지 않은 것과 달리, 이 러너는 운영자가
                // 반복 실행하는 CLI라 로그에 남는 값을 더 보수적으로 좁힌다).
                when (failure) {
                    is EasyDocException, is IllegalArgumentException -> {
                        log.error("세금계산서 요청 처리가 실패했다: {}", failure.message)
                    }

                    else -> {
                        log.error("세금계산서 요청 처리가 실패했다: {}", failure::class.java.simpleName)
                    }
                }
                FAILURE
            }
    }

    override fun getExitCode(): Int = exitCode

    private companion object {
        const val SUCCESS = 0
        const val FAILURE = 1
    }
}

/**
 * `invoice-handle` 인자 검증 — Spring 컨텍스트 없이 [ApplicationArguments] 만으로 단위
 * 테스트할 수 있게 [InvoiceHandleRunner] 에서 분리했다(`CreditGrantArgs`와 같은 이유).
 */
internal data class InvoiceHandleArgs(
    val id: UUID,
    val status: InvoiceRequestStatus,
    val note: String?,
) {
    /** 운영자 메모를 찍지 않는다(인구조사 규약). */
    override fun toString(): String = "InvoiceHandleArgs(id=$id, status=$status, note=$CONTENT_MASK)"

    companion object {
        private const val ID_OPTION = "id"
        private const val STATUS_OPTION = "status"
        private const val NOTE_OPTION = "note"

        /** 운영자가 선택할 수 있는 종결 상태. `REQUESTED`로 되돌릴 수 없다. */
        private val ALLOWED_STATUSES =
            mapOf(
                "issued" to InvoiceRequestStatus.ISSUED,
                "rejected" to InvoiceRequestStatus.REJECTED,
            )

        fun parse(args: ApplicationArguments): InvoiceHandleArgs {
            val id = requireUuid(singleOptionValue(args, ID_OPTION))
            val status = requireStatus(singleOptionValue(args, STATUS_OPTION))
            val note = requireValidNote(singleOptionValue(args, NOTE_OPTION))
            return InvoiceHandleArgs(id, status, note)
        }

        private fun singleOptionValue(
            args: ApplicationArguments,
            name: String,
        ): String? = if (args.containsOption(name)) args.getOptionValues(name)?.firstOrNull() else null

        private fun requireUuid(value: String?): UUID {
            val raw = requireNotNull(value) { "--$ID_OPTION 은 필수입니다" }
            return try {
                UUID.fromString(raw)
            } catch (failure: IllegalArgumentException) {
                throw IllegalArgumentException("--$ID_OPTION 형식이 올바르지 않습니다: $raw", failure)
            }
        }

        private fun requireStatus(value: String?): InvoiceRequestStatus {
            val raw = requireNotNull(value) { "--$STATUS_OPTION 은 필수입니다" }
            return requireNotNull(ALLOWED_STATUSES[raw]) {
                "--$STATUS_OPTION 값이 올바르지 않습니다: $raw (허용값: ${ALLOWED_STATUSES.keys.joinToString(", ")})"
            }
        }

        /**
         * [requireValidOperatorNote](application 층)에 위임한다 — 손으로 다시 쓴 `.length`
         * 길이 검사(UTF-16 코드 유닛)는 다른 필드들이 쓰는 코드 포인트 기준과 어긋날 수
         * 있었다. 그 함수가 다른 선택 필드와 같은 정규화(제어문자 제거 + 앞뒤 공백
         * 제거)도 함께 적용한다 — 운영자가 터미널에 붙여넣는 메모라 제어문자가 섞여
         * 들어올 수 있고, 그 값이 그대로 요청자 메일 본문에 실린다.
         */
        private fun requireValidNote(value: String?): String? = requireValidOperatorNote(value)
    }
}
