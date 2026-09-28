package kr.easydoc.application.subscription

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SubscriptionPlanCatalogTest {
    @Test
    fun `all and require share the fixed Start plan`() {
        val expected = SubscriptionPlan("start", "Start", 50, 99_000)

        assertThat(SubscriptionPlanCatalog.all()).containsExactly(expected)
        assertThat(SubscriptionPlanCatalog.require("start")).isEqualTo(expected)
    }

    @Test
    fun `require rejects plans outside the catalog`() {
        assertThatThrownBy { SubscriptionPlanCatalog.require("pro") }
            .isInstanceOf(kr.easydoc.core.exceptions.InvalidInputException::class.java)
    }
}
