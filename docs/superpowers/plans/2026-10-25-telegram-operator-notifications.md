# Telegram Operator Notifications Implementation Plan
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
**Goal:** 이메일·소셜 최초 가입, 회원 탈퇴 및 승인된 Toss 결제·구독 상태 전이를 사업 커밋 직후 Telegram으로 한 번만 동기 알리는 best-effort 운영 알림을 구현한다.
**Architecture:** `application`은 sealed 이벤트, 좁은 입력 포트 `OperatorNotifications`, plain text 출력 포트와 전체 실패를 삼키는 알림 서비스를 소유한다. 사업 서비스는 트랜잭션 안에서 실제 상태 전이와 불변 스냅샷만 만들고, `inTransaction`/`owned`가 정상 반환된 뒤 입력 포트를 호출한다. `infrastructure`는 JDK `HttpClient` 기반 `RestClient`로 Telegram `sendMessage`를 한 번 호출하며 재시도·outbox·후속 확인은 두지 않는다.
**Tech Stack:** Kotlin 2.3.21, Java 21, Spring Boot 4.1.0, Spring `RestClient`, `JdkClientHttpRequestFactory`, Jackson 3, JUnit 6, AssertJ, JDK `HttpServer`, Logback `ListAppender`(application 테스트 전용), Gradle dependency locking.

## Global Constraints

- 모든 Gradle 명령의 작업 디렉터리는 `backend-kotlin/`이고, `docker compose config`의 작업 디렉터리는 저장소 루트다.
- 제품 구현은 Kotlin/Spring만 사용한다. Python 구현·비교 하네스·새 외부 테스트 라이브러리·실제 Telegram 호출·유료 LLM 호출을 추가하지 않는다.
- 알림은 최대 한 번의 동기 발송이다. durable outbox, 자동 재시도, 실패 큐, DB migration, worker scheduler, webhook, public HTTP endpoint를 추가하지 않는다.
- `contracts/`, `frontend/`, `compose.yml`, 기존 `billing_notifications`, 회원용 이메일 동작은 변경하지 않는다. Compose는 이미 `.env`를 `env_file`로 전달하므로 설정 전달을 위해 수정하지 않는다.
- `.env`는 읽지 않는다. 실제 token, chat ID, 전체 이메일, provider 응답, payment/billing key를 코드·테스트·로그에 넣지 않는다. 테스트 문자열은 명백한 가짜 값만 사용한다.
- `enabled=false`이면 사용자 이메일 조회, 메시지 생성·마스킹, 출력 포트 호출, 실패 로그 없이 `OperatorNotificationService.notify`가 즉시 반환한다.
- 알림 준비와 발송 전체의 `RuntimeException`은 `OperatorNotificationService` 한 경계에서 삼킨다. WARN 한 줄에는 event type과 회원 이벤트의 `userId` 또는 결제 이벤트의 `orderId`, 예외 클래스 단순명만 남긴다. 원본 예외 메시지, 예외 객체, stack trace는 전달하지 않는다.
- 이메일은 local part 첫 글자 + 정확히 `***` + `@domain`으로 마스킹한다. 전체 UUID를 표시하고 `Instant`는 `Asia/Seoul`로 변환한다. `toss_test` 결제만 제목 앞에 `[TEST]`를 붙인다.
- 가입·탈퇴와 모든 결제 알림은 사업 트랜잭션 커밋 후 호출한다. 트랜잭션 안에서 `OperatorNotifications.notify` 또는 Telegram 출력 포트를 호출하지 않는다.
- 결제 상태 판정은 제공자 호출 횟수가 아니라 workspace 잠금 안에서 다시 읽은 저장 상태의 실제 전이로 한다. 같은 상태 재저장과 멱등 재호출은 알리지 않는다.
- 결제 이벤트는 `payment_approved`, `renewal_approved`, `payment_failed`, `renewal_failed`, `refund_approved`, `subscription_canceling`, `subscription_expired`, `subscription_past_due`, `subscription_suspended`, `payment_manual_review`만 지원한다. `suspended` 판정을 위해 새 유스케이스나 상태 전이를 추가하지 않는다.
- 구독 이벤트는 `TossBillingStore.latestOrder(workspaceId)`의 `kind<>'refund'` 최신 주문을 사용한다. `null`이면 ID·금액을 추측하지 않고 해당 알림을 생략하며 정제된 WARN만 남기고 사업 결과는 유지한다.
- 7일 예고, 카드 등록·교체·폐기, scheduled 생성·release, 로그인, 소셜 연결·해제, 이메일 인증, 비밀번호 변경은 알리지 않는다.
- 각 Task는 테스트 실패를 확인한 뒤 최소 구현을 하고, 지정한 테스트와 커밋으로 끝낸다. 이후 코드가 바뀌면 그 변경에 대한 테스트를 다시 통과해야 한다.

---

## File Structure

### Create

- `application/src/main/kotlin/kr/easydoc/application/notification/OperatorNotificationPorts.kt`: sealed 회원·결제 이벤트, 결제 kind enum, 사업 서비스용 입력 포트, plain text 출력 포트.
- `application/src/main/kotlin/kr/easydoc/application/notification/OperatorNotificationService.kt`: 활성화 no-op, 결제 소유자 이메일 보강, 메시지 렌더링, best-effort 및 정제 로그 경계.
- `application/src/test/kotlin/kr/easydoc/application/notification/OperatorNotificationServiceTest.kt`: 메시지, no-op, 조회 실패, 발송 실패와 로그 유출 방지 테스트.
- `application/src/test/kotlin/kr/easydoc/application/notification/RecordingOperatorNotifications.kt`: 회원·결제 유스케이스 테스트가 공유하는 발송 기록 대역과 최소 `UserRepository` 대역.
- `infrastructure/src/main/kotlin/kr/easydoc/infrastructure/telegram/TelegramOperatorNotificationAdapter.kt`: Telegram `sendMessage` 단발 HTTP 어댑터.
- `infrastructure/src/main/kotlin/kr/easydoc/infrastructure/telegram/TelegramConfiguration.kt`: 설정 검증, timeout이 적용된 `RestClient`, 포트·서비스 빈 조립.
- `infrastructure/src/test/kotlin/kr/easydoc/infrastructure/telegram/TelegramOperatorNotificationAdapterTest.kt`: 로컬 HTTP 스텁으로 요청·응답·timeout·무재시도 검증.
- `infrastructure/src/test/kotlin/kr/easydoc/infrastructure/telegram/TelegramPropertiesTest.kt`: 기본값, 필수값, positive timeout, `toString` 비밀 차단 검증.
- `infrastructure/src/test/kotlin/kr/easydoc/infrastructure/telegram/TelegramConfigurationTest.kt`: 기본 URL과 빈 조립 검증.

### Modify

- `gradle/libs.versions.toml`, `application/build.gradle.kts`, `application/gradle.lockfile`: application 테스트 전용 Logback 좌표와 lock 갱신만 추가한다.
- `application/src/main/kotlin/kr/easydoc/application/auth/AuthService.kt`: 이메일 가입 생성 스냅샷의 커밋 후 알림.
- `application/src/main/kotlin/kr/easydoc/application/auth/SocialLoginService.kt`: 실제 신규 `User` 스냅샷을 트랜잭션 결과로 반환하고 신규 가입만 알림.
- `application/src/main/kotlin/kr/easydoc/application/account/AccountDeletionPorts.kt`: `LockedAccount.email` 추가.
- `application/src/main/kotlin/kr/easydoc/application/account/DeleteAccountService.kt`: `Clock`, 입력 포트, 삭제 전 이메일 스냅샷과 커밋 후 알림.
- `application/src/main/kotlin/kr/easydoc/application/subscription/TossBillingService.kt`: 잠금 안 전이 이벤트 생성과 `owned` 밖 발송.
- `application/src/test/kotlin/kr/easydoc/application/auth/AuthServiceTest.kt`: 가입 알림, 롤백, best-effort 회귀.
- `application/src/test/kotlin/kr/easydoc/application/auth/SocialLoginServiceTest.kt`: provider별 최초 가입과 기존 로그인·경쟁 복구·연결·해제 제외.
- `application/src/test/kotlin/kr/easydoc/application/account/DeleteAccountServiceTest.kt`: 탈퇴 알림, 거부·롤백, 기존 메일 공존, best-effort 회귀.
- `application/src/test/kotlin/kr/easydoc/application/subscription/TossBillingServiceTest.kt`: 결제·구독 전이와 중복 억제, 제외 경로, fixture 생성자 갱신.
- `infrastructure/src/main/kotlin/kr/easydoc/infrastructure/auth/AuthConfiguration.kt`: 회원 서비스와 `Clock.systemUTC()` 배선.
- `infrastructure/src/main/kotlin/kr/easydoc/infrastructure/auth/JdbcAccountDeletionRepository.kt`: 잠금 SELECT와 `LockedAccount.email` 매핑.
- `infrastructure/src/main/kotlin/kr/easydoc/infrastructure/subscription/TossConfiguration.kt`: `OperatorNotifications` 배선.
- `infrastructure/src/test/kotlin/kr/easydoc/infrastructure/auth/JdbcAccountDeletionRepositoryTest.kt`: 잠금 행 이메일 매핑 검증.
- `api/src/main/resources/application.yml`, `worker/src/main/resources/application.yml`: 동일한 `easydoc.telegram` 블록.
- `api/src/test/kotlin/kr/easydoc/api/ConfigurationPropertiesBindingTest.kt`: Telegram 바인딩과 YAML/환경 예시 고정.
- `api/src/test/kotlin/kr/easydoc/api/support/AuthSliceBeans.kt`: no-op 입력 포트와 생성자 영향 배선.
- `.env.example`: 다섯 Telegram 환경변수의 비밀 없는 예시.

---

### Task 1: Application 알림 모델·메시지·실패 경계

**Files:**
- Create: `application/src/main/kotlin/kr/easydoc/application/notification/OperatorNotificationPorts.kt`
- Create: `application/src/main/kotlin/kr/easydoc/application/notification/OperatorNotificationService.kt`
- Create: `application/src/test/kotlin/kr/easydoc/application/notification/OperatorNotificationServiceTest.kt`
- Create: `application/src/test/kotlin/kr/easydoc/application/notification/RecordingOperatorNotifications.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `application/build.gradle.kts`
- Modify: `application/gradle.lockfile`

**Interfaces:**
- Consumes: `UserRepository.findById(id: UUID): User?`, `User.email: String`, `Secret`는 사용하지 않는다.
- Produces: `fun interface OperatorNotifications { fun notify(event: OperatorNotificationEvent) }`
- Produces: `fun interface OperatorNotificationPort { fun send(message: String) }`
- Produces: `sealed interface OperatorNotificationEvent`의 `MemberSignedUp`, `MemberDeleted`, `Payment`와 `OperatorPaymentKind`.
- Produces: `class OperatorNotificationService(enabled: Boolean, users: UserRepository, port: OperatorNotificationPort) : OperatorNotifications`.

- [ ] **Step 1: 이벤트와 메시지의 실패 테스트를 작성한다**

`OperatorNotificationServiceTest`에 고정 UUID·시각을 쓰는 다음 테스트를 추가한다. `RecordingOperatorNotifications`의 `events`, `failNext`, `onNotify`도 이후 Task에서 그대로 사용한다.

```kotlin
@Test
fun `회원과 결제 메시지는 마스킹 식별자 서울 시각과 테스트 표시를 지킨다`() {
    val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val workspaceId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val orderId = UUID.fromString("33333333-3333-3333-3333-333333333333")
    val occurredAt = Instant.parse("2026-03-05T00:00:00Z")
    val sent = mutableListOf<String>()
    val users = NotificationUserRepository(User(userId, "hello@example.test", occurredAt, hasPassword = true))
    val service = OperatorNotificationService(true, users) { sent += it }

    service.notify(OperatorNotificationEvent.MemberSignedUp(userId, occurredAt, "h@example.test", "email"))
    service.notify(
        OperatorNotificationEvent.Payment(
            userId, occurredAt, OperatorPaymentKind.PAYMENT_APPROVED,
            workspaceId, orderId, "start", 99000, "toss_test",
        ),
    )
    service.notify(
        OperatorNotificationEvent.Payment(
            userId, occurredAt, OperatorPaymentKind.PAYMENT_APPROVED,
            workspaceId, orderId, "start", 99000, "toss_live",
        ),
    )

    assertThat(sent[0]).contains(
        "회원 가입", "event: member_signed_up", "email: h***@example.test",
        "userId: $userId", "occurredAt: 2026-03-05T09:00:00+09:00[Asia/Seoul]", "route: email",
    )
    assertThat(sent[1]).contains(
        "[TEST] 결제 승인", "email: h***@example.test", "userId: $userId",
        "planId: start", "amount: 99000 KRW", "orderId: $orderId",
        "workspaceId: $workspaceId", "environment: toss_test",
    ).doesNotContain("hello@example.test")
    assertThat(sent[2]).startsWith("결제 승인").doesNotContain("[TEST]")
}

@Test
fun `비활성화면 사용자 조회 메시지 생성 발송과 로그를 모두 생략한다`() {
    val users = NotificationUserRepository()
    var sends = 0
    val service = OperatorNotificationService(false, users) { sends++ }
    val malformed = OperatorNotificationEvent.MemberSignedUp(UUID.randomUUID(), Instant.EPOCH, "not-an-email", "email")

    val logs = captureLogs { service.notify(malformed) }

    assertThat(users.findByIdCount).isZero()
    assertThat(sends).isZero()
    assertThat(logs).isEmpty()
}

@Test
fun `조회와 발송 실패는 삼키고 정제된 WARN 한 줄만 남긴다`() {
    val userId = UUID.randomUUID()
    val orderId = UUID.randomUUID()
    val users = NotificationUserRepository()
    val secretMessage = "body-token-chat-full-email@example.test"
    val service = OperatorNotificationService(true, users) { throw IllegalStateException(secretMessage) }
    val event = OperatorNotificationEvent.Payment(
        userId, Instant.EPOCH, OperatorPaymentKind.RENEWAL_FAILED,
        UUID.randomUUID(), orderId, "start", 99000, "toss_live",
    )

    val missingLogs = captureLogs { service.notify(event) }
    users.user = User(userId, "full-email@example.test", Instant.EPOCH, hasPassword = true)
    val sendLogs = captureLogs { service.notify(event) }

    assertThat(missingLogs).singleElement().satisfies {
        assertThat(it.level).isEqualTo(Level.WARN)
        assertThat(it.formattedMessage).contains("renewal_failed", orderId.toString(), "IllegalStateException")
        assertThat(it.throwableProxy).isNull()
    }
    val rendered = sendLogs.joinToString("\n") { it.formattedMessage }
    assertThat(rendered).contains("IllegalStateException")
    assertThat(rendered).doesNotContain(secretMessage, "full-email@example.test", "chat", "token")
}

private fun captureLogs(block: () -> Unit): List<ILoggingEvent> {
    val logger = LoggerFactory.getLogger(OperatorNotificationService::class.java) as ch.qos.logback.classic.Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    val previousLevel = logger.level
    logger.addAppender(appender)
    logger.level = Level.TRACE
    try {
        block()
    } finally {
        logger.level = previousLevel
        logger.detachAppender(appender)
        appender.stop()
    }
    return appender.list.toList()
}
```

공유 대역은 다음 시그니처로 만든다. `NotificationUserRepository`의 나머지 명시적 abstract 메서드는 `error("not used")`로 구현하고 `findById`만 조회 횟수를 기록한다.

```kotlin
package kr.easydoc.application.notification

class RecordingOperatorNotifications : OperatorNotifications {
    val events = mutableListOf<OperatorNotificationEvent>()
    var failNext = false
    var onNotify: () -> Unit = {}

    override fun notify(event: OperatorNotificationEvent) {
        onNotify()
        if (failNext) {
            failNext = false
            throw IllegalStateException("recording notification failure")
        }
        events += event
    }
}

class NotificationUserRepository(var user: User? = null) : UserRepository {
    var findByIdCount = 0
        private set

    override fun findById(id: UUID): User? {
        findByIdCount++
        return user?.takeIf { it.id == id }
    }

    override fun findByEmail(email: String): StoredUser? = error("not used")
    override fun exists(id: UUID): Boolean = error("not used")
    override fun lockForUpdate(id: UUID): User? = error("not used")
    override fun create(email: String, passwordHash: PasswordHash): User = error("not used")
    override fun createWithoutPassword(email: String, emailVerified: Boolean): User = error("not used")
    override fun updatePasswordHash(userId: UUID, passwordHash: PasswordHash) = error("not used")
    override fun markEmailVerified(userId: UUID): Boolean = error("not used")
}
```

- [ ] **Step 2: 테스트가 모델 부재로 실패하는지 확인한다**

Run: `./gradlew :application:test --tests 'kr.easydoc.application.notification.OperatorNotificationServiceTest'`

Expected: FAIL. `OperatorNotificationEvent`, `OperatorNotificationService`, `OperatorPaymentKind`를 해석할 수 없어 test Kotlin 컴파일이 실패한다.

- [ ] **Step 3: sealed 모델과 두 포트를 최소 구현한다**

`OperatorNotificationPorts.kt` 전체 공개 모델은 다음 형태로 고정한다.

```kotlin
package kr.easydoc.application.notification

import java.time.Instant
import java.util.UUID

sealed interface OperatorNotificationEvent {
    val userId: UUID
    val occurredAt: Instant
    val eventType: String

    data class MemberSignedUp(
        override val userId: UUID,
        override val occurredAt: Instant,
        val email: String,
        val route: String,
    ) : OperatorNotificationEvent {
        override val eventType: String get() = "member_signed_up"
    }

    data class MemberDeleted(
        override val userId: UUID,
        override val occurredAt: Instant,
        val email: String,
    ) : OperatorNotificationEvent {
        override val eventType: String get() = "member_deleted"
    }

    data class Payment(
        override val userId: UUID,
        override val occurredAt: Instant,
        val kind: OperatorPaymentKind,
        val workspaceId: UUID,
        val orderId: UUID,
        val planId: String,
        val amountKrw: Int,
        val environment: String,
    ) : OperatorNotificationEvent {
        override val eventType: String get() = kind.wireValue
    }
}

enum class OperatorPaymentKind(val wireValue: String) {
    PAYMENT_APPROVED("payment_approved"),
    RENEWAL_APPROVED("renewal_approved"),
    PAYMENT_FAILED("payment_failed"),
    RENEWAL_FAILED("renewal_failed"),
    REFUND_APPROVED("refund_approved"),
    SUBSCRIPTION_CANCELING("subscription_canceling"),
    SUBSCRIPTION_EXPIRED("subscription_expired"),
    SUBSCRIPTION_PAST_DUE("subscription_past_due"),
    SUBSCRIPTION_SUSPENDED("subscription_suspended"),
    PAYMENT_MANUAL_REVIEW("payment_manual_review"),
}

fun interface OperatorNotifications {
    fun notify(event: OperatorNotificationEvent)
}

fun interface OperatorNotificationPort {
    fun send(message: String)
}
```

- [ ] **Step 4: 알림 서비스와 application 테스트 로깅 의존성을 구현한다**

`OperatorNotificationService`는 enabled 검사를 `try` 밖 최상단에 둔다. 회원 이메일은 이벤트 스냅샷을 쓰고 결제만 `findById`로 보강한다.

```kotlin
class OperatorNotificationService(
    private val enabled: Boolean,
    private val users: UserRepository,
    private val port: OperatorNotificationPort,
) : OperatorNotifications {
    private val log = LoggerFactory.getLogger(OperatorNotificationService::class.java)

    override fun notify(event: OperatorNotificationEvent) {
        if (!enabled) return
        try {
            val email = when (event) {
                is OperatorNotificationEvent.MemberSignedUp -> event.email
                is OperatorNotificationEvent.MemberDeleted -> event.email
                is OperatorNotificationEvent.Payment ->
                    users.findById(event.userId)?.email
                        ?: throw IllegalStateException("operator notification owner missing")
            }
            port.send(render(event, email))
        } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
            when (event) {
                is OperatorNotificationEvent.Payment -> log.warn(
                    "운영 알림 발송 실패: eventType={} orderId={} 예외={}",
                    event.eventType, event.orderId, failure::class.java.simpleName,
                )
                else -> log.warn(
                    "운영 알림 발송 실패: eventType={} userId={} 예외={}",
                    event.eventType, event.userId, failure::class.java.simpleName,
                )
            }
        }
    }

    private fun render(event: OperatorNotificationEvent, email: String): String {
        val title = when (event) {
            is OperatorNotificationEvent.MemberSignedUp -> "회원 가입"
            is OperatorNotificationEvent.MemberDeleted -> "회원 탈퇴"
            is OperatorNotificationEvent.Payment -> title(event.kind)
        }
        val testPrefix = if (event is OperatorNotificationEvent.Payment && event.environment == "toss_test") "[TEST] " else ""
        return buildString {
            appendLine("$testPrefix$title")
            appendLine("event: ${event.eventType}")
            appendLine("email: ${maskEmail(email)}")
            appendLine("userId: ${event.userId}")
            appendLine("occurredAt: ${SEOUL_FORMATTER.format(event.occurredAt)}")
            if (event is OperatorNotificationEvent.MemberSignedUp) appendLine("route: ${event.route}")
            if (event is OperatorNotificationEvent.Payment) {
                appendLine("planId: ${event.planId}")
                appendLine("amount: ${event.amountKrw} KRW")
                appendLine("orderId: ${event.orderId}")
                appendLine("workspaceId: ${event.workspaceId}")
                append("environment: ${event.environment}")
            }
        }
    }

    private fun maskEmail(email: String): String {
        val at = email.lastIndexOf('@')
        require(at > 0 && at < email.lastIndex) { "invalid notification email" }
        return email.first() + "***" + email.substring(at)
    }

    private fun title(kind: OperatorPaymentKind): String = when (kind) {
        OperatorPaymentKind.PAYMENT_APPROVED -> "결제 승인"
        OperatorPaymentKind.RENEWAL_APPROVED -> "정기 결제 승인"
        OperatorPaymentKind.PAYMENT_FAILED -> "결제 실패"
        OperatorPaymentKind.RENEWAL_FAILED -> "정기 결제 실패"
        OperatorPaymentKind.REFUND_APPROVED -> "환불 승인"
        OperatorPaymentKind.SUBSCRIPTION_CANCELING -> "구독 해지 예정"
        OperatorPaymentKind.SUBSCRIPTION_EXPIRED -> "구독 만료"
        OperatorPaymentKind.SUBSCRIPTION_PAST_DUE -> "구독 연체"
        OperatorPaymentKind.SUBSCRIPTION_SUSPENDED -> "구독 정지"
        OperatorPaymentKind.PAYMENT_MANUAL_REVIEW -> "결제 수동 확인 필요"
    }

    private companion object {
        val SEOUL_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ISO_ZONED_DATE_TIME.withZone(ZoneId.of("Asia/Seoul"))
    }
}
```

`gradle/libs.versions.toml`의 `slf4j-api` 아래에 BOM 관리 좌표를 추가하고 `application/build.gradle.kts`에는 테스트 의존성만 추가한다.

```toml
# [libraries]에서 slf4j-api 바로 아래에 추가
logback-classic = { module = "ch.qos.logback:logback-classic" }
```

```kotlin
// application/build.gradle.kts dependencies
testImplementation(libs.logback.classic)
```

다음 명령으로 application lock만 갱신하고 diff에서 Logback 1.5.x가 test configuration에만 추가됐는지 확인한다.

```bash
./gradlew :application:dependencies --write-locks
```

- [ ] **Step 5: 단위 테스트를 통과시킨다**

Run: `./gradlew :application:test --tests 'kr.easydoc.application.notification.OperatorNotificationServiceTest'`

Expected: PASS. 회원·결제 메시지, disabled no-op, 소유자 부재와 발송 실패의 단일 정제 WARN이 모두 통과한다.

- [ ] **Step 6: Task 1을 커밋한다**

```bash
git add gradle/libs.versions.toml application/build.gradle.kts application/gradle.lockfile application/src/main/kotlin/kr/easydoc/application/notification/OperatorNotificationPorts.kt application/src/main/kotlin/kr/easydoc/application/notification/OperatorNotificationService.kt application/src/test/kotlin/kr/easydoc/application/notification/OperatorNotificationServiceTest.kt application/src/test/kotlin/kr/easydoc/application/notification/RecordingOperatorNotifications.kt
git commit -m "feat: add operator notification application boundary"
```

---

### Task 2: Telegram 설정·HTTP 어댑터

**Files:**
- Create: `infrastructure/src/main/kotlin/kr/easydoc/infrastructure/telegram/TelegramOperatorNotificationAdapter.kt`
- Create: `infrastructure/src/main/kotlin/kr/easydoc/infrastructure/telegram/TelegramConfiguration.kt`
- Create: `infrastructure/src/test/kotlin/kr/easydoc/infrastructure/telegram/TelegramOperatorNotificationAdapterTest.kt`
- Create: `infrastructure/src/test/kotlin/kr/easydoc/infrastructure/telegram/TelegramPropertiesTest.kt`
- Create: `infrastructure/src/test/kotlin/kr/easydoc/infrastructure/telegram/TelegramConfigurationTest.kt`

**Interfaces:**
- Consumes: `OperatorNotificationPort.send(message: String)`, `OperatorNotificationService(enabled, users, port)`, `Secret.reveal(): String`.
- Produces: `@ConfigurationProperties("easydoc.telegram") data class TelegramProperties(enabled: Boolean, botToken: Secret, chatId: String, connectTimeout: Duration, readTimeout: Duration)`; 다섯 설정만 바인딩한다.
- Produces: `class TelegramOperatorNotificationAdapter(client: RestClient, properties: TelegramProperties) : OperatorNotificationPort`.
- Produces: `TelegramConfiguration.DEFAULT_BASE_URL`, @Bean `telegramRestClient(properties: TelegramProperties)`, 테스트 전용 non-@Bean `telegramRestClient(properties: TelegramProperties, baseUrl: String)` overload, `telegramOperatorNotificationAdapter`, `operatorNotifications` 빈.

- [ ] **Step 1: Telegram wire 계약과 설정 실패 테스트를 작성한다**

JDK `HttpServer` 스텁은 요청 목록과 status/body/delay를 갖는다. 성공 테스트는 JSON을 tree로 읽어 필드가 정확히 `chat_id`, `text` 둘인지 검증한다.

```kotlin
@Test
fun `sendMessage는 토큰 경로와 두 JSON 필드만 한 번 본다`() {
    stub.replyWith(200, """{"ok":true}""")
    val adapter = adapter(readTimeout = Duration.ofSeconds(1))

    adapter.send("가입 <plain> & text")

    val request = stub.received.single()
    assertThat(request.method).isEqualTo("POST")
    assertThat(request.path).isEqualTo("/botfake-bot-token/sendMessage")
    val body = JsonMapper.builder().build().readTree(request.body)
    assertThat(body.size()).isEqualTo(2)
    assertThat(body.path("chat_id").asString()).isEqualTo("-100123")
    assertThat(body.path("text").asString()).isEqualTo("가입 <plain> & text")
    assertThat(body.has("parse_mode")).isFalse()
}

@Test
fun `HTTP 오류와 비정상 응답은 실패이고 재시도하지 않는다`() {
    listOf(
        Triple(500, """{"ok":true}""", "http"),
        Triple(200, """{"ok":false}""", "not ok"),
        Triple(200, """{"ok":"true"}""", "non-boolean ok"),
        Triple(200, """{"ok":true""", "malformed"),
        Triple(204, "", "null body"),
    ).forEach { (status, body, label) ->
        stub.replyWith(status, body)
        val before = stub.received.size
        assertThatThrownBy { adapter().send("secret provider response") }
            .withFailMessage(label)
            .isInstanceOf(RuntimeException::class.java)
        assertThat(stub.received.size).isEqualTo(before + 1)
    }
}

@Test
fun `연결 실패와 읽기 지연은 실패다`() {
    val closedPort = ServerSocket(0).use { it.localPort }
    val settings = properties()
    assertThatThrownBy {
        TelegramOperatorNotificationAdapter(
            TelegramConfiguration().telegramRestClient(settings, "http://127.0.0.1:$closedPort"),
            settings,
        ).send("message")
    }.isInstanceOf(RuntimeException::class.java)

    stub.replyWith(200, """{"ok":true}""", Duration.ofMillis(250))
    assertThatThrownBy { adapter(readTimeout = Duration.ofMillis(50)).send("message") }
        .isInstanceOf(RuntimeException::class.java)
}
```

테스트 fixture는 아래 코드로 고정한다. 매 테스트 후 `stub.close()`를 `@AfterEach`에서 호출한다.

```kotlin
private val stub = StubTelegramServer()

private fun properties(
    readTimeout: Duration = Duration.ofSeconds(1),
): TelegramProperties =
    TelegramProperties(
        enabled = true,
        botToken = Secret("fake-bot-token"),
        chatId = "-100123",
        connectTimeout = Duration.ofMillis(100),
        readTimeout = readTimeout,
    )

private fun adapter(
    baseUrl: String = stub.baseUrl,
    readTimeout: Duration = Duration.ofSeconds(1),
): TelegramOperatorNotificationAdapter {
    val settings = properties(readTimeout = readTimeout)
    return TelegramOperatorNotificationAdapter(
        TelegramConfiguration().telegramRestClient(settings, baseUrl), settings,
    )
}

private class StubTelegramServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var status = 200
    private var body = """{"ok":true}"""
    private var delay = Duration.ZERO
    val received = mutableListOf<RecordedTelegramRequest>()
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            exchange.use {
                received += RecordedTelegramRequest(
                    exchange.requestMethod,
                    exchange.requestURI.toString(),
                    exchange.requestBody.readBytes().toString(Charsets.UTF_8),
                )
                if (!delay.isZero) Thread.sleep(delay.toMillis())
                val payload = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, payload.size.toLong())
                exchange.responseBody.write(payload)
            }
        }
        server.start()
    }

    fun replyWith(status: Int, body: String, delay: Duration = Duration.ZERO) {
        this.status = status
        this.body = body
        this.delay = delay
    }

    override fun close() = server.stop(0)
}

private data class RecordedTelegramRequest(
    val method: String,
    val path: String,
    val body: String,
)
```

`TelegramPropertiesTest`에는 다음 단언을 넣는다.

```kotlin
@Test
fun `기본은 비활성화고 자격 증명 없이 허용한다`() {
    val p = TelegramProperties()
    assertThat(p.enabled).isFalse()
    assertThat(p.connectTimeout).isEqualTo(Duration.ofSeconds(2))
    assertThat(p.readTimeout).isEqualTo(Duration.ofSeconds(5))
}

@Test
fun `활성화 필수값과 양의 timeout을 검증하고 toString은 자격 증명을 숨긴다`() {
    assertThatThrownBy { TelegramProperties(enabled = true) }.isInstanceOf(IllegalArgumentException::class.java)
    assertThatThrownBy {
        TelegramProperties(enabled = true, botToken = Secret("token"), connectTimeout = Duration.ZERO)
    }.isInstanceOf(IllegalArgumentException::class.java)
    assertThatThrownBy {
        TelegramProperties(enabled = true, botToken = Secret("token"), chatId = "chat", readTimeout = Duration.ofSeconds(-1))
    }.isInstanceOf(IllegalArgumentException::class.java)
    val rendered = TelegramProperties(true, Secret("real-looking-token"), "private-chat").toString()
    assertThat(rendered).doesNotContain("real-looking-token", "private-chat")
}
```

- [ ] **Step 2: 새 타입 부재로 테스트가 실패하는지 확인한다**

Run: `./gradlew :infrastructure:test --tests 'kr.easydoc.infrastructure.telegram.TelegramOperatorNotificationAdapterTest' --tests 'kr.easydoc.infrastructure.telegram.TelegramPropertiesTest' --tests 'kr.easydoc.infrastructure.telegram.TelegramConfigurationTest'`

Expected: FAIL. `TelegramProperties`, `TelegramConfiguration`, `TelegramOperatorNotificationAdapter` unresolved reference로 컴파일 실패한다.

- [ ] **Step 3: properties와 Spring 조립을 구현한다**

`TelegramConfiguration.kt`에 설정과 빈을 함께 둔다. `baseUrl`은 `TelegramProperties`에 두지 않으며 YAML이나 `.env.example`에도 노출하지 않는다. 운영 @Bean은 고정 상수를 쓰고, 두 인자 overload는 테스트에서만 로컬 스텁 주소를 받는다.

```kotlin
@ConfigurationProperties("easydoc.telegram")
data class TelegramProperties(
    val enabled: Boolean = false,
    val botToken: Secret = Secret.EMPTY,
    val chatId: String = "",
    @param:DurationUnit(ChronoUnit.SECONDS)
    val connectTimeout: Duration = Duration.ofSeconds(2),
    @param:DurationUnit(ChronoUnit.SECONDS)
    val readTimeout: Duration = Duration.ofSeconds(5),
) {
    init {
        require(!enabled || !botToken.isBlank()) { "easydoc.telegram.bot-token 은 활성화 시 필수입니다" }
        require(!enabled || chatId.isNotBlank()) { "easydoc.telegram.chat-id 는 활성화 시 필수입니다" }
        require(!connectTimeout.isZero && !connectTimeout.isNegative) { "easydoc.telegram.connect-timeout 은 0보다 커야 합니다" }
        require(!readTimeout.isZero && !readTimeout.isNegative) { "easydoc.telegram.read-timeout 은 0보다 커야 합니다" }
    }

    override fun toString(): String =
        "TelegramProperties(enabled=$enabled, botToken=$botToken, chatId=[REDACTED], " +
            "connectTimeout=$connectTimeout, readTimeout=$readTimeout)"
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TelegramProperties::class)
class TelegramConfiguration {
    @Bean
    fun telegramRestClient(properties: TelegramProperties): RestClient =
        telegramRestClient(properties, DEFAULT_BASE_URL)

    // 테스트만 로컬 스텁 baseUrl을 전달한다. Spring @Bean으로 노출하지 않는다.
    fun telegramRestClient(properties: TelegramProperties, baseUrl: String): RestClient {
        val httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build()
        val requests = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(properties.readTimeout) }
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requests).build()
    }

    @Bean
    fun telegramOperatorNotificationAdapter(
        client: RestClient,
        properties: TelegramProperties,
    ): OperatorNotificationPort = TelegramOperatorNotificationAdapter(client, properties)

    @Bean
    fun operatorNotifications(
        properties: TelegramProperties,
        users: UserRepository,
        port: OperatorNotificationPort,
    ): OperatorNotifications = OperatorNotificationService(properties.enabled, users, port)

    companion object {
        internal const val DEFAULT_BASE_URL = "https://api.telegram.org"
    }
}
```

`TelegramConfigurationTest`는 네트워크 없이 다음을 단언한다.

```kotlin
@Test
fun `기본 URL로 RestClient와 출력 포트를 조립한다`() {
    val configuration = TelegramConfiguration()
    val properties = TelegramProperties()
    val client = configuration.telegramRestClient(properties)
    val port = configuration.telegramOperatorNotificationAdapter(client, properties)

    assertThat(TelegramConfiguration.DEFAULT_BASE_URL).isEqualTo("https://api.telegram.org")
    assertThat(port).isInstanceOf(TelegramOperatorNotificationAdapter::class.java)
}
```

- [ ] **Step 4: 단발 어댑터를 구현한다**

```kotlin
class TelegramOperatorNotificationAdapter(
    private val client: RestClient,
    private val properties: TelegramProperties,
) : OperatorNotificationPort {
    private val json = JsonMapper.builder().build()

    override fun send(message: String) {
        val response = client.post()
            .uri("/bot${properties.botToken.reveal()}/sendMessage")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("chat_id" to properties.chatId, "text" to message))
            .retrieve()
            .body(String::class.java)
        val body = requireNotNull(response) { "Telegram sendMessage returned no body" }
        val ok = json.readTree(body).get("ok")
        check(ok != null && ok.isBoolean && ok.booleanValue()) { "Telegram sendMessage failed" }
    }
}
```

`RestClientException`, Jackson malformed 오류, null 본문을 별도 재시도 없이 호출자에게 전파한다. `ok=false`이거나 `ok`가 boolean이 아니면 `IllegalStateException`으로 실패한다. 어댑터에는 재시도와 로거를 두지 않는다.

- [ ] **Step 5: 어댑터·설정 테스트를 통과시킨다**

Run: `./gradlew :infrastructure:test --tests 'kr.easydoc.infrastructure.telegram.TelegramOperatorNotificationAdapterTest'`

Expected: PASS. 성공 요청, HTTP/JSON/null/읽기 지연/연결 실패와 각 실패의 단일 요청이 검증된다.

Run: `./gradlew :infrastructure:test --tests 'kr.easydoc.infrastructure.telegram.TelegramPropertiesTest' --tests 'kr.easydoc.infrastructure.telegram.TelegramConfigurationTest'`

Expected: PASS. 기본값, 필수 설정, timeout, secret redaction과 기본 조립이 통과한다.

- [ ] **Step 6: Task 2를 커밋한다**

```bash
git add infrastructure/src/main/kotlin/kr/easydoc/infrastructure/telegram/TelegramOperatorNotificationAdapter.kt infrastructure/src/main/kotlin/kr/easydoc/infrastructure/telegram/TelegramConfiguration.kt infrastructure/src/test/kotlin/kr/easydoc/infrastructure/telegram/TelegramOperatorNotificationAdapterTest.kt infrastructure/src/test/kotlin/kr/easydoc/infrastructure/telegram/TelegramPropertiesTest.kt infrastructure/src/test/kotlin/kr/easydoc/infrastructure/telegram/TelegramConfigurationTest.kt
git commit -m "feat: add telegram notification adapter"
```

---

### Task 3: API·worker 설정과 환경 예시

**Files:**
- Modify: `api/src/main/resources/application.yml`
- Modify: `worker/src/main/resources/application.yml`
- Modify: `.env.example`
- Test: `api/src/test/kotlin/kr/easydoc/api/ConfigurationPropertiesBindingTest.kt`

**Interfaces:**
- Consumes: `TelegramProperties(enabled, botToken, chatId, connectTimeout, readTimeout)`.
- Produces: `easydoc.telegram.{enabled,bot-token,chat-id,connect-timeout,read-timeout}` 및 다섯 `EASYDOC_TELEGRAM_*` 환경변수. `base-url`은 공개 설정으로 만들지 않는다.

- [ ] **Step 1: 바인딩과 두 YAML 블록·환경 예시의 실패 테스트를 추가한다**

`ConfigurationPropertiesBindingTest`에 `TelegramProperties` import와 다음 테스트를 추가한다. Gradle `Test`의 기본 working directory인 `api/`를 기준으로 상대 경로를 사용한다.

```kotlin
@Test
fun `Telegram 설정과 두 실행 모듈의 환경변수 계약이 일치한다`() {
    val properties = bind(
        "easydoc.telegram",
        TelegramProperties::class.java,
        mapOf(
            "easydoc.telegram.enabled" to "true",
            "easydoc.telegram.bot-token" to SECRET_VALUE,
            "easydoc.telegram.chat-id" to "-100999",
            "easydoc.telegram.connect-timeout" to "3",
            "easydoc.telegram.read-timeout" to "7",
        ),
    )
    assertThat(properties.enabled).isTrue()
    assertThat(properties.botToken.reveal()).isEqualTo(SECRET_VALUE)
    assertThat(properties.chatId).isEqualTo("-100999")
    assertThat(properties.connectTimeout).isEqualTo(Duration.ofSeconds(3))
    assertThat(properties.readTimeout).isEqualTo(Duration.ofSeconds(7))

    val block = """
      telegram:
        enabled: ${'$'}{EASYDOC_TELEGRAM_ENABLED:false}
        bot-token: ${'$'}{EASYDOC_TELEGRAM_BOT_TOKEN:}
        chat-id: ${'$'}{EASYDOC_TELEGRAM_CHAT_ID:}
        connect-timeout: ${'$'}{EASYDOC_TELEGRAM_CONNECT_TIMEOUT:2}
        read-timeout: ${'$'}{EASYDOC_TELEGRAM_READ_TIMEOUT:5}
    """.trimIndent()
    val api = Files.readString(Path.of("src/main/resources/application.yml"))
    val worker = Files.readString(Path.of("../worker/src/main/resources/application.yml"))
    assertThat(api).contains(block)
    assertThat(worker).contains(block)
    assertThat(api).doesNotContain("telegram:\n    base-url")
    assertThat(worker).doesNotContain("telegram:\n    base-url")

    val env = Files.readString(Path.of("../../.env.example"))
    listOf("ENABLED=false", "BOT_TOKEN=", "CHAT_ID=", "CONNECT_TIMEOUT=2", "READ_TIMEOUT=5").forEach {
        assertThat(env).contains("EASYDOC_TELEGRAM_$it")
    }
}
```

- [ ] **Step 2: 설정 부재로 테스트가 실패하는지 확인한다**

Run: `./gradlew :api:test --tests 'kr.easydoc.api.ConfigurationPropertiesBindingTest'`

Expected: FAIL. 두 `application.yml`에 `telegram` 블록이 없어 `contains(block)` 단언이 실패한다.

- [ ] **Step 3: 동일한 설정 블록과 비밀 없는 예시를 추가한다**

`easydoc:` 바로 아래에 API와 worker 모두 다음 YAML을 같은 들여쓰기로 추가한다.

```yaml
  telegram:
    enabled: ${EASYDOC_TELEGRAM_ENABLED:false}
    bot-token: ${EASYDOC_TELEGRAM_BOT_TOKEN:}
    chat-id: ${EASYDOC_TELEGRAM_CHAT_ID:}
    connect-timeout: ${EASYDOC_TELEGRAM_CONNECT_TIMEOUT:2}
    read-timeout: ${EASYDOC_TELEGRAM_READ_TIMEOUT:5}
```

`.env.example`의 Kotlin 백엔드 공통 영역에 다음을 추가한다. token과 chat ID는 빈 값으로 둔다.

```dotenv
# Telegram 운영 알림. 실제 Bot token과 chat ID는 .env 또는 배포 Secret에만 넣는다.
EASYDOC_TELEGRAM_ENABLED=false
EASYDOC_TELEGRAM_BOT_TOKEN=
EASYDOC_TELEGRAM_CHAT_ID=
EASYDOC_TELEGRAM_CONNECT_TIMEOUT=2
EASYDOC_TELEGRAM_READ_TIMEOUT=5
```

- [ ] **Step 4: 바인딩 테스트와 Compose 해석을 통과시킨다**

Run: `./gradlew :api:test --tests 'kr.easydoc.api.ConfigurationPropertiesBindingTest'`

Expected: PASS. typed duration과 secret 바인딩, API·worker 블록 동일성, 환경 예시가 통과한다.

Run: `docker compose config`

Expected: PASS. 기존 `env_file` 구성이 유효하며 Compose 파일 diff는 없다.

- [ ] **Step 5: Task 3을 커밋한다**

```bash
git add api/src/main/resources/application.yml worker/src/main/resources/application.yml .env.example api/src/test/kotlin/kr/easydoc/api/ConfigurationPropertiesBindingTest.kt
git commit -m "chore: configure telegram operator notifications"
```

---

### Task 4: 이메일·소셜 최초 가입 알림

**Files:**
- Modify: `application/src/main/kotlin/kr/easydoc/application/auth/AuthService.kt`
- Modify: `application/src/main/kotlin/kr/easydoc/application/auth/SocialLoginService.kt`
- Modify: `infrastructure/src/main/kotlin/kr/easydoc/infrastructure/auth/AuthConfiguration.kt`
- Modify: `api/src/test/kotlin/kr/easydoc/api/support/AuthSliceBeans.kt`
- Test: `application/src/test/kotlin/kr/easydoc/application/auth/AuthServiceTest.kt`
- Test: `application/src/test/kotlin/kr/easydoc/application/auth/SocialLoginServiceTest.kt`

**Interfaces:**
- Consumes: `OperatorNotifications.notify`, `MemberSignedUp(userId, occurredAt, email, route)`, `User.createdAt`, `SocialLoginProviderId.wireValue`.
- Produces: `AuthService(private val users: UserRepository, private val workspaces: WorkspaceRepository, private val passwords: PasswordHasher, private val accessTokens: AccessTokens, private val transaction: TransactionRunner, private val emailVerification: PostSignupEmailVerification, private val credits: CreditAccountService, private val operatorNotifications: OperatorNotifications)`.
- Produces: `SocialLoginService(private val providers: Map<SocialLoginProviderId, SocialLoginProvider>, private val states: OAuthStateStore, private val repositories: SocialLoginRepositories, private val accessTokens: AccessTokens, private val transaction: TransactionRunner, private val stateTtl: Duration, private val emailVerification: PostSignupEmailVerification, private val credits: CreditAccountService, private val operatorNotifications: OperatorNotifications)`.
- Produces: 소셜 콜백 트랜잭션 결과 `Pair<User?, IssuedAccessToken>`; 첫 값은 실제 생성한 `User` 스냅샷, 경쟁 복구·기존 로그인은 `null`.

- [ ] **Step 1: 가입 알림과 제외 경로의 실패 테스트를 작성한다**

`World`, `SocialWorld`, `RacedSocialWorld`에 각각 `val notifications = RecordingOperatorNotifications()`를 추가하고 마지막 생성자 인자로 주입한다. `AuthServiceTest`에는 다음을 추가한다.

```kotlin
@Test
fun `이메일 가입은 생성 스냅샷을 커밋 뒤 한 번 알린다`() {
    val world = World()
    var depth = -1
    world.notifications.onNotify = { depth = world.transaction.depth }

    val created = world.service.signup(" New@Example.Test ", VALID_PASSWORD)

    assertThat(depth).isZero()
    assertThat(world.notifications.events).containsExactly(
        OperatorNotificationEvent.MemberSignedUp(created.id, created.createdAt, created.email, "email"),
    )
}

@Test
fun `가입 생성 실패와 로그인은 알리지 않는다`() {
    val world = World()
    world.users.failNextCreate = true
    assertThatThrownBy { world.service.signup(uniqueEmail(), VALID_PASSWORD) }
    assertThat(world.notifications.events).isEmpty()

    val email = uniqueEmail()
    world.service.signup(email, VALID_PASSWORD)
    world.notifications.events.clear()
    world.service.login(email, VALID_PASSWORD)
    assertThat(world.notifications.events).isEmpty()
}

@Test
fun `Telegram 준비 발송 실패 후에도 가입 결과가 유지된다`() {
    val world = World()
    val bestEffort = OperatorNotificationService(true, world.users) {
        throw IllegalStateException("sensitive provider failure")
    }
    val service = world.serviceWith(bestEffort)

    val created = service.signup(uniqueEmail(), VALID_PASSWORD)

    assertThat(world.users.findById(created.id)).isEqualTo(created)
}
```

`RecordingUserRepository`에 `var failNextCreate = false`를 추가하고 `create` 첫 줄에 다음을 둔다. `World.serviceWith`는 기존 협력자와 전달받은 포트로 같은 시그니처의 `AuthService`를 반환한다.

```kotlin
if (failNextCreate) {
    failNextCreate = false
    throw IllegalStateException("simulated create rollback")
}
```

```kotlin
fun serviceWith(notifications: OperatorNotifications): AuthService =
    AuthService(users, workspaces, hasher, tokens, transaction, emailVerification, credits, notifications)
```

`SocialLoginServiceTest`의 `새 신원이 계정을 만든다`에는 생성된 `User`와 `google` route를 단언하고, `네이버 최초 가입은 미검증 계정을 만들고 인증 코드를 발급한다`에는 알림 한 건과 기존 인증 발급 한 건을 함께 단언한다.

```kotlin
val created = world.users.saved.getValue("new@example.test").user
assertThat(world.notifications.events).containsExactly(
    OperatorNotificationEvent.MemberSignedUp(created.id, created.createdAt, created.email, "google"),
)
```

기존 로그인·경쟁 복구·진짜 중복·`linkCallback`·`unlink` 테스트에는 다음 단언을 추가한다. `RacedSocialWorld.notifications`를 노출해 경쟁 복구에서도 같은 단언을 한다.

```kotlin
assertThat(world.notifications.events).isEmpty()
```

- [ ] **Step 2: 생성자 불일치와 미발송으로 실패를 확인한다**

Run: `./gradlew :application:test --tests 'kr.easydoc.application.auth.AuthServiceTest'`

Expected: FAIL. `AuthService`가 아직 알림 포트와 이벤트 생성을 갖지 않아 생성자 또는 이벤트 단언이 실패한다.

Run: `./gradlew :application:test --tests 'kr.easydoc.application.auth.SocialLoginServiceTest'`

Expected: FAIL. `SocialLoginService` 생성자 불일치 또는 신규 가입 이벤트 부재로 실패한다.

- [ ] **Step 3: 이메일 가입의 커밋 후 호출을 구현한다**

`AuthService` 마지막 생성자 매개변수로 `private val operatorNotifications: OperatorNotifications`를 추가한다. `transaction.inTransaction` 반환 직후, 기존 이메일 인증 호출은 유지한 채 다음을 실행한다.

```kotlin
operatorNotifications.notify(
    OperatorNotificationEvent.MemberSignedUp(
        userId = created.id,
        occurredAt = created.createdAt,
        email = created.email,
        route = "email",
    ),
)
emailVerification.issueAfterSignup(created.id)
return created
```

- [ ] **Step 4: 소셜 신규 User 스냅샷과 제외 조건을 구현한다**

`SocialLoginService` 마지막 생성자 매개변수로 같은 입력 포트를 추가한다. 콜백의 `newUserId`를 `newUser: User?`로 바꾸고 트랜잭션 성공 결과는 `user to accessTokens.issue(user.id)`, 경쟁 복구 결과는 `null to accessTokens.issue(racedWinner.userId)`로 유지한다. 커밋 뒤 코드는 다음이다.

```kotlin
if (newUser != null) {
    operatorNotifications.notify(
        OperatorNotificationEvent.MemberSignedUp(
            userId = newUser.id,
            occurredAt = newUser.createdAt,
            email = newUser.email,
            route = providerId.wireValue,
        ),
    )
    if (!identity.emailVerified) {
        emailVerification.issueAfterSignup(newUser.id)
    }
}
return issued
```

기존 신원의 조기 반환과 `linkCallback`/`unlink`에는 알림 호출을 추가하지 않는다.

- [ ] **Step 5: 제품·슬라이스 배선을 갱신한다**

`AuthConfiguration.authService`와 `socialLoginService`에 `operatorNotifications: OperatorNotifications` 매개변수를 추가하고 동일 이름으로 생성자에 전달한다. `AuthSliceBeans`에 no-op 빈을 추가하고 두 실물 서비스 생성자에도 전달한다.

```kotlin
@Bean
fun operatorNotifications(): OperatorNotifications = OperatorNotifications { }
```

```kotlin
AuthService(users, workspaces, hasher, tokens, transaction, emailVerification, credits, operatorNotifications)
```

```kotlin
SocialLoginService(
    providers = providers,
    states = states,
    repositories = repositories,
    accessTokens = tokens,
    transaction = transaction,
    stateTtl = Duration.ofMinutes(10),
    emailVerification = emailVerification,
    credits = credits,
    operatorNotifications = operatorNotifications,
)
```

- [ ] **Step 6: 회원 가입 테스트와 생성자 컴파일을 통과시킨다**

Run: `./gradlew :application:test --tests 'kr.easydoc.application.auth.AuthServiceTest'`

Expected: PASS. 이메일 가입만 커밋 후 한 번 알리고 생성 실패·로그인은 미발송, 실제 best-effort 실패 후에도 가입이 유지된다.

Run: `./gradlew :application:test --tests 'kr.easydoc.application.auth.SocialLoginServiceTest'`

Expected: PASS. provider별 최초 가입만 알리고 기존 로그인·경쟁 복구·연결·해제·중복 롤백은 미발송이다.

Run: `./gradlew :infrastructure:compileTestKotlin :api:compileTestKotlin`

Expected: PASS. 제품 및 API 슬라이스 생성자 영향이 모두 해소된다.

- [ ] **Step 7: Task 4를 커밋한다**

```bash
git add application/src/main/kotlin/kr/easydoc/application/auth/AuthService.kt application/src/main/kotlin/kr/easydoc/application/auth/SocialLoginService.kt application/src/test/kotlin/kr/easydoc/application/auth/AuthServiceTest.kt application/src/test/kotlin/kr/easydoc/application/auth/SocialLoginServiceTest.kt infrastructure/src/main/kotlin/kr/easydoc/infrastructure/auth/AuthConfiguration.kt api/src/test/kotlin/kr/easydoc/api/support/AuthSliceBeans.kt
git commit -m "feat: notify member signups"
```

---

### Task 5: 잠금 행 이메일과 탈퇴 알림

**Files:**
- Modify: `application/src/main/kotlin/kr/easydoc/application/account/AccountDeletionPorts.kt`
- Modify: `application/src/main/kotlin/kr/easydoc/application/account/DeleteAccountService.kt`
- Modify: `infrastructure/src/main/kotlin/kr/easydoc/infrastructure/auth/JdbcAccountDeletionRepository.kt`
- Modify: `infrastructure/src/main/kotlin/kr/easydoc/infrastructure/auth/AuthConfiguration.kt`
- Modify: `api/src/test/kotlin/kr/easydoc/api/support/AuthSliceBeans.kt`
- Test: `application/src/test/kotlin/kr/easydoc/application/account/DeleteAccountServiceTest.kt`
- Test: `infrastructure/src/test/kotlin/kr/easydoc/infrastructure/auth/JdbcAccountDeletionRepositoryTest.kt`

**Interfaces:**
- Consumes: `OperatorNotificationEvent.MemberDeleted`, `LockedAccount`, `Clock.instant(): Instant`.
- Produces: `data class LockedAccount(val isAdmin: Boolean, val passwordHash: PasswordHash?, val email: String)`.
- Produces: `DeleteAccountService(private val accounts: AccountDeletionRepository, private val passwords: PasswordHasher, private val transaction: TransactionRunner, private val mail: MailSender, private val operatorEmail: String, private val operatorNotifications: OperatorNotifications, private val clock: Clock)`.
- Produces: 트랜잭션 결과 `AccountDeletionResult(pendingInvoiceRequestIds: List<UUID>, deletedEmail: String)`.

- [ ] **Step 1: 탈퇴 스냅샷·제외 경로·JDBC 매핑 실패 테스트를 작성한다**

`DeleteAccountWorld`에 고정 시계와 기록 대역을 추가하고 `serviceWith`로 다른 입력 포트도 주입 가능하게 한다.

```kotlin
private class DeleteAccountWorld(operatorEmail: String = "") {
    val accounts = FakeAccountDeletionRepository()
    val transaction = RecordingDeletionTransactionRunner()
    val mail = RecordingDeletionMailSender()
    val notifications = RecordingOperatorNotifications()
    val clock = Clock.fixed(Instant.parse("2026-10-25T01:02:03Z"), ZoneOffset.UTC)
    val service = serviceWith(notifications)

    fun serviceWith(operatorNotifications: OperatorNotifications): DeleteAccountService =
        DeleteAccountService(
            accounts, StubDeletionPasswordHasher(), transaction, mail,
            operatorEmail, operatorNotifications, clock,
        )
}
```

성공 테스트에는 다음을 추가하고 `FakeAccountDeletionRepository.seed`는 `email: String = "deleted@example.test"`를 받아 `LockedAccount(isAdmin, passwordHash, email)`에 저장한다.

```kotlin
assertThat(world.notifications.events).containsExactly(
    OperatorNotificationEvent.MemberDeleted(
        userId, world.clock.instant(), "deleted@example.test",
    ),
)
```

확인 문구 실패·비밀번호 실패·관리자 거부·계정 부재 테스트에는 `notifications.events.isEmpty()`를 단언한다. 롤백과 best-effort는 다음 테스트로 고정한다.

```kotlin
@Test
fun `삭제 롤백은 미발송이고 알림 실패는 탈퇴 결과를 바꾸지 않는다`() {
    val rolledBack = DeleteAccountWorld()
    val userId = rolledBack.accounts.seed(isAdmin = false, passwordHash = null)
    rolledBack.accounts.failNextDelete = true
    assertThatThrownBy {
        rolledBack.service.deleteAccount(userId, null, DeleteAccountService.CONFIRMATION_PHRASE)
    }
    assertThat(rolledBack.notifications.events).isEmpty()

    val world = DeleteAccountWorld()
    val deletedId = world.accounts.seed(isAdmin = false, passwordHash = null)
    val bestEffort = OperatorNotificationService(true, NotificationUserRepository()) {
        throw IllegalStateException("sensitive provider failure")
    }
    world.serviceWith(bestEffort).deleteAccount(deletedId, null, DeleteAccountService.CONFIRMATION_PHRASE)
    assertThat(world.accounts.usersDeleted).containsExactly(deletedId)
}
```

`FakeAccountDeletionRepository`에 `var failNextDelete = false`를 추가하고 `deleteUser` 첫 줄에서 플래그를 소비하며 `IllegalStateException`을 던진다. 기존 메일 테스트에는 알림 한 건과 메일 한 통이 공존함을 단언한다.

`JdbcAccountDeletionRepositoryTest.lockForDeletion 은 상태를 읽는다`에는 저장한 이메일과 같은지 직접 조회해 비교한다.

```kotlin
val expectedEmail = jdbcClient.sql("SELECT email FROM users WHERE id = :id")
    .param("id", passwordUser).query(String::class.java).single()
assertThat(repository.lockForDeletion(passwordUser)?.email).isEqualTo(expectedEmail)
```

- [ ] **Step 2: application과 JDBC 테스트 실패를 확인한다**

Run: `./gradlew :application:test --tests 'kr.easydoc.application.account.DeleteAccountServiceTest'`

Expected: FAIL. `LockedAccount.email`, `Clock`, 알림 포트 생성자 불일치로 실패한다.

Run: `./gradlew :infrastructure:test --tests 'kr.easydoc.infrastructure.auth.JdbcAccountDeletionRepositoryTest'`

Expected: FAIL. `LockedAccount` 생성자 또는 `email` 프로퍼티가 없어 컴파일 실패한다.

- [ ] **Step 3: LockedAccount와 JDBC SELECT를 확장한다**

```kotlin
data class LockedAccount(
    val isAdmin: Boolean,
    val passwordHash: PasswordHash?,
    val email: String,
) {
    val hasPassword: Boolean get() = passwordHash != null
}
```

JDBC는 동일한 잠금 질의에서 이메일을 함께 읽는다.

```kotlin
jdbc.sql("SELECT email, is_admin, password_hash FROM users WHERE id = :userId FOR UPDATE")
```

```kotlin
LockedAccount(
    email = rs.getString("email"),
    isAdmin = rs.getBoolean("is_admin"),
    passwordHash = rs.getString("password_hash")?.let(::PasswordHash),
)
```

`AuthSliceBeans.InMemoryAccountDeletionRepository.lockForDeletion`도 `LockedAccount(isAdmin = userId in admins, passwordHash = stored.passwordHash, email = stored.user.email)`을 반환하게 한다.

- [ ] **Step 4: 삭제 결과와 커밋 후 알림을 구현한다**

`DeleteAccountService`에 `operatorNotifications`, `clock`을 추가하고 private 결과 타입을 둔다.

```kotlin
private data class AccountDeletionResult(
    val pendingInvoiceRequestIds: List<UUID>,
    val deletedEmail: String,
)
```

트랜잭션은 `ids` 대신 `AccountDeletionResult(ids, locked.email)`을 반환한다. 커밋 뒤 기존 감사 로그와 `notifyOperatorIfPending`을 그대로 실행한 다음 알린다.

```kotlin
val result = transaction.inTransaction {
    val locked = accounts.lockForDeletion(userId)
        ?: throw InvalidCredentialsException(ACCOUNT_GONE_MESSAGE)
    if (locked.isAdmin) throw ConflictException(ADMIN_CANNOT_DELETE_MESSAGE)
    if (locked.hasPassword) requireMatchingPassword(rawPassword, locked.passwordHash)
    val ids = accounts.pendingInvoiceRequestIds(userId)
    accounts.deleteConversionFeedback(userId)
    accounts.deleteUser(userId)
    AccountDeletionResult(ids, locked.email)
}

log.info("계정을 탈퇴 처리했다: userId={}", userId)
notifyOperatorIfPending(result.pendingInvoiceRequestIds)
operatorNotifications.notify(
    OperatorNotificationEvent.MemberDeleted(userId, clock.instant(), result.deletedEmail),
)
```

- [ ] **Step 5: 제품·슬라이스 배선을 갱신한다**

`AuthConfiguration.deleteAccountService`에 `operatorNotifications: OperatorNotifications`를 추가하고 다음 인자를 전달한다.

```kotlin
DeleteAccountService(
    accounts = accounts,
    passwords = passwordHasher,
    transaction = transactionRunner,
    mail = mailSender,
    operatorEmail = billingProperties.operatorEmail,
    operatorNotifications = operatorNotifications,
    clock = Clock.systemUTC(),
)
```

`AuthSliceBeans.deleteAccountService`는 Task 4에서 추가한 no-op `OperatorNotifications` 빈을 재사용한다. 같은 빈을 중복 선언하지 않는다. Spring에 `Clock` 빈을 새로 요구하지 않고 제품과 동일하게 메서드 안에서 `Clock.systemUTC()`를 전달한다.

```kotlin
@Bean
fun deleteAccountService(
    accounts: InMemoryAccountDeletionRepository,
    hasher: StubPasswordHasher,
    transaction: TransactionRunner,
    mail: FakeMailSender,
    operatorNotifications: OperatorNotifications,
): DeleteAccountService =
    DeleteAccountService(
        accounts = accounts,
        passwords = hasher,
        transaction = transaction,
        mail = mail,
        operatorEmail = "",
        operatorNotifications = operatorNotifications,
        clock = Clock.systemUTC(),
    )
```

- [ ] **Step 6: 탈퇴·JDBC·생성자 영향 검증을 통과시킨다**

Run: `./gradlew :application:test --tests 'kr.easydoc.application.account.DeleteAccountServiceTest'`

Expected: PASS. 커밋 후 삭제 시각·이메일 알림, 검증 실패·롤백 미발송, 기존 메일 유지와 best-effort가 통과한다.

Run: `./gradlew :infrastructure:test --tests 'kr.easydoc.infrastructure.auth.JdbcAccountDeletionRepositoryTest'`

Expected: PASS. `email`, `is_admin`, `password_hash` 잠금 매핑과 기존 삭제·FK 테스트가 통과한다.

Run: `./gradlew :infrastructure:compileTestKotlin :api:compileTestKotlin`

Expected: PASS. `LockedAccount`와 `DeleteAccountService` 생성자 영향이 해소된다.

- [ ] **Step 7: Task 5를 커밋한다**

```bash
git add application/src/main/kotlin/kr/easydoc/application/account/AccountDeletionPorts.kt application/src/main/kotlin/kr/easydoc/application/account/DeleteAccountService.kt application/src/test/kotlin/kr/easydoc/application/account/DeleteAccountServiceTest.kt infrastructure/src/main/kotlin/kr/easydoc/infrastructure/auth/JdbcAccountDeletionRepository.kt infrastructure/src/main/kotlin/kr/easydoc/infrastructure/auth/AuthConfiguration.kt infrastructure/src/test/kotlin/kr/easydoc/infrastructure/auth/JdbcAccountDeletionRepositoryTest.kt api/src/test/kotlin/kr/easydoc/api/support/AuthSliceBeans.kt
git commit -m "feat: notify account deletion"
```

---

### Task 6: Toss 주문 승인·실패·환불·수동 확인 알림

**Files:**
- Modify: `application/src/main/kotlin/kr/easydoc/application/subscription/TossBillingService.kt`
- Modify: `infrastructure/src/main/kotlin/kr/easydoc/infrastructure/subscription/TossConfiguration.kt`
- Test: `application/src/test/kotlin/kr/easydoc/application/subscription/TossBillingServiceTest.kt`

**Interfaces:**
- Consumes: `OperatorNotifications`, `OperatorNotificationEvent.Payment`, `OperatorPaymentKind`, `BillingOrder`, `TossPayment`.
- Produces: `TossBillingService(private val store: TossBillingStore, private val subscriptions: SubscriptionStore, private val accounts: CreditAccountRepository, private val credits: CreditAccountService, private val transaction: TransactionRunner, private val gateway: TossGateway, val enabled: Boolean, private val clock: Clock, private val zone: ZoneId, val clientKey: Secret = Secret.EMPTY, private val users: kr.easydoc.application.auth.UserRepository, private val timing: TossBillingTiming = TossBillingTiming(), val environment: String = "toss_test", val purchaseEnabled: Boolean = enabled, val autoChargeEnabled: Boolean = enabled, val allowedWorkspaces: Set<UUID> = emptySet(), private val operatorNotifications: OperatorNotifications)`; 마지막 필수 인자로 두어 직접 생성 지점을 컴파일러가 찾게 한다.
- Produces: `private fun paymentEvent(owner: UUID, order: BillingOrder, kind: OperatorPaymentKind): OperatorNotificationEvent.Payment`.
- Produces: `private fun notifyAll(events: List<OperatorNotificationEvent.Payment>)`, `private fun failedOrderEvent(owner: UUID, current: BillingOrder): OperatorNotificationEvent.Payment?`.
- Produces: `UNSETTLED_ORDER_STATUSES = setOf("pending", "processing", "scheduled", "manual_review")`, `FAILURE_SOURCE_STATUSES = setOf("pending", "processing", "scheduled")`.

- [ ] **Step 1: 주문 전이와 멱등성 실패 테스트를 작성한다**

`Fixture`에 `val notifications = RecordingOperatorNotifications()`를 추가하고 마지막 생성자 인자로 주입한다. `recovery cutoff uses the injected clock and retry window`의 직접 생성에는 `operatorNotifications = OperatorNotifications { }`를 추가한다.

```kotlin
@Test
fun `charge 승인은 커밋 후 한 번이고 재처리는 미발송이다`() {
    val f = Fixture()
    f.gateway.payment = f.payment(f.now)

    f.service.process(f.order.id)
    f.service.process(f.order.id)
    f.service.recheck(f.order.id)

    val event = f.notifications.events.single() as OperatorNotificationEvent.Payment
    assertThat(event.kind).isEqualTo(OperatorPaymentKind.PAYMENT_APPROVED)
    assertThat(event.userId).isEqualTo(f.owner)
    assertThat(event.orderId).isEqualTo(f.order.id)
    assertThat(event.workspaceId).isEqualTo(f.workspace)
    assertThat(event.planId).isEqualTo("start")
    assertThat(event.amountKrw).isEqualTo(99000)
    assertThat(event.environment).isEqualTo("toss_test")
}

@Test
fun `서로 다른 renewal 재시도 주문 실패는 각각 알린다`() {
    val f = Fixture()
    f.gateway.decline = true
    f.store.orders[f.order.id] = f.order.copy(kind = "renewal")
    f.service.process(f.order.id)
    val next = f.store.orders.values.single { it.status == "scheduled" }

    f.service.process(next.id)

    val events = f.notifications.events.filterIsInstance<OperatorNotificationEvent.Payment>()
    assertThat(events.map { it.kind }).containsExactly(
        OperatorPaymentKind.RENEWAL_FAILED, OperatorPaymentKind.RENEWAL_FAILED,
    )
    assertThat(events.map { it.orderId }).doesNotHaveDuplicates()
}

@Test
fun `환불은 원 결제가 아니라 환불 작업 주문의 실제 paid 전이를 알린다`() {
    val f = Fixture()
    val originalPayment = f.payment(f.now)
    val original = f.order.copy(status = "paid", payment = originalPayment)
    val operation = BillingOrder(
        UUID.randomUUID(), f.workspace, original.planId, 1000, f.now, original.cycleEndsAt,
        kind = "refund", originalId = original.id, previousRemaining = 99000,
    )
    f.store.orders[original.id] = original
    f.store.orders[operation.id] = operation
    f.gateway.payment = originalPayment.copy(status = "PARTIAL_CANCELED", remainingAmount = 98000)

    f.service.recheck(operation.id)
    f.service.recheck(operation.id)

    val event = f.notifications.events.single() as OperatorNotificationEvent.Payment
    assertThat(event.kind).isEqualTo(OperatorPaymentKind.REFUND_APPROVED)
    assertThat(event.orderId).isEqualTo(operation.id)
    assertThat(event.amountKrw).isEqualTo(1000)
}

@Test
fun `manual_review는 첫 전이만 알리고 알림 실패는 paid 저장을 유지한다`() {
    val review = Fixture()
    review.store.orders[review.order.id] = review.order.copy(createdAt = review.now.minus(Duration.ofDays(15)))
    review.service.process(review.order.id)
    review.service.process(review.order.id)
    assertThat(review.notifications.events.map { (it as OperatorNotificationEvent.Payment).kind })
        .containsExactly(OperatorPaymentKind.PAYMENT_MANUAL_REVIEW)

    val approved = Fixture()
    approved.gateway.payment = approved.payment(approved.now)
    val bestEffort = OperatorNotificationService(
        true,
        NotificationUserRepository(User(approved.owner, "billing@example.test", approved.now, hasPassword = true)),
    ) { throw IllegalStateException("sensitive provider failure") }
    approved.serviceWith(bestEffort).process(approved.order.id)
    assertThat(approved.store.orders[approved.order.id]?.status).isEqualTo("paid")
}
```

`Fixture`가 기존 생성자에서 쓰는 `credits`, `transaction`, `clock`, `users`, `timing`, `purchase`, `autoCharge`, `allowedWorkspaces`를 프로퍼티로 보관하고, 기본 `service`는 `serviceWith(notifications)`로 만든다. 기존 `private val subscriptions`와 `private val accounts`는 private을 유지하며, `Fixture` 클래스 안의 `serviceWith`가 동일 프로퍼티를 그대로 전달한다. `RecordingStore`의 기존 저장·검증 로직은 바꾸지 않는다. 다른 입력 포트로만 교체하는 helper는 다음과 같다.

```kotlin
fun serviceWith(operatorNotifications: OperatorNotifications): TossBillingService =
    TossBillingService(
        store = store,
        subscriptions = subscriptions,
        accounts = accounts,
        credits = credits,
        transaction = transaction,
        gateway = gateway,
        enabled = true,
        clock = clock,
        zone = zone,
        users = users,
        timing = timing,
        purchaseEnabled = purchase,
        autoChargeEnabled = autoCharge,
        allowedWorkspaces = allowedWorkspaces,
        operatorNotifications = operatorNotifications,
    )
```

- [ ] **Step 2: 주문 알림 테스트가 실패하는지 확인한다**

Run: `./gradlew :application:test --tests 'kr.easydoc.application.subscription.TossBillingServiceTest'`

Expected: FAIL. 새 마지막 생성자 인자 불일치와 `notifications.events`가 비어 있어 실패한다.

- [ ] **Step 3: 공통 주문 이벤트 팩토리를 추가한다**

`TossBillingService` 마지막 생성자 매개변수에 `private val operatorNotifications: OperatorNotifications`를 추가하고 다음 헬퍼를 둔다.

```kotlin
private fun paymentEvent(
    owner: UUID,
    order: BillingOrder,
    kind: OperatorPaymentKind,
): OperatorNotificationEvent.Payment =
    OperatorNotificationEvent.Payment(
        userId = owner,
        occurredAt = clock.instant(),
        kind = kind,
        workspaceId = order.workspaceId,
        orderId = order.id,
        planId = order.planId,
        amountKrw = order.amount,
        environment = order.environment,
    )

private fun notifyAll(events: List<OperatorNotificationEvent.Payment>) {
    events.forEach(operatorNotifications::notify)
}

private fun failedOrderEvent(
    owner: UUID,
    current: BillingOrder,
): OperatorNotificationEvent.Payment? {
    if (current.status !in FAILURE_SOURCE_STATUSES) return null
    val kind = when (current.kind) {
        "charge" -> OperatorPaymentKind.PAYMENT_FAILED
        "renewal" -> OperatorPaymentKind.RENEWAL_FAILED
        else -> return null
    }
    return paymentEvent(owner, current, kind)
}

private fun approvedKind(order: BillingOrder): OperatorPaymentKind = when (order.kind) {
    "charge" -> OperatorPaymentKind.PAYMENT_APPROVED
    "renewal" -> OperatorPaymentKind.RENEWAL_APPROVED
    else -> throw IllegalArgumentException("unsupported approval kind")
}
```

companion에 `val UNSETTLED_ORDER_STATUSES`와 `val FAILURE_SOURCE_STATUSES`를 위 집합으로 추가한다.

- [ ] **Step 4: finish를 원 주문과 환불 작업 주문의 fresh 상태로 분리한다**

`finish`의 `owned`는 이벤트 목록을 반환하고 반환 뒤 `notifyAll`을 호출한다. 핵심 구현은 다음 순서를 정확히 따른다.

```kotlin
val events = owned(owner, order.workspaceId) {
    val events = mutableListOf<OperatorNotificationEvent.Payment>()
    val latestOriginal = store.order(original.id) ?: throw TossUncertain()
    val effective = latestOriginal.payment?.takeIf { it.remainingAmount < payment.remainingAmount } ?: payment
    val endsAt = (effective.approvedAt ?: clock.instant()).atZone(zone).plusMonths(1).toInstant()
    val savedOriginal = latestOriginal.copy(
        status = "paid", payment = effective, cycleEndsAt = endsAt,
        nextAttemptAt = clock.instant().plus(Duration.ofDays(1)),
    )

    if (order.kind in listOf("charge", "renewal") &&
        latestOriginal.status in UNSETTLED_ORDER_STATUSES && payment.status == "DONE"
    ) {
        grantApprovedPeriod(original, payment, owner)
        events += paymentEvent(owner, savedOriginal, approvedKind(savedOriginal))
    }

    store.saveOrder(savedOriginal)
    store.savePayment(original, effective)
    store.session(order.workspaceId)?.takeIf { it.state == "cancel_requested" }?.let {
        store.saveSession(it.copy(state = "revoking", authKey = null))
    }

    if (order.kind == "refund") {
        val latestOperation = store.order(order.id) ?: throw TossUncertain()
        val savedOperation = latestOperation.copy(status = "paid", payment = payment)
        store.saveOrder(savedOperation)
        if (latestOperation.status in UNSETTLED_ORDER_STATUSES) {
            events += paymentEvent(owner, savedOperation, OperatorPaymentKind.REFUND_APPROVED)
        }
    }
    events
}
notifyAll(events)
```

`latest.status != paid` 단순 비교는 사용하지 않는다. `charge`/`renewal`은 `payment.status == "DONE"`과 paid 저장을 같은 조건에 연결하고, refund는 `latestOperation`의 실제 unsettled → paid 전이를 사용한다.

- [ ] **Step 5: failed와 handleUncertain이 fresh 전이 목록을 반환하게 한다**

`failed`는 외부에서 구한 `owner`를 그대로 쓰고, 다음 형태로 커밋 뒤 발송한다. 기존 세션·결제 기록·재시도 생성 코드는 그대로 두되 이벤트는 fresh `current`에서만 만든다.

```kotlin
val events = owned(owner, order.workspaceId) {
    val current = store.order(order.id) ?: return@owned emptyList()
    if (current.status !in FAILURE_SOURCE_STATUSES) return@owned emptyList()
    val event = failedOrderEvent(owner, current)
    store.saveOrder(current.copy(status = "failed"))
    if (order.kind in listOf("charge", "renewal")) {
        if (requiresCard) {
            store.session(order.workspaceId)?.let { store.saveSession(it.copy(state = "needs_card")) }
        }
        subscriptions.record(
            SubscriptionPayment(
                order.id, order.workspaceId, order.planId, order.amount,
                "failed", order.createdAt, order.simulateFailure, order.environment,
            ),
        )
        if (order.kind == "charge") {
            store.session(order.workspaceId)?.let {
                store.saveSession(it.copy(state = "revoking", authKey = null))
            }
            return@owned listOfNotNull(event)
        }
        val first = order.firstFailureAt ?: clock.instant()
        val delays = RETRY_DELAYS
        if (order.attempt < delays.size) {
            val next = first.plus(delays[order.attempt])
            store.saveOrder(
                order.copy(
                    id = UUID.nameUUIDFromBytes("${order.cycleId}:${order.attempt + 1}".toByteArray()),
                    status = "scheduled", payment = null, attempt = order.attempt + 1,
                    firstFailureAt = first, createdAt = clock.instant(), nextAttemptAt = next,
                ),
            )
        } else {
            store.saveOrder(
                order.copy(
                    status = "suspend_pending", firstFailureAt = first,
                    nextAttemptAt = first.plus(SUSPENSION_DELAY),
                ),
            )
        }
    }
    listOfNotNull(event)
}
notifyAll(events)
```

`handleUncertain`은 첫 `manual_review`만 이벤트를 만든다.

```kotlin
val events = owned(owner, order.workspaceId) {
    val current = store.order(order.id) ?: return@owned emptyList()
    if (current.status in listOf("paid", "failed", "canceled")) return@owned emptyList()
    if (retryWindowElapsed(current.createdAt) && current.status in FAILURE_SOURCE_STATUSES) {
        val saved = current.copy(status = "manual_review")
        store.saveOrder(saved)
        listOf(paymentEvent(owner, saved, OperatorPaymentKind.PAYMENT_MANUAL_REVIEW))
    } else {
        store.release(order.id, clock.instant().plus(timing.retryInterval))
        emptyList()
    }
}
notifyAll(events)
```

- [ ] **Step 6: TossConfiguration을 배선하고 테스트를 통과시킨다**

`TossConfiguration.tossBillingService`에 `operatorNotifications: OperatorNotifications`를 추가하고 생성자 마지막에 전달한다.

```kotlin
TossBillingService(
    store, subscriptions, accounts, credits, transaction, gateway,
    properties.provider in setOf("toss_test", "toss_live"),
    Clock.systemUTC(), usage.zoneId(), properties.tossClientKey, users, billing.timing(),
    environment = properties.provider,
    purchaseEnabled = properties.purchaseEnabled,
    autoChargeEnabled = properties.autoChargeEnabled,
    allowedWorkspaces = properties.allowedWorkspaces,
    operatorNotifications = operatorNotifications,
)
```

Run: `./gradlew :application:test --tests 'kr.easydoc.application.subscription.TossBillingServiceTest'`

Expected: PASS. 최초·정기 승인, 서로 다른 재시도 실패, 환불 작업 주문, 첫 manual_review와 멱등 재처리가 통과한다.

Run: `./gradlew :infrastructure:compileTestKotlin :api:compileTestKotlin`

Expected: PASS. `TossBillingService`의 직접 생성 지점이 모두 갱신된다.

- [ ] **Step 7: Task 6을 커밋한다**

```bash
git add application/src/main/kotlin/kr/easydoc/application/subscription/TossBillingService.kt application/src/test/kotlin/kr/easydoc/application/subscription/TossBillingServiceTest.kt infrastructure/src/main/kotlin/kr/easydoc/infrastructure/subscription/TossConfiguration.kt
git commit -m "feat: notify toss payment transitions"
```

---

### Task 7: Toss 구독 상태 알림과 전체 회귀

**Files:**
- Modify: `application/src/main/kotlin/kr/easydoc/application/subscription/TossBillingService.kt`
- Test: `application/src/test/kotlin/kr/easydoc/application/subscription/TossBillingServiceTest.kt`

**Interfaces:**
- Consumes: Task 6의 `paymentEvent`, `failedOrderEvent(owner, current)`, `notifyAll`, `UNSETTLED_ORDER_STATUSES`; `SubscriptionStore`, `TossBillingStore.latestOrder`.
- Produces: `internal fun subscriptionEventsForTransition(subscription: Subscription, newStatus: String): List<OperatorNotificationEvent.Payment>`.
- Produces: `private fun expire(subscription: Subscription, status: String): List<OperatorNotificationEvent.Payment>`; 알림은 반환만 하고 직접 발송하지 않는다.
- Produces: `stopRenewal`, `cancel`, `waitForCard`, `process`, `runDue`는 각 `owned` 정상 반환 후에만 이벤트를 발송한다. `waitForCard`의 외부 반환형은 기존 `Boolean`을 유지한다.

- [ ] **Step 1: 구독 전이·중복 억제·제외 경로 실패 테스트를 작성한다**

`RecordingStore.latestOrder`를 실제 JDBC 규칙과 같이 refund 제외 최신 주문으로 구현한다.

```kotlin
override fun latestOrder(workspace: UUID): BillingOrder? =
    orders.values
        .filter { it.workspaceId == workspace && it.kind != "refund" }
        .maxWithOrNull(compareBy<BillingOrder> { it.createdAt }.thenBy { it.attempt })
```

`Fixture` 클래스 안의 기존 `private val subscriptions`가 위임하는 `SubscriptionStore` 대역에 `override fun due(now: Instant): List<Subscription> = listOfNotNull(current)`를 추가한다. 외부에서 저장소를 교체하거나 public으로 노출하지 않으며, Task 6의 `serviceWith`도 같은 private 프로퍼티를 사용한다. 다음 테스트를 넣는다.

```kotlin
@Test
fun `canceling과 expired는 실제 전이만 알리고 기준 주문을 유지한다`() {
    val f = Fixture()
    f.current = Subscription(f.workspace, f.owner, "start", 50, 99000, "active", f.now, "toss_test")
    f.store.orders[f.order.id] = f.order.copy(status = "paid")

    f.service.stopRenewal(f.owner, f.workspace)
    f.service.stopRenewal(f.owner, f.workspace)
    f.service.runDue()

    val events = f.notifications.events.filterIsInstance<OperatorNotificationEvent.Payment>()
    assertThat(events.map { it.kind }).containsExactly(
        OperatorPaymentKind.SUBSCRIPTION_CANCELING, OperatorPaymentKind.SUBSCRIPTION_EXPIRED,
    )
    assertThat(events.map { it.orderId }).containsOnly(f.order.id)
    assertThat(events.map { it.planId }).containsOnly("start")
    assertThat(events.map { it.amountKrw }).containsOnly(99000)
}

@Test
fun `cancel 알림은 revoke 전이고 공통 판정은 suspended를 지원한다`() {
    val f = Fixture()
    f.current = Subscription(f.workspace, f.owner, "start", 50, 99000, "active", f.now, "toss_test")
    f.store.orders[f.order.id] = f.order.copy(status = "paid")
    var revokedAtNotify = -1
    f.notifications.onNotify = { revokedAtNotify = f.gateway.revoked.size }

    f.service.cancel(f.owner, f.workspace)
    val suspended = f.service.subscriptionEventsForTransition(f.current!!, "suspended")
    val repeated = f.service.subscriptionEventsForTransition(f.current!!, "canceling")

    assertThat(revokedAtNotify).isZero()
    assertThat(f.notifications.events.map { (it as OperatorNotificationEvent.Payment).kind })
        .containsExactly(OperatorPaymentKind.SUBSCRIPTION_CANCELING)
    assertThat(suspended.map { it.kind }).containsExactly(OperatorPaymentKind.SUBSCRIPTION_SUSPENDED)
    assertThat(repeated).isEmpty()
}

@Test
fun `카드 대기 종료는 renewal 실패와 past_due를 각각 알리고 후속 정리는 실패를 반복하지 않는다`() {
    val f = Fixture()
    f.store.orders[f.order.id] = f.order.copy(
        kind = "renewal", status = "scheduled", firstFailureAt = f.now.minus(Duration.ofDays(15)),
    )
    f.store.currentSession = requireNotNull(f.store.currentSession).copy(state = "needs_card")
    f.current = Subscription(f.workspace, f.owner, "start", 50, 99000, "active", f.now, "toss_test")

    f.service.process(f.order.id)

    assertThat(f.notifications.events.map { (it as OperatorNotificationEvent.Payment).kind })
        .containsExactly(OperatorPaymentKind.SUBSCRIPTION_PAST_DUE, OperatorPaymentKind.RENEWAL_FAILED)

    f.notifications.events.clear()
    f.current = requireNotNull(f.current).copy(status = "active")
    f.store.orders[f.order.id] = requireNotNull(f.store.orders[f.order.id]).copy(status = "suspend_pending")
    f.store.currentSession = requireNotNull(f.store.currentSession).copy(state = "active")
    f.service.process(f.order.id)
    assertThat(f.notifications.events.map { (it as OperatorNotificationEvent.Payment).kind })
        .containsOnly(OperatorPaymentKind.SUBSCRIPTION_PAST_DUE)
}

@Test
fun `최신 승인 주문이 없으면 구독 알림만 생략하고 비대상 작업은 알리지 않는다`() {
    val f = Fixture()
    f.store.orders.clear()
    f.current = Subscription(f.workspace, f.owner, "start", 50, 99000, "active", f.now, "toss_test")

    val logs = captureTossLogs {
        assertThat(f.service.subscriptionEventsForTransition(f.current!!, "expired")).isEmpty()
    }
    f.service.begin(f.owner, f.workspace, "start", "replace_card")
    f.service.billingDeleted(Secret("card"))
    f.service.retryCardDeletion(f.workspace)

    assertThat(logs).hasSize(1)
    val log = logs.single()
    assertThat(log.level).isEqualTo(Level.WARN)
    assertThat(log.formattedMessage).contains("subscription_expired", f.owner.toString())
    assertThat(log.formattedMessage).doesNotContain("billing@example.test", "\"text\"", "sendMessage")
    assertThat(log.throwableProxy).isNull()
    assertThat(f.current?.status).isEqualTo("active")
    assertThat(f.notifications.events).isEmpty()
}

private fun captureTossLogs(block: () -> Unit): List<ILoggingEvent> {
    val logger = LoggerFactory.getLogger(TossBillingService::class.java) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    logger.addAppender(appender)
    try {
        block()
        return appender.list.toList()
    } finally {
        logger.detachAppender(appender)
        appender.stop()
    }
}
```

위 helper에는 Task 1과 같은 Logback `Logger`, `Level`, `ListAppender`, `ILoggingEvent` import를 사용한다. 기존 `approved time starts full month even after delayed result and duplicate processing`, `past due card replacement waits for actual renewal approval before reactivation` 테스트에도 동일 상태 재저장 시 알림 수가 늘지 않는다는 단언을 추가한다. scheduled 생성, 인증 복구, 카드 등록·교체·폐기만 일어난 기존 테스트에는 `notifications.events.isEmpty()`를 단언한다. `complete`/`recoverAuthorizations`가 `process`/`finish`를 재사용하는 경로에는 새 훅을 두지 않는다.

- [ ] **Step 2: 구독 테스트 실패를 확인한다**

Run: `./gradlew :application:test --tests 'kr.easydoc.application.subscription.TossBillingServiceTest'`

Expected: FAIL. `subscriptionEventsForTransition` 부재와 canceling/expired/past_due 이벤트 부재로 실패한다.

- [ ] **Step 3: 공통 구독 전이 판정과 latestOrder 누락 경로를 구현한다**

`TossBillingService`에 `LoggerFactory` 로거를 추가하고 다음 함수를 둔다. `latestOrder`는 새 저장소 메서드나 SQL이 아니라 기존 refund 제외 구현을 그대로 사용한다.

```kotlin
internal fun subscriptionEventsForTransition(
    subscription: Subscription,
    newStatus: String,
): List<OperatorNotificationEvent.Payment> {
    if (subscription.status == newStatus) return emptyList()
    val kind = when (newStatus) {
        "canceling" -> OperatorPaymentKind.SUBSCRIPTION_CANCELING
        "expired" -> OperatorPaymentKind.SUBSCRIPTION_EXPIRED
        "past_due" -> OperatorPaymentKind.SUBSCRIPTION_PAST_DUE
        "suspended" -> OperatorPaymentKind.SUBSCRIPTION_SUSPENDED
        else -> return emptyList()
    }
    val latest = store.latestOrder(subscription.workspaceId)
    if (latest == null) {
        log.warn(
            "구독 운영 알림의 기준 주문이 없어 알림을 생략한다: eventType={} userId={}",
            kind.wireValue, subscription.ownerId,
        )
        return emptyList()
    }
    val event = paymentEvent(subscription.ownerId, latest, kind).copy(
        planId = subscription.planId,
        amountKrw = subscription.monthlyPrice,
        environment = subscription.environment,
    )
    return listOf(event)
}

private fun expire(subscription: Subscription, status: String): List<OperatorNotificationEvent.Payment> {
    val events = subscriptionEventsForTransition(subscription, status)
    subscriptions.save(subscription.copy(status = status))
    credits.setAllowance(
        subscription.workspaceId, subscription.ownerId, 0, subscription.cycleEndsAt,
        false, CreditReason.CYCLE_END, "Toss subscription ended",
    )
    return events
}
```

`active`/`canceling` 복귀와 크레딧 변경만으로는 이벤트가 없다. latestOrder가 없어도 저장과 크레딧 정리는 그대로 진행한다.

- [ ] **Step 4: stopRenewal과 cancel을 owned 밖 발송으로 바꾼다**

`stopRenewal`의 구독 저장 전에 전이 이벤트를 만들고 세션·scheduled 기존 동작은 유지한다.

```kotlin
val events = owned(owner, workspace) {
    val events = subscriptions.find(workspace)?.let { current ->
        val events = subscriptionEventsForTransition(current, "canceling")
        subscriptions.save(current.copy(status = "canceling"))
        events
    } ?: emptyList()
    store.stopScheduled(workspace)
    store.session(workspace)?.let {
        if (it.state == "issuing") conflict("카드 등록 결과를 먼저 확인하세요")
        store.saveSession(it.copy(state = if (store.replacementBlocked(workspace)) "cancel_requested" else "revoking"))
    }
    events
}
notifyAll(events)
```

`cancel`은 active → canceling만 이벤트로 하고 `revoke` 직전에 발송한다.

```kotlin
val events = owned(owner, workspace) {
    if (store.replacementBlocked(workspace)) conflict("처리 중인 결제 결과를 확인한 후 해지하세요")
    store.stopScheduled(workspace)
    val session = store.session(workspace)
    if (session?.state == "issuing") conflict("처리 중인 카드 등록 결과를 확인한 후 해지하세요")
    val events = subscriptions.find(workspace)?.let { current ->
        if (current.status != "active") return@let emptyList()
        val events = subscriptionEventsForTransition(current, "canceling")
        subscriptions.save(current.copy(status = "canceling"))
        events
    } ?: emptyList()
    store.session(workspace)?.let { store.saveSession(it.copy(state = "revoking", authKey = null)) }
    events
}
notifyAll(events)
revoke(workspace)
```

- [ ] **Step 5: waitForCard와 suspend_pending 정리를 이벤트 목록으로 바꾼다**

`waitForCard`는 `Boolean`을 그대로 반환하고, fresh 주문과 구독 이벤트를 한 커밋 결과로 합친다.

```kotlin
val events = owned(owner, existing.workspaceId) {
    val current = store.order(existing.id) ?: return@owned emptyList()
    if (store.session(existing.workspaceId)?.state != "needs_card" || current.status != "scheduled") {
        return@owned emptyList()
    }
    val first = current.firstFailureAt ?: current.createdAt
    val suspendAt = first.plus(SUSPENSION_DELAY)
    if (clock.instant() >= suspendAt) {
        val events = subscriptions.find(existing.workspaceId)?.let { expire(it, "past_due") }.orEmpty().toMutableList()
        failedOrderEvent(owner, current)?.let(events::add)
        store.saveOrder(current.copy(status = "failed"))
        events
    } else {
        store.release(current.id, suspendAt)
        emptyList()
    }
}
notifyAll(events)
return true
```

`process`의 `suspend_pending` 분기는 fresh 주문을 읽고 실패 이벤트를 만들지 않는다.

```kotlin
if (order.status == "suspend_pending") {
    val events = owned(owner, order.workspaceId) {
        val current = store.order(order.id) ?: return@owned emptyList()
        if (current.status != "suspend_pending") return@owned emptyList()
        val events = subscriptions.find(order.workspaceId)?.let { expire(it, "past_due") }.orEmpty()
        store.saveOrder(current.copy(status = "failed"))
        events
    }
    notifyAll(events)
    return
}
```

`complete`, `recheck`, `recoverAuthorizations`에는 추가 알림 훅을 넣지 않고 기존 `process`/`finish` 호출을 유지한다.

- [ ] **Step 6: runDue의 만료 이벤트를 owned 밖에서 발송한다**

`subscriptions.due` 반복 블록만 다음 형태로 바꾼다. scheduled 생성은 빈 목록을 반환한다.

```kotlin
subscriptions.due(clock.instant()).filter { it.provider == environment }.forEach { candidate ->
    val events = owned(candidate.ownerId, candidate.workspaceId) {
        val current = subscriptions.find(candidate.workspaceId) ?: return@owned emptyList()
        if (current.cycleEndsAt > clock.instant() || store.pending(current.workspaceId)) {
            return@owned emptyList()
        }
        if (current.status == "canceling") return@owned expire(current, "expired")
        if (current.status != "active" || !enabled || !autoChargeEnabled) return@owned emptyList()
        val id = UUID.nameUUIDFromBytes("toss:${current.workspaceId}:${current.cycleEndsAt}".toByteArray())
        if (store.order(id) == null) {
            val now = clock.instant()
            store.saveOrder(
                BillingOrder(
                    id, current.workspaceId, current.planId, current.monthlyPrice,
                    now, now.atZone(zone).plusMonths(1).toInstant(),
                    environment = environment, kind = "renewal",
                ),
            )
        }
        emptyList()
    }
    notifyAll(events)
}
```

`store.candidates(clock.instant()).forEach(::process)`와 cleanup/revoke 호출은 그대로 둔다. 7일 예고나 새 scheduler는 추가하지 않는다.

- [ ] **Step 7: 구독 단위 테스트와 생성자 영향 검증을 통과시킨다**

Run: `./gradlew :application:test --tests 'kr.easydoc.application.subscription.TossBillingServiceTest'`

Expected: PASS. canceling, expired, past_due, 공통 suspended, waitForCard의 두 대상 이벤트, suspend_pending 중복 실패 억제, latestOrder 누락 및 비대상 경로가 통과한다.

Run: `./gradlew :infrastructure:compileTestKotlin :api:compileTestKotlin`

Expected: PASS. infrastructure·API 테스트의 모든 생성자와 fixture가 최종 시그니처와 일치한다.

- [ ] **Step 8: Task 7 구현을 커밋한다**

```bash
git add application/src/main/kotlin/kr/easydoc/application/subscription/TossBillingService.kt application/src/test/kotlin/kr/easydoc/application/subscription/TossBillingServiceTest.kt
git commit -m "feat: notify toss subscription transitions"
```

- [ ] **Step 9: 지정된 회원·어댑터·설정 회귀를 다시 통과시킨다**

Run:

```bash
./gradlew :application:test --tests 'kr.easydoc.application.notification.OperatorNotificationServiceTest'
./gradlew :application:test --tests 'kr.easydoc.application.auth.AuthServiceTest'
./gradlew :application:test --tests 'kr.easydoc.application.auth.SocialLoginServiceTest'
./gradlew :application:test --tests 'kr.easydoc.application.account.DeleteAccountServiceTest'
./gradlew :application:test --tests 'kr.easydoc.application.subscription.TossBillingServiceTest'
./gradlew :infrastructure:test --tests 'kr.easydoc.infrastructure.telegram.TelegramOperatorNotificationAdapterTest'
./gradlew :infrastructure:test --tests 'kr.easydoc.infrastructure.auth.JdbcAccountDeletionRepositoryTest'
./gradlew :api:test --tests 'kr.easydoc.api.ConfigurationPropertiesBindingTest'
```

Expected: 모든 명령 PASS. 최종 Toss 변경 뒤 이전 Task의 녹색 결과를 재사용하지 않고 현재 코드에서 다시 검증한다.

- [ ] **Step 10: 전체 빌드와 Compose 해석을 통과시킨다**

Run: `./gradlew build`

Expected: PASS. 전 모듈 compile, 단위·JDBC·API 테스트, ktlint, detekt 및 dependency locking이 통과한다.

Run: `docker compose config`

Expected: PASS. 환경 예시 추가 후 기존 `env_file` 기반 API·worker 구성이 유효하다.

- [ ] **Step 11: 최종 독립 리뷰 기준을 확인한다**

다음을 현재 최종 diff에 대해 모두 확인한다. 어느 하나라도 실패하면 완료로 판정하지 않고 해당 Task의 실패 로그와 함께 수정 단계로 돌아간다.

- 알림 호출이 `inTransaction`/`owned` 안에 없고, 롤백·가드 조기 반환·같은 상태 재저장에서 이벤트가 없다.
- `finish`의 charge/renewal 승인은 unsettled와 `payment.status == "DONE"`을 함께 쓰고, refund는 작업 주문 ID와 fresh 상태를 쓴다.
- 서로 다른 재시도 주문 실패는 각각 발생하고 `suspend_pending` 후속 정리에는 실패 중복이 없다.
- `cancel`은 `revoke` 전, `runDue` 만료는 각 반복의 `owned` 밖, `waitForCard`는 `Boolean` 반환 전에 커밋 결과를 발송한다.
- `complete`/`recheck`/`recoverAuthorizations`에 중복 훅이 없고 scheduled 생성·release·카드·로그인·소셜 연결·해제·이메일 인증·비밀번호 변경은 알리지 않는다.
- 메시지는 plain text이며 이메일 마스킹, 전체 UUID, `Asia/Seoul`, `[TEST]`는 `toss_test`만, 결제 plan/정수 KRW/order/workspace/environment가 정확하다.
- disabled는 조회·렌더링·발송·로그를 모두 생략하고, 모든 실패 WARN은 정제된 한 줄이며 예외 객체·민감 원문이 없다.
- token/chat ID가 properties `toString`에 없고 base URL이 운영 속성으로 추가되지 않았으며 HTTP 재시도와 `parse_mode`가 없다.
- `compose.yml`, `contracts/`, `frontend/`, DB migration, worker scheduler, `billing_notifications`, 회원용 메일 변경이 없다.

Run:

```bash
git diff --check
git status --short
```

Expected: whitespace 오류가 없고, status에는 계획된 구현 파일만 보이며 미커밋 변경이 없다. 최종 커밋 이후 검증만 수행한 상태에서는 `git status --short` 출력이 비어 있다.
