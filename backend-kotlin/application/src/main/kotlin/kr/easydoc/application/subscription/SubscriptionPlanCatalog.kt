package kr.easydoc.application.subscription

import kr.easydoc.core.exceptions.InvalidInputException

/** The single source of truth for plans that can be charged by the subscription flows. */
object SubscriptionPlanCatalog {
    private val plans =
        listOf(
            SubscriptionPlan(
                id = "start",
                name = "Start",
                allowance = 50,
                monthlyPrice = 99_000,
            ),
        )

    fun all(): List<SubscriptionPlan> = plans

    fun require(id: String): SubscriptionPlan =
        plans.find { it.id == id } ?: throw InvalidInputException("알 수 없는 구독 플랜입니다")
}
