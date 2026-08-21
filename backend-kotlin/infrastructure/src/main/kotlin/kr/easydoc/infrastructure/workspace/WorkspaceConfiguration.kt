package kr.easydoc.infrastructure.workspace

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.auth.WorkspaceRepository
import kr.easydoc.application.workspace.WorkspaceService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 작업 공간 유스케이스 조립. */
@Configuration(proxyBeanMethods = false)
class WorkspaceConfiguration {
    @Bean
    fun workspaceService(
        workspaces: WorkspaceRepository,
        transactionRunner: TransactionRunner,
    ): WorkspaceService = WorkspaceService(workspaces = workspaces, transaction = transactionRunner)
}
