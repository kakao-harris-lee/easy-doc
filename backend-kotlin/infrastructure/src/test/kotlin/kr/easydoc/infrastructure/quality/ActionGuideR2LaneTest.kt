package kr.easydoc.infrastructure.quality

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ActionGuideR2LaneTest {
    @Test
    @DisplayName("R2 레인은 별도 토글이 없으면 비활성이다")
    fun `토글 없이는 비활성`() {
        assertThat(ActionGuideR2Lane.plan { null }).isEqualTo(ActionGuideR2LanePlan.Disabled)
    }

    @Test
    @DisplayName("기본 실행은 개발 6건과 action-guide production bound를 사용한다")
    fun `기본 개발 표본과 production bound`() {
        val plan = ready(baseEnvironment())

        assertThat(plan.cohort).isEqualTo(ActionGuideR2Cohort.DEVELOPMENT)
        assertThat(plan.documentIds).containsExactlyElementsOf(ActionGuideR2Lane.DEVELOPMENT_IDS)
        assertThat(plan.runs).isEqualTo(1)
        assertThat(plan.maxCalls).isEqualTo(6)
        assertThat(plan.options.maxTokens).isEqualTo(ActionGuideR2Lane.ACTION_GUIDE_MAX_OUTPUT_TOKENS)
        assertThat(plan.description)
            .contains("max_tokens=8192")
            .contains("read_timeout_s=90")
    }

    @Test
    @DisplayName("보류 표본은 cohort를 명시해야 선택된다")
    fun `holdout을 명시하면 보류 4건만 선택`() {
        val plan =
            ready(
                baseEnvironment() +
                    (ActionGuideR2Lane.COHORT_ENV to "holdout"),
            )

        assertThat(plan.cohort).isEqualTo(ActionGuideR2Cohort.HOLDOUT)
        assertThat(plan.documentIds).containsExactlyElementsOf(ActionGuideR2Lane.HOLDOUT_IDS)
        assertThat(plan.maxCalls).isEqualTo(4)
    }

    @Test
    @DisplayName("계획 호출 수보다 작은 상한은 유료 실행 전에 거절한다")
    fun `호출 상한이 계획보다 작으면 거절`() {
        val plan =
            ActionGuideR2Lane.plan(
                (baseEnvironment() + (ActionGuideR2Lane.MAX_CALLS_ENV to "5"))::get,
            )

        assertThat(plan).isInstanceOf(ActionGuideR2LanePlan.Unusable::class.java)
        assertThat((plan as ActionGuideR2LanePlan.Unusable).reason).contains("계획 호출 수 6")
    }

    @Test
    @DisplayName("단가와 달러 상한을 명시하지 않으면 Ready가 되지 않는다")
    fun `가격 상한 없는 실행은 거절`() {
        val env = baseEnvironment().toMutableMap()
        env.remove(ActionGuideR2Lane.MAX_USD_ENV)

        val plan = ActionGuideR2Lane.plan(env::get)

        assertThat(plan).isInstanceOf(ActionGuideR2LanePlan.Unusable::class.java)
        assertThat((plan as ActionGuideR2LanePlan.Unusable).reason)
            .contains(ActionGuideR2Lane.MAX_USD_ENV)
    }

    @Test
    @DisplayName("보류 표본 id를 개발 표본으로 위장할 수 없다")
    fun `cohort 밖 문서는 거절`() {
        val plan =
            ActionGuideR2Lane.plan(
                (baseEnvironment() + (ActionGuideR2Lane.DOCUMENTS_ENV to "089"))::get,
            )

        assertThat(plan).isInstanceOf(ActionGuideR2LanePlan.Unusable::class.java)
    }

    private fun ready(env: Map<String, String>): ActionGuideR2LanePlan.Ready {
        val plan = ActionGuideR2Lane.plan(env::get)
        assertThat(plan).isInstanceOf(ActionGuideR2LanePlan.Ready::class.java)
        return plan as ActionGuideR2LanePlan.Ready
    }

    private fun baseEnvironment(): Map<String, String> =
        mapOf(
            ActionGuideR2Lane.ENABLED_ENV to "true",
            GoldenLlmLane.PROVIDER_ENV to "anthropic",
            GoldenLlmLane.ANTHROPIC_KEY_ENV to "test-key-do-not-leak",
            GoldenLlmLane.INPUT_PRICE_ENV to "10",
            GoldenLlmLane.OUTPUT_PRICE_ENV to "50",
            ActionGuideR2Lane.MAX_USD_ENV to "20",
        )
}
