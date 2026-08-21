package kr.easydoc.api.error

import org.apache.catalina.Context
import org.apache.catalina.Lifecycle
import org.apache.catalina.LifecycleListener
import org.apache.catalina.Pipeline
import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.core.StandardHost
import org.apache.catalina.valves.ErrorReportValve
import org.apache.coyote.ActionCode
import org.slf4j.LoggerFactory
import org.springframework.boot.tomcat.TomcatContextCustomizer
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** **서블릿에 닿지 못한 응답의 본문**을 계약 형태로 만든다. */
class ContractErrorReportValve : ErrorReportValve() {
    override fun report(
        request: Request,
        response: Response,
        throwable: Throwable?,
    ) {
        if (shouldWriteContractBody(response)) {
            writeContractBody(response)
        }
    }

    /**
     * Tomcat 기본 구현과 **같은 판정**이다 — 오류 상태이고, 아무것도 쓰이지 않았고, 아직
     * 아무도 보고하지 않았고, I/O 가 가능할 때만 쓴다.
     */
    private fun shouldWriteContractBody(response: Response): Boolean =
        response.status >= FIRST_ERROR_STATUS &&
            response.contentWritten == 0L &&
            response.setErrorReported() &&
            isIoAllowed(response)

    private fun isIoAllowed(response: Response): Boolean {
        val allowed = AtomicBoolean(false)
        response.coyoteResponse.action(ActionCode.IS_IO_ALLOWED, allowed)
        return allowed.get()
    }

    private fun writeContractBody(response: Response) {
        try {
            response.setContentType(CONTRACT_ERROR_CONTENT_TYPE)
            // getReporter() 는 응답이 중단된 상태에서 null 을 준다. 그때는 쓸 곳이 없다.
            response.reporter?.let { writer ->
                writer.write(contractErrorJson(response.status))
                response.finishResponse()
            }
        } catch (exception: IOException) {
            // 오류 응답을 쓰다 실패했다. 여기서 더 할 수 있는 일이 없고, 다시 던지면
            // 컨테이너가 같은 실패를 반복한다. 예외 메시지에 무엇이 담길지 알 수 없으므로
            // 타입 이름만 남긴다(프로젝트 CLAUDE.md 보안 규칙).
            LoggerFactory
                .getLogger(ContractErrorReportValve::class.java)
                .debug("계약 오류 본문을 쓰지 못했다: {}", exception::class.java.simpleName)
        }
    }

    private companion object {
        /** 4xx 미만은 오류가 아니다. Tomcat 기본 구현과 같은 경계다. */
        const val FIRST_ERROR_STATUS = 400
    }
}

/** [ContractErrorReportValve] 를 Tomcat Host 파이프라인의 **유일한** 오류 리포터로 만든다. */
@Configuration(proxyBeanMethods = false)
class ContractErrorBodyConfig {
    @Bean
    fun contractErrorReportValveCustomizer(): WebServerFactoryCustomizer<TomcatServletWebServerFactory> =
        WebServerFactoryCustomizer { factory ->
            factory.addContextCustomizers(
                TomcatContextCustomizer { context -> registerContractErrorReporter(context) },
            )
        }
}

private fun registerContractErrorReporter(context: Context) {
    val host =
        context.parent as? StandardHost
            ?: error(
                "Tomcat StandardHost 를 찾지 못했다 — 서블릿 컨테이너가 바뀌면 오류 본문 계약이 " +
                    "조용히 무효가 되므로 기동을 멈춘다.",
            )
    host.addLifecycleListener(
        LifecycleListener { event ->
            if (event.type == Lifecycle.START_EVENT) {
                replaceErrorReporters(host.pipeline)
            }
        },
    )
}

/** 기본 HTML 리포터를 모두 걷어내고 계약 리포터 하나만 남긴다. */
private fun replaceErrorReporters(pipeline: Pipeline) {
    pipeline.valves
        .filterIsInstance<ErrorReportValve>()
        .filterNot { it is ContractErrorReportValve }
        .forEach(pipeline::removeValve)
    if (pipeline.valves.none { it is ContractErrorReportValve }) {
        pipeline.addValve(ContractErrorReportValve())
    }
}
