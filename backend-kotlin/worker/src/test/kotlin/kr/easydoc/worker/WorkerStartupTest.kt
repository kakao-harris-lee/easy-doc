package kr.easydoc.worker

import kr.easydoc.application.accesslog.PurgePersonalDataAccessLogs
import kr.easydoc.application.conversion.ConversionCompletedNotifier
import kr.easydoc.application.conversion.DictionaryContextSource
import kr.easydoc.application.conversion.ProcessConversionJob
import kr.easydoc.application.document.PurgeExpiredDocuments
import kr.easydoc.application.document.PurgeFeedbackComments
import kr.easydoc.application.mail.MailSender
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import kr.easydoc.infrastructure.auth.PhoneVerificationProperties
import kr.easydoc.infrastructure.sms.SmsProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import javax.sql.DataSource

/** worker 진입점이 기동되는지 확인한다. */
@SpringBootTest
@ActiveProfiles("worker")
class WorkerStartupTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    @DisplayName("worker 가 기동하고 DataSource 를 갖는다")
    fun `worker 가 기동한다`() {
        assertThat(context.environment.activeProfiles).isNotNull()
        assertThat(dataSource).isNotNull()
        assertThat(context.getBean(ProcessConversionJob::class.java)).isNotNull()
        assertThat(context.getBean(PurgeExpiredDocuments::class.java)).isNotNull()
        assertThat(context.getBean(PurgeFeedbackComments::class.java)).isNotNull()
        assertThat(context.getBean(RetentionPurgeScheduler::class.java)).isNotNull()
        assertThat(context.getBean(PurgePersonalDataAccessLogs::class.java)).isNotNull()
    }

    @Test
    @DisplayName("worker 는 변환 완료 알림 유스케이스와 메일 발송기를 갖는다")
    fun `완료 알림이 조립된다`() {
        assertThat(context.getBean(ConversionCompletedNotifier::class.java)).isNotNull()
        assertThat(context.getBean(MailSender::class.java)).isNotNull()
    }

    @Test
    @DisplayName("worker 도 운영 SENS 환경변수와 휴대폰 인증 설정을 바인딩한다")
    fun `휴대폰 인증 운영 설정이 바인딩된다`() {
        val sms = context.getBean(SmsProperties::class.java)
        val verification = context.getBean(PhoneVerificationProperties::class.java)

        assertThat(sms.provider).isEqualTo("sens")
        assertThat(sms.serviceId).isEqualTo("ncp-sms-service-id")
        assertThat(sms.accessKey).isEqualTo("ncp-access-key")
        assertThat(sms.secretKey.isBlank()).isFalse()
        assertThat(verification.fingerprintPepper.isBlank()).isFalse()
    }

    @Test
    @DisplayName("worker 는 사전 컨텍스트 공급원을 갖는다 — 색인 적재까지 실제로 도는 자리다")
    fun `사전 공급원이 조립된다`() {
        assertThat(context.getBean(DictionaryContextSource::class.java)).isNotNull()
    }

    @Test
    @DisplayName("worker 는 웹 서버를 띄우지 않는다")
    fun `웹 컨텍스트가 아니다`() {
        assertThat(context).isNotInstanceOf(
            org.springframework.web.context.WebApplicationContext::class.java,
        )
    }

    @Test
    @DisplayName("worker 는 스키마를 적용하지 않는다")
    fun `Flyway 를 돌리지 않는다`() {
        assertThat(database.queryInt(TABLE_COUNT_SQL)).isZero()
    }

    companion object {
        private const val TABLE_COUNT_SQL =
            "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                "WHERE n.nspname = 'public' AND c.relkind = 'r'"

        private val database: DatabaseHandle by lazy {
            PostgresTestSupport.createEmptyDatabase("worker_startup")
        }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
            registry.add("spring.task.scheduling.enabled") { "false" }
            registry.add("EASYDOC_SMS_PROVIDER") { "sens" }
            registry.add("EASYDOC_SENS_SERVICE_ID") { "ncp-sms-service-id" }
            registry.add("EASYDOC_SENS_ACCESS_KEY") { "ncp-access-key" }
            registry.add("EASYDOC_SENS_SECRET_KEY") { "ncp-secret-key" }
            registry.add("EASYDOC_SMS_FROM") { "01012345678" }
            registry.add("EASYDOC_PHONE_VERIFICATION_PEPPER") { "worker-test-phone-pepper" }
        }
    }
}
