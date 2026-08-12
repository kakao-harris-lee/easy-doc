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

/**
 * **서블릿에 닿지 못한 응답의 본문**을 계약 형태로 만든다.
 *
 * ## 왜 [ContractErrorController] 만으로 부족한가 (2026-08-12 실측)
 *
 * `/error` 컨트롤러는 컨테이너가 오류 페이지로 **디스패치할 수 있을 때만** 돈다. 요청 줄이나
 * 헤더 블록이 깨진 요청은 `Http11InputBuffer` 파싱 단계에서 거절돼 서블릿에 매핑되지
 * 않으므로 그 디스패치가 아예 없다. 그 자리를 채우는 것이 Tomcat 의
 * [ErrorReportValve] 이고, **그것이 만드는 본문은 HTML 이다.** 원시 소켓 측정:
 *
 * | 요청 | 상태 | 고치기 전 Content-Type / 본문 |
 * |---|---|---|
 * | 요청 대상에 금지 문자 | 400 | `text/html;charset=utf-8` / 435바이트 HTML |
 * | 요청 줄 자체가 쓰레기 | 400 | 〃 |
 * | 헤더 상한 초과 | 400 | 〃 |
 * | `Host` 없는 HTTP/1.1 | 400 | 〃 |
 * | 알 수 없는 HTTP 버전 | 505 | `text/html;charset=utf-8` / 465바이트 HTML |
 *
 * **헤더 조치로는 이 자리가 닫히지 않았다.** `PrivateResponseHeadersValve`(Engine 파이프라인)
 * 는 이 응답들에 닿았지만, 그 밸브의 `next.invoke()` 가 돌아올 때는 [ErrorReportValve] 가
 * 이미 본문을 써 버린 뒤다 — Host 파이프라인이 Engine 파이프라인보다 **안쪽**이기 때문이다.
 * 본문은 헤더보다 한 층 더 깊은 곳에서 결정된다.
 *
 * `server.error.include-stacktrace=never` 라 Spring Boot 가 `showReport=false`,
 * `showServerInfo=false` 로 설정한 [ErrorReportValve] 를 넣어 두는데, 실측 결과 **그 두
 * 플래그는 HTML 골격 자체를 없애지 못한다.** 없앨 수 있는 것은 예외 설명·서버 정보 부분뿐이다.
 *
 * ## 그래서 리포터를 갈아 끼운다
 *
 * [ErrorReportValve.report] 만 재정의한다. 도달 판정(커밋 여부, 비동기 여부, 오류 페이지
 * 탐색)은 Tomcat 의 `invoke` 를 그대로 쓰고 **본문 생성만** 바꾼다 — 그 판정을 우리가 다시
 * 쓰면 Tomcat 이 버전마다 고쳐 온 경계 조건을 잃는다.
 *
 * ## 메시지를 싣지 않는다
 *
 * Tomcat 의 원래 구현은 `response.getMessage()`(= `sendError` 두 번째 인자)와 예외 정보를
 * 본문에 싣는다. 여기서는 **상태 코드에서 유도한 표준 사유 문구만** 쓴다.
 * [ContractErrorController] 와 같은 규칙이고, 이유도 같다 — 무엇이 담길지 알 수 없는
 * 문자열을 응답 본문에 넣으면 그것이 개인정보 유출 경로가 된다.
 */
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
     *
     * 단락 평가 순서가 의미를 갖는다. `setErrorReported()` 는 "내가 보고했다"고 표시하는
     * **부수 효과가 있는 호출**이라, 앞의 조건이 실패했는데도 불리면 다른 층이 본문을 쓸
     * 기회를 빼앗는다.
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

/**
 * [ContractErrorReportValve] 를 Tomcat Host 파이프라인의 **유일한** 오류 리포터로 만든다.
 *
 * ## 왜 Host 파이프라인인가
 *
 * 오류 본문을 만드는 층이 여기다. Engine 파이프라인(사적 응답 헤더 밸브가 사는 곳)은 한
 * 층 바깥이라, 거기서 `next.invoke()` 가 돌아왔을 때는 본문이 이미 쓰인 뒤다.
 *
 * ## 왜 `Lifecycle.START_EVENT` 인가 — 순서 의존을 없애려고
 *
 * 기본 [ErrorReportValve] 를 파이프라인에 넣는 주체가 둘이다.
 *
 * 1. Spring Boot `TomcatWebServerFactoryCustomizer` — `server.error.include-stacktrace=never`
 *    일 때 `showReport=false` 인 것을 컨텍스트 커스터마이저로 넣는다.
 * 2. Tomcat `StandardHost.startInternal()` — `errorReportValveClass` 와 클래스 이름이
 *    같은 밸브가 없으면 기본 밸브를 넣는다.
 *
 * 컨텍스트 커스터마이저 단계에서 치우면 **1번과의 실행 순서에 결과가 걸린다.** 우리가 먼저
 * 돌면 Boot 이 나중에 자기 밸브를 다시 넣고, 그 밸브가 우리 것보다 안쪽이라 HTML 을 **먼저**
 * 쓴다. 그 상태의 증상은 "malformed 요청에만 본문이 HTML" 하나뿐이라 눈으로 잡히지 않는다 —
 * 이번 게이트에서 반복해 잡힌 *선언은 전역인데 실제 도달은 일부뿐* 과 같은 모양이다.
 *
 * `START_EVENT` 는 위 둘이 **모두 끝난 뒤** 발생한다(`StandardHost.startInternal()` 이
 * 밸브를 넣고 나서 `super.startInternal()` 을 부르고, 거기서 상태 전이가 이벤트를 쏜다).
 * 그 시점에 청소하면 누가 먼저 돌았든 결과가 같다.
 *
 * ## 컨테이너 결합을 감춘 채 사라지지 않게 한다
 *
 * Host 가 [StandardHost] 가 아니면 기동을 멈춘다. 계획 §3.1이 Spring MVC + 내장 Tomcat 을
 * 고정했으므로 감수하는 결합이고, 컨테이너를 바꾸면 이 배선이 조용히 무효가 되는 대신
 * 기동 시점에 깨져야 한다.
 */
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
