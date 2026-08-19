package kr.easydoc.infrastructure.workspace

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.auth.WorkspaceRepository
import kr.easydoc.application.workspace.WorkspaceService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 작업 공간 유스케이스 조립.
 *
 * `WorkspaceService` 는 `application` 의 평범한 클래스다 — Spring 애너테이션이 하나도 없다.
 * 유스케이스가 프레임워크를 모르게 두는 대신 조립을 `infrastructure` 에서 한다
 * (`AuthConfiguration` 과 같은 이유: `api` 는 `runtimeOnly(project(":infrastructure"))` 라
 * 구현 타입을 컴파일 시점에 보지 못한다).
 *
 * **저장소 빈은 여기 없다.** `WorkspaceRepository` 는 가입도 쓰므로 `AuthConfiguration` 이
 * 이미 들고 있다. 이리로 옮기면 리뷰를 마친 auth 조립을 흔들면서 얻는 것이 「파일 이름이
 * 더 맞아 보인다」뿐이라 옮기지 않았다 — 빈은 타입으로 주입되므로 어느 설정 클래스가
 * 선언하든 조립 결과가 같다.
 */
@Configuration(proxyBeanMethods = false)
class WorkspaceConfiguration {
    @Bean
    fun workspaceService(
        workspaces: WorkspaceRepository,
        transactionRunner: TransactionRunner,
    ): WorkspaceService = WorkspaceService(workspaces = workspaces, transaction = transactionRunner)
}
