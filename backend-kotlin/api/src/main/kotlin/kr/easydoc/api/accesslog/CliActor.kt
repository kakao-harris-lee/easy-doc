package kr.easydoc.api.accesslog

import kr.easydoc.application.auth.UserRepository
import kr.easydoc.application.mail.EmailAddress
import kr.easydoc.core.exceptions.NotFoundException
import org.springframework.boot.ApplicationArguments
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.UUID

/**
 * CLI 실행 프로필(usage-report·credit-grant·admin-grant) 공통 `--actor-email=<이메일>` 파싱 —
 * 접속기록(`personal_data_access_logs`, V22)의 `actor_user_id`를 채운다. HTTP 요청과
 * 달리 CLI는 인증된 사용자 컨텍스트가 없으므로, 운영자가 자신을 식별할 값을 인자로
 * 직접 준다(계획 `docs/plans/2026-09-11-access-log-retention.md` §3.2).
 *
 * **이메일이지 UUID가 아니다** — 기존 운영자 CLI(`admin-grant --email=<이메일>`,
 * [kr.easydoc.api.admin.AdminGrantRunner])와 같은 형태다. 운영자는 자기 user_id를
 * 모른다 — UUID를 요구하면 매번 DB를 찾아봐야 하고, 그 마찰이 "아무 값이나 넣기"로
 * 이어져 감사 기록의 신뢰를 떨어뜨린다.
 */
internal object CliActor {
    const val ACTOR_EMAIL_OPTION = "actor-email"

    /** `--actor-email` 원문을 그대로 돌려준다 — 형식·존재 검증은 [resolveActorId]가 한다. */
    fun parseEmail(args: ApplicationArguments): String =
        requireNotNull(singleOptionValue(args)) { "--$ACTOR_EMAIL_OPTION 은 필수입니다" }

    /**
     * 이메일을 `actor_user_id`로 바꾼다 — `AdminGrantService.grant`와 같은 정규화
     * ([EmailAddress.of])를 쓴다. 형식이 잘못됐거나(`InvalidInputException`) 계정이
     * 없으면([NotFoundException]) 던진다 — **이메일 원문을 예외 메시지에 담지 않는다**
     * (`AdminGrantRunner.UNKNOWN_EMAIL_MESSAGE`와 같은 규약, 아래 [UNKNOWN_ACTOR_EMAIL_MESSAGE]).
     */
    fun resolveActorId(
        users: UserRepository,
        rawEmail: String,
    ): UUID {
        val normalized = EmailAddress.of(rawEmail)
        val stored = users.findByEmail(normalized.value) ?: throw NotFoundException(UNKNOWN_ACTOR_EMAIL_MESSAGE)
        return stored.user.id
    }

    /**
     * `client_ip` 열에 대신 담을 실행 주체 표시값 — CLI는 HTTP 요청이 아니라 접속지가
     * 없다(계획 §3.2 「client_ip는 없으므로 실행 주체를 대신 남긴다」). 호스트명을 얻지
     * 못하는 컨테이너 환경(DNS 미설정 등)에서도 실행 자체는 막지 않는다.
     */
    fun clientIp(): String {
        val host =
            try {
                InetAddress.getLocalHost().hostName
            } catch (_: UnknownHostException) {
                "unknown-host"
            }
        return "cli:$host/${System.getProperty("user.name")}"
    }

    /** [rawEmail]을 담지 않는다 — 위 KDoc 규약. */
    const val UNKNOWN_ACTOR_EMAIL_MESSAGE = "알 수 없는 --$ACTOR_EMAIL_OPTION 입니다"

    private fun singleOptionValue(args: ApplicationArguments): String? =
        if (args.containsOption(ACTOR_EMAIL_OPTION)) args.getOptionValues(ACTOR_EMAIL_OPTION)?.firstOrNull() else null
}
