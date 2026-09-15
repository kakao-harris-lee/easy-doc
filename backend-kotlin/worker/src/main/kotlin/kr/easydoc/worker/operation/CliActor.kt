package kr.easydoc.worker.operation

import kr.easydoc.application.auth.UserRepository
import kr.easydoc.application.mail.EmailAddress
import kr.easydoc.core.exceptions.NotFoundException
import org.springframework.boot.ApplicationArguments
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.UUID

/** CLI의 `--actor-email`을 접속기록에 사용할 사용자 ID로 해석한다. */
internal object CliActor {
    const val ACTOR_EMAIL_OPTION = "actor-email"

    /** 형식과 존재 여부는 [resolveActorId]에서 검증한다. */
    fun parseEmail(args: ApplicationArguments): String =
        requireNotNull(singleOptionValue(args)) { "--$ACTOR_EMAIL_OPTION 은 필수입니다" }

    /** 이메일을 정규화해 사용자를 찾으며 실패 메시지에는 이메일 원문을 담지 않는다. */
    fun resolveActorId(
        users: UserRepository,
        rawEmail: String,
    ): UUID {
        val normalized = EmailAddress.of(rawEmail)
        val stored = users.findByEmail(normalized.value) ?: throw NotFoundException(UNKNOWN_ACTOR_EMAIL_MESSAGE)
        return stored.user.id
    }

    /** HTTP 접속지가 없는 CLI 실행을 위한 식별값. 호스트명 조회 실패는 명령을 막지 않는다. */
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
