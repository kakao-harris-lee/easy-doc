# Production Start Billing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Open the existing Start monthly billing flow to every eligible logged-in workspace owner in Production with fail-closed payment configuration, separated Compose profiles, accurate UI, and effective legal snapshots.

**Architecture:** Keep the existing Toss gateway, transaction, ownership, recovery, refund, HTTP DTO, and `start-monthly-v1` consent boundaries. Add environment validation at the existing `SubscriptionConfiguration.paymentGateway` bean boundary, centralize purchase eligibility in `TossBillingService.purchaseAllowed`, and render only from existing `SubscriptionOverview` fields. Change the Production Compose overlay and legal resources without adding APIs, migrations, payment architecture, or dynamic date-based activation.

**Tech Stack:** Kotlin, Spring Boot, Gradle, JUnit 5, AssertJ, `MockEnvironment`; React, TypeScript, Vitest, Testing Library; Docker Compose v2; Markdown legal/runbook content.

## Global Constraints

- Sell only existing `SubscriptionPlanCatalog` plan `start`: KRW 99,000/month and 50 credits/month; do not sell or promise Basic/Pro.
- Preserve `SubscriptionPlanCatalog.all()`, `SubscriptionPlanCatalog.require(id)`, existing ownership and payment-eligibility checks, billing/callback/webhook/refund/recovery behavior, and `start-monthly-v1`.
- Production purchase eligibility is `purchaseEnabled`; `allowedWorkspaces` remains only for non-live review flows and must never widen or narrow `toss_live` eligibility.
- Production release configuration is `purchaseEnabled=true` and `autoChargeEnabled=true`; either existing flag may independently be set false for incident response.
- `local`/`test` reject `toss_live`; `production` requires `toss_live`, `live_ck_`/`live_sk_`, `mockEnabled=false`, and an HTTPS non-local `AppProperties.publicBaseUrl` whose `/api/payments/toss/billing/callback` has the same HTTPS origin.
- Errors name only profiles, provider, property names, and reasons; never include key material, Secret contents, callback credentials, raw URLs, or environment dumps. Never read or copy a real `.env`; Compose interpolation uses `--env-file .env.example` plus non-secret fixtures only.
- Keep `compose.yml` on `api,local` and `worker,local`; the pilot overlay uses `api,production`, `worker,production`, and `SPRING_PROFILES_INCLUDE=production` for one-shot workers.
- Do not change `contracts/easy-doc-v1.yaml`, `frontend/src/api/`, API/worker `application.yml`, database migrations, or admin purchase controls.
- Legal effective date is exactly **2026년 11월 1일**; versions are `terms-2026-11-01` and `privacy-2026-11-01`, review date is `2026-10-26`, snapshot selector is `LEGAL_SNAPSHOT_VERSION="2026-11-01"`.
- Production activation requires PR review and operator approval and must not occur before 2026-11-01. No deployment, live key injection, paid LLM call, real charge, renewal, or refund is part of implementation or verification.
- Run verification on the final diff; any later code change invalidates earlier green results. Each task has one scoped commit.

## File Structure

- `SubscriptionConfiguration.kt`: common API/worker payment startup validation and existing gateway bean construction.
- `TossBillingService.kt`: one purchase-eligibility predicate shared by registration and initial-charge approval.
- `SubscriptionService.kt`: overview purchase state delegates to that predicate; plan catalog remains unchanged.
- `compose.pilot.yml`, `.env.example`, `docs/pilot-runbook.md`: Production profiles, safe configuration examples, and operator-only release gate.
- `SubscriptionCard.tsx`, `TargetPlanCatalog.tsx`: existing server-driven availability and Start-only live catalog/consent copy.
- Public Markdown and versioned backend legal resources: identical published and archived paid terms.

---

### Task 1: Payment Environment Startup Guards

**Files:**
- Modify: `backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/subscription/SubscriptionConfiguration.kt`
- Test: `backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/subscription/PaymentConfigurationTest.kt`
- Test: `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/ConfigurationPropertiesBindingTest.kt`
- Test: `backend-kotlin/worker/src/test/kotlin/kr/easydoc/worker/WorkerStartupTest.kt`

**Interfaces:**
- Consumes: existing `PaymentProperties(provider, mockEnabled, tossClientKey, tossSecretKey, purchaseEnabled, autoChargeEnabled, allowedWorkspaces)` and `AppProperties(publicBaseUrl: String)`.
- Produces: `@Bean fun SubscriptionConfiguration.paymentGateway(properties: PaymentProperties, environment: Environment, app: AppProperties): PaymentGateway`; validation finishes before `StubPaymentGateway()` is returned and performs no network call.

- [ ] **Step 1: Add failing environment-matrix and secret-redaction tests**

In `PaymentConfigurationTest.kt`, add `AppProperties` and `Secret` imports, a valid production fixture, and these tests. Update the existing production live-key test to pass `productionApp` as the new third argument; retain all local/test stub, mock, and `toss_test` cases. Add `PaymentProperties` to the API test imports and `PaymentProperties` plus `PaymentGateway` to the worker test imports.

```kotlin
private val productionApp = AppProperties("https://easydoc.kr")
private val live =
    PaymentProperties(
        provider = "toss_live",
        tossClientKey = Secret("live_ck_fixture"),
        tossSecretKey = Secret("live_sk_fixture"),
        purchaseEnabled = true,
        autoChargeEnabled = true,
    )

private fun environment(vararg profiles: String) =
    MockEnvironment().apply { setActiveProfiles(*profiles) }

@Test
fun `local and test reject live billing before gateway construction`() {
    listOf("local", "test").forEach { profile ->
        assertThatThrownBy {
            configuration.paymentGateway(live, environment(profile), AppProperties())
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(profile)
            .hasMessageContaining("easydoc.payment.provider")
            .hasMessageNotContaining("live_ck_fixture", "live_sk_fixture")
    }
}

@Test
fun `production accepts only complete live HTTPS non mock configuration`() {
    assertThat(configuration.paymentGateway(live, environment("production"), productionApp))
        .isInstanceOf(StubPaymentGateway::class.java)

    val invalid = listOf(
        live.copy(provider = "stub"),
        live.copy(provider = "toss_test"),
        live.copy(mockEnabled = true),
        live.copy(tossClientKey = Secret("test_ck_fixture")),
        live.copy(tossSecretKey = Secret.EMPTY),
    )
    invalid.forEach { properties ->
        assertThatThrownBy {
            configuration.paymentGateway(properties, environment("production"), productionApp)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("production")
            .hasMessageNotContaining("live_ck_fixture", "live_sk_fixture", "test_ck_fixture")
    }
}

@Test
fun `production rejects insecure or credential bearing public and callback URLs without echoing them`() {
    listOf(
        "http://easydoc.kr",
        "https://localhost:5173",
        "https://127.0.0.1",
        "https://easydoc.kr/?resume=secret-callback-token",
    ).forEach { url ->
        assertThatThrownBy {
            configuration.paymentGateway(live, environment("production"), AppProperties(url))
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("easydoc.app.public-base-url")
            .hasMessageNotContaining(url, "secret-callback-token")
    }
}

@Test
fun `local stub and explicit local mock remain available`() {
    assertThat(configuration.paymentGateway(PaymentProperties(), environment("local"), AppProperties()))
        .isInstanceOf(StubPaymentGateway::class.java)
    assertThat(
        configuration.paymentGateway(
            PaymentProperties(mockEnabled = true),
            environment("local"),
            AppProperties(),
        ),
    ).isInstanceOf(StubPaymentGateway::class.java)
}
```

Add a binding case to `ConfigurationPropertiesBindingTest.kt` using its existing private `bind` helper:

```kotlin
@Test
fun `결제 제공자와 구매·갱신 스위치가 바인딩된다`() {
    val payment = bind(
        "easydoc.payment",
        PaymentProperties::class.java,
        mapOf(
            "easydoc.payment.provider" to "toss_live",
            "easydoc.payment.purchase-enabled" to "true",
            "easydoc.payment.auto-charge-enabled" to "true",
            "easydoc.payment.mock-enabled" to "false",
        ),
    )
    assertThat(payment.provider).isEqualTo("toss_live")
    assertThat(payment.purchaseEnabled).isTrue()
    assertThat(payment.autoChargeEnabled).isTrue()
    assertThat(payment.mockEnabled).isFalse()
}
```

Add to `WorkerStartupTest.kt`:

```kotlin
@Test
fun `worker 는 공통 결제 설정을 stub 준비 상태로 조립한다`() {
    val payment = context.getBean(PaymentProperties::class.java)
    assertThat(payment.provider).isEqualTo("stub")
    assertThat(payment.mockEnabled).isFalse()
    assertThat(context.getBean(PaymentGateway::class.java)).isNotNull()
}
```

- [ ] **Step 2: Run RED tests**

Run:

```bash
cd backend-kotlin && ./gradlew :infrastructure:test --tests 'kr.easydoc.infrastructure.subscription.PaymentConfigurationTest' :api:test --tests 'kr.easydoc.api.ConfigurationPropertiesBindingTest' :worker:test --tests 'kr.easydoc.worker.WorkerStartupTest'
```

Expected: FAIL. New production URL/local-live cases fail against the two-argument bean or missing guard; compilation also reports the new three-argument call until implementation. Existing local/test tests are not removed.

- [ ] **Step 3: Implement minimal fail-closed validation**

Change the bean signature to the Produced signature and call `validatePaymentEnvironment(properties, environment, app)` before the existing provider-specific return. Keep existing `StubPaymentGateway`, local mock, and `toss_test` construction behavior. Add these private functions inside `SubscriptionConfiguration`; do not log `properties` or `app`.

```kotlin
private fun validatePaymentEnvironment(
    properties: PaymentProperties,
    environment: Environment,
    app: AppProperties,
) {
    val profiles = environment.activeProfiles.toList()
    val production = profiles.any { it == "production" || it == "prod" }
    val development = profiles.any { it == "local" || it == "test" }
    check(properties.provider in setOf("stub", "toss_test", "toss_live")) {
        invalid("easydoc.payment.provider", "unknown provider", profiles)
    }
    check(!(development && properties.provider == "toss_live")) {
        invalid("easydoc.payment.provider", "live billing is forbidden in local/test", profiles)
    }
    if (!production) return
    check(properties.provider == "toss_live") {
        invalid("easydoc.payment.provider", "production requires toss_live", profiles)
    }
    check(!properties.mockEnabled) {
        invalid("easydoc.payment.mock-enabled", "production forbids mock payments", profiles)
    }
    check(properties.tossClientKey.reveal().startsWith("live_ck_")) {
        invalid("easydoc.payment.toss-client-key", "live client key is required", profiles)
    }
    check(properties.tossSecretKey.reveal().startsWith("live_sk_")) {
        invalid("easydoc.payment.toss-secret-key", "live secret key is required", profiles)
    }
    val publicUrl = parsePublicUrl(app.publicBaseUrl, profiles)
    val callback = publicUrl.resolve("/api/payments/toss/billing/callback")
    check(isPublicHttps(callback) && callback.scheme == publicUrl.scheme && callback.host == publicUrl.host && callback.port == publicUrl.port) {
        invalid("easydoc.app.public-base-url", "public and callback HTTPS origins must match", profiles)
    }
}

private fun parsePublicUrl(value: String, profiles: List<String>): java.net.URI {
    val uri = try {
        java.net.URI.create(value)
    } catch (_: IllegalArgumentException) {
        throw IllegalStateException(invalid("easydoc.app.public-base-url", "valid HTTPS public URL is required", profiles))
    }
    check(isPublicHttps(uri) && uri.userInfo == null && uri.query == null && uri.fragment == null) {
        invalid("easydoc.app.public-base-url", "valid credential-free HTTPS public URL is required", profiles)
    }
    return uri
}

private fun isPublicHttps(uri: java.net.URI): Boolean =
    uri.scheme == "https" && uri.host != null &&
        uri.host !in setOf("localhost", "127.0.0.1", "::1")

private fun invalid(property: String, reason: String, profiles: List<String>): String =
    "Payment configuration invalid: profiles=${profiles.joinToString(",")}, property=$property, reason=$reason"
```

Retain the existing non-production `toss_test` prefix checks and mock checks. Validation messages never interpolate key or URL values and no cause carrying a raw URL is attached.

- [ ] **Step 4: Run GREEN tests**

Run the Step 2 command again.

Expected: PASS for all three focused test classes; valid local stub/mock/test paths and valid Production configuration pass, every invalid matrix case fails before gateway construction.

- [ ] **Step 5: Commit the startup guard slice**

```bash
git add backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/subscription/SubscriptionConfiguration.kt backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/subscription/PaymentConfigurationTest.kt backend-kotlin/api/src/test/kotlin/kr/easydoc/api/ConfigurationPropertiesBindingTest.kt backend-kotlin/worker/src/test/kotlin/kr/easydoc/worker/WorkerStartupTest.kt
git commit -m "feat: validate production payment startup"
```

---

### Task 2: Production All-User Start Eligibility

**Files:**
- Modify: `backend-kotlin/application/src/main/kotlin/kr/easydoc/application/subscription/TossBillingService.kt`
- Modify: `backend-kotlin/application/src/main/kotlin/kr/easydoc/application/subscription/SubscriptionService.kt`
- Test: `backend-kotlin/application/src/test/kotlin/kr/easydoc/application/subscription/TossBillingServiceTest.kt`

**Interfaces:**
- Consumes: existing `purchaseEnabled`, `autoChargeEnabled`, `allowedWorkspaces`, `environment`, `begin(...)`, `complete(...)`, `process(id)`, and `SubscriptionOverview.plans` from Task 1's unchanged properties.
- Produces: `fun TossBillingService.purchaseAllowed(workspace: UUID): Boolean`, used by `begin`, `chargeAllowed`, and `SubscriptionService.overview`; live result is exactly `purchaseEnabled`.

- [ ] **Step 1: Add failing live overview/begin/approval tests while retaining review tests**

Extend `Fixture` with `environment: String = "toss_test"`, pass `environment = environment` to `TossBillingService`, `BillingOrder`, and `BillingSession`, and expose the existing user repository as `private val userRepository`. Add this fixture method using its existing stores and `DirectTransaction`:

```kotlin
fun overview(): SubscriptionOverview =
    SubscriptionService(
        subscriptions,
        CreditAccountService(accounts, enforced = false),
        DirectTransaction,
        object : PaymentGateway {
            override fun charge(orderId: UUID, amount: Int, fail: Boolean): Boolean =
                error("stub gateway must not be called")
        },
        false,
        Clock.fixed(now, ZoneOffset.UTC),
        zone,
        userRepository,
        service,
    ).read(owner, workspace)
```

Add tests:

```kotlin
@Test
fun `production purchase opens Start overview registration and initial approval for ordinary workspace`() {
    val f = Fixture(environment = "toss_live", purchase = true, reviewWorkspace = false)
    f.store.orders.clear()
    f.store.currentSession = null

    val overview = f.overview()
    assertThat(overview.purchaseEnabled).isTrue()
    assertThat(overview.autoChargeEnabled).isTrue()
    assertThat(overview.plans.map { it.id }).containsExactly("start")
    assertThat(overview.plans.single().monthlyPrice).isEqualTo(99_000)
    assertThat(overview.plans.single().allowance).isEqualTo(50)

    val session = f.service.begin(
        f.owner,
        f.workspace,
        "start",
        consentVersion = "start-monthly-v1",
    )
    assertThat(session.workspaceId).isEqualTo(f.workspace)
    assertThat(session.environment).isEqualTo("toss_live")

    val approval = Fixture(environment = "toss_live", purchase = true, reviewWorkspace = false)
    approval.service.process(approval.order.id)
    assertThat(approval.gateway.charges).isEqualTo(1)
}

@Test
fun `production ignores review allowlist in both directions`() {
    val closed = Fixture(environment = "toss_live", purchase = false, reviewWorkspace = true)
    closed.store.orders.clear()
    closed.store.currentSession = null
    assertThat(closed.overview().purchaseEnabled).isFalse()
    assertThatThrownBy {
        closed.service.begin(
            closed.owner,
            closed.workspace,
            "start",
            consentVersion = "start-monthly-v1",
        )
    }.isInstanceOf(kr.easydoc.core.exceptions.ConflictException::class.java)

    val closedApproval = Fixture(environment = "toss_live", purchase = false, reviewWorkspace = true)
    closedApproval.service.process(closedApproval.order.id)
    assertThat(closedApproval.gateway.charges).isZero()

    val otherWorkspace = Fixture(environment = "toss_live", purchase = true, reviewWorkspace = false)
    assertThat(otherWorkspace.overview().purchaseEnabled).isTrue()
}

@Test
fun `production keeps existing Start only plan validation`() {
    val f = Fixture(environment = "toss_live")
    f.store.orders.clear()
    f.store.currentSession = null
    assertThatThrownBy {
        f.service.begin(f.owner, f.workspace, "basic", consentVersion = "start-monthly-v1")
    }.isInstanceOf(kr.easydoc.core.exceptions.InvalidInputException::class.java)
        .hasMessage("알 수 없는 구독 플랜입니다")
}
```

Keep the existing `closed purchases allow only the review workspace...` and `review allowlist permits initial charge...` tests unchanged: their default `toss_test` fixture proves non-live review behavior remains.

- [ ] **Step 2: Run RED tests**

Run:

```bash
cd backend-kotlin && ./gradlew :application:test --tests 'kr.easydoc.application.subscription.TossBillingServiceTest'
```

Expected: FAIL. `overview()` has no predicate to call yet, live allowlisted closed purchases still pass, and live overview purchase state still depends on `allowedWorkspaces`.

- [ ] **Step 3: Implement one shared eligibility predicate**

In `TossBillingService`, add:

```kotlin
fun purchaseAllowed(workspace: UUID): Boolean =
    purchaseEnabled || (environment != "toss_live" && workspace in allowedWorkspaces)
```

Replace only the purchase boolean passed to `validateRegistrationRequest` with `purchaseAllowed(workspace)`. In `chargeAllowed`, replace the `"charge"` branch with `"charge" -> purchaseAllowed(order.workspaceId)`; keep `"renewal" -> autoChargeEnabled` unchanged.

In `SubscriptionService.overview`, replace the `purchaseEnabled` argument expression with:

```kotlin
toss?.purchaseAllowed(workspaceId) == true,
```

Do not alter `owned`, `lockOwned`, consent validation, `complete`, provider environment checks, idempotency, renewal, refund, or `SubscriptionPlanCatalog`.

- [ ] **Step 4: Run GREEN tests**

Run the Step 2 command again.

Expected: PASS, including ordinary live Start begin/initial charge, closed-live allowlist rejection, Start-only plan validation, and all existing test-environment review/recovery tests.

- [ ] **Step 5: Commit the eligibility slice**

```bash
git add backend-kotlin/application/src/main/kotlin/kr/easydoc/application/subscription/TossBillingService.kt backend-kotlin/application/src/main/kotlin/kr/easydoc/application/subscription/SubscriptionService.kt backend-kotlin/application/src/test/kotlin/kr/easydoc/application/subscription/TossBillingServiceTest.kt
git commit -m "feat: open production Start purchases"
```

---

### Task 3: Separate Local and Production Compose Profiles

**Files:**
- Modify: `compose.pilot.yml`
- Modify: `.env.example`
- Modify: `docs/pilot-runbook.md`
- Verify unchanged: `compose.yml`

**Interfaces:**
- Consumes: Task 1's Production startup guards and existing `EASYDOC_PAYMENT_PURCHASE_ENABLED` / `EASYDOC_PAYMENT_AUTO_CHARGE_ENABLED` properties.
- Produces: public overlay commands `api,production`, `worker,production`, and default `SPRING_PROFILES_INCLUDE=production`; local Compose remains `local`.

- [ ] **Step 1: Run safe profile assertions that fail on the current overlay**

Use only non-secret shell fixtures; do not invoke plain `docker compose config` where ambient `.env` interpolation is possible.

```bash
export SPRING_DATASOURCE_PASSWORD=compose-fixture
export EASYDOC_PHONE_VERIFICATION_PEPPER=compose-fixture
export EASYDOC_SENS_SERVICE_ID=compose-fixture
export EASYDOC_SENS_ACCESS_KEY=compose-fixture
export EASYDOC_SENS_SECRET_KEY=compose-fixture
export EASYDOC_SMS_FROM=01000000000
local_config="$(docker compose --env-file .env.example -f compose.yml config)"
printf '%s' "$local_config" | grep -F -- '--spring.profiles.active=api,local'
printf '%s' "$local_config" | grep -F -- '--spring.profiles.active=worker,local'
pilot_config="$(docker compose --env-file .env.example -f compose.yml -f compose.pilot.yml config)"
printf '%s' "$pilot_config" | grep -F -- '--spring.profiles.active=api,production'
printf '%s' "$pilot_config" | grep -F -- '--spring.profiles.active=worker,production'
printf '%s' "$pilot_config" | grep -F 'SPRING_PROFILES_INCLUDE: production'
! printf '%s' "$pilot_config" | grep -F -- '--spring.profiles.active=api,local'
! printf '%s' "$pilot_config" | grep -F -- '--spring.profiles.active=worker,local'
```

Expected: local assertions PASS; the first Production assertion FAILS because the current overlay emits `api,local`, and the current include default is `local`.

- [ ] **Step 2: Change only profile wiring and examples**

In `compose.pilot.yml`, use these exact values and replace the obsolete Toss-test comments:

```yaml
backend-api:
  command: ["java", "-jar", "/app/easy-doc-api.jar", "--spring.profiles.active=api,production"]

backend-worker:
  command: ["java", "-jar", "/app/easy-doc-worker.jar", "--spring.profiles.active=worker,production"]
  environment:
    SPRING_PROFILES_INCLUDE: ${EASYDOC_WORKER_PROFILES_INCLUDE:-production}
```

Do not edit `compose.yml`. In `.env.example`, keep local defaults `EASYDOC_PAYMENT_PROVIDER=stub`, both purchase flags false, and `EASYDOC_WORKER_PROFILES_INCLUDE=local`. Replace the allowlist comment with:

```dotenv
# Optional local/test review workspaces only; ignored for toss_live purchase eligibility.
EASYDOC_PAYMENT_ALLOWED_WORKSPACES=
```

Append this operator documentation, with blank key values retained:

```dotenv
# Production operator settings (not local defaults): provider=toss_live, mock=false,
# purchase=true and autoCharge=true at the approved release. API/worker/one-shot profiles
# come from compose.pilot.yml. Inject matching live MID keys only through server secrets.
# EASYDOC_APP_PUBLIC_BASE_URL must be public HTTPS; callback is
# /api/payments/toss/billing/callback on that same origin.
```

In `docs/pilot-runbook.md`, replace the current statements that public pilot must retain `local` with the three Production values above. Add a **Production Start 출시 게이트** section containing this checklist verbatim:

```markdown
- [ ] PR 리뷰와 운영자 출시 승인이 완료됐고 현재 일자가 2026년 11월 1일 이후다.
- [ ] `EASYDOC_PAYMENT_PROVIDER=toss_live`, `EASYDOC_PAYMENT_MOCK_ENABLED=false`다.
- [ ] `EASYDOC_PAYMENT_PURCHASE_ENABLED=true`, `EASYDOC_PAYMENT_AUTO_CHARGE_ENABLED=true`다.
- [ ] Toss 라이브 MID와 `live_ck_`·`live_sk_`가 같은 상점·환경이며 Secret으로만 주입됐다.
- [ ] 공개 기준 URL과 `/api/payments/toss/billing/callback`이 같은 공개 HTTPS 출처다.
- [ ] 라이브 `PAYMENT_STATUS_CHANGED`·`BILLING_DELETED` 웹훅과 SMTP 실제 수신을 운영자가 확인했다.
- [ ] Start 99,000원·50크레딧과 2026년 11월 1일 약관·동의·스냅샷이 일치한다.
- [ ] 최초 실결제·환불은 승인된 운영자만 수행하고 에이전트는 실행하지 않는다.
```

Document incident response: set the two existing purchase flags independently to false, never substitute `stub`/`toss_test`, and preserve lookup/refund/card deletion/manual recovery. No deployment command is executed in this task.

- [ ] **Step 3: Run GREEN Compose and content checks**

Run Step 1 again, then:

```bash
docker compose --env-file .env.example -f compose.yml config --quiet
docker compose --env-file .env.example -f compose.yml -f compose.pilot.yml config --quiet
grep -F '2026년 11월 1일' docs/pilot-runbook.md
grep -F 'SPRING_PROFILES_INCLUDE: ${EASYDOC_WORKER_PROFILES_INCLUDE:-production}' compose.pilot.yml
```

Expected: every profile assertion and both Compose parses PASS; local still emits `local`, pilot emits only Production app profiles, and the release gate is present. No container starts and no transaction occurs.

- [ ] **Step 4: Commit the Compose/runbook slice**

```bash
git add compose.pilot.yml .env.example docs/pilot-runbook.md
git commit -m "chore: separate production compose billing"
```

---

### Task 4: Server-Driven User and Admin Start UI

**Files:**
- Modify: `frontend/src/components/subscription/SubscriptionCard.tsx`
- Test: `frontend/src/components/subscription/SubscriptionCard.test.tsx`
- Test: `frontend/src/components/subscription/TargetPlanCatalog.test.tsx`
- Test: `frontend/src/pages/UsagePage.test.tsx`
- Test: `frontend/src/pages/admin/AdminWorkspacesTab.test.tsx`
- Verify unchanged: `frontend/src/components/subscription/TargetPlanCatalog.tsx`, `frontend/src/api/`

**Interfaces:**
- Consumes: existing `SubscriptionOverview` wire fields `mock_enabled`, `toss_enabled`, `billing_environment`, `purchase_enabled`, `auto_charge_enabled`, and `plans`; Task 2 returns Start-only plans and live purchase state.
- Produces: `billingAvailable = Boolean(view?.mock_enabled || (view?.toss_enabled && view?.purchase_enabled === true))`; admin continues rendering no `TargetPlanCatalog` or purchase button.

- [ ] **Step 1: Add failing closed-live and focused production UI tests**

Add to `SubscriptionCard.test.tsx`:

```tsx
it.each([true, false])('live availability follows purchase_enabled=%s', async (purchase) => {
  vi.mocked(getSubscription).mockResolvedValue({
    ...available,
    mock_enabled: false,
    toss_enabled: true,
    billing_environment: 'toss_live',
    purchase_enabled: purchase,
  })
  render(<SubscriptionCard workspaceId="w1" />)
  if (purchase) {
    expect(await screen.findByText('선택한 플랜 없음')).toBeInTheDocument()
    expect(screen.queryByText('구독 서비스 준비 중')).not.toBeInTheDocument()
    expect(screen.queryByRole('radio', { name: 'Basic' })).not.toBeInTheDocument()
    expect(screen.queryByRole('radio', { name: 'Pro' })).not.toBeInTheDocument()
  } else {
    expect(await screen.findByText('구독 서비스 준비 중')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /정기결제 시작/ })).not.toBeInTheDocument()
  }
})
```

Add to `TargetPlanCatalog.test.tsx`:

```tsx
it('live catalog contains only Start with the existing price and credits', () => {
  render(<TargetPlanCatalog workspaceId="w1" live checkoutEnabled tossEnabled />)
  expect(screen.getByRole('radio', { name: 'Start' })).toBeInTheDocument()
  expect(screen.queryByRole('radio', { name: 'Basic' })).not.toBeInTheDocument()
  expect(screen.queryByRole('radio', { name: 'Pro' })).not.toBeInTheDocument()
  expect(screen.getByText(/Start는 월 99,000원에 50크레딧/)).toBeInTheDocument()
  expect(screen.queryByText(/실제 금액은 청구되지 않습니다/)).not.toBeInTheDocument()
})
```

Add to `UsagePage.test.tsx`:

```tsx
it('production purchase response shows only Start and no preparation heading', async () => {
  vi.mocked(getSubscription).mockResolvedValue({
    mock_enabled: false,
    toss_enabled: true,
    billing_environment: 'toss_live',
    purchase_enabled: true,
    auto_charge_enabled: true,
    plans: [{ id: 'start', name: 'Start', allowance: 50, monthly_price: 99_000 }],
    subscription: null,
    payments: [],
  })
  render(page())
  expect(await screen.findByText('선택한 플랜 없음')).toBeInTheDocument()
  expect(screen.queryByText('구독 서비스 준비 중')).not.toBeInTheDocument()
  expect(screen.queryByRole('radio', { name: 'Basic' })).not.toBeInTheDocument()
  expect(screen.queryByRole('radio', { name: 'Pro' })).not.toBeInTheDocument()
})
```

Add to `AdminWorkspacesTab.test.tsx`:

```tsx
it('관리자는 production 가용성을 보지만 구매 버튼은 없다', async () => {
  vi.mocked(getSubscription).mockResolvedValue({
    mock_enabled: false,
    toss_enabled: true,
    billing_environment: 'toss_live',
    purchase_enabled: true,
    plans: [{ id: 'start', name: 'Start', allowance: 50, monthly_price: 99_000 }],
    subscription: null,
    payments: [],
  })
  await open()
  expect(await screen.findByText('선택한 플랜 없음')).toBeInTheDocument()
  expect(screen.queryByText('구독 서비스 준비 중')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /정기결제 시작/ })).not.toBeInTheDocument()
})
```

- [ ] **Step 2: Run RED frontend tests**

Run:

```bash
cd frontend && npm run test -- --run src/components/subscription/SubscriptionCard.test.tsx src/components/subscription/TargetPlanCatalog.test.tsx src/pages/UsagePage.test.tsx src/pages/admin/AdminWorkspacesTab.test.tsx
```

Expected: FAIL in the `purchase_enabled=false` case because the current `live || purchase_enabled` expression marks closed live billing available. Start-only assertions document and protect the existing live filter.

- [ ] **Step 3: Apply the minimal availability correction**

In `SubscriptionCard.tsx`, replace only the `billingAvailable` declaration with:

```tsx
const billingAvailable = Boolean(
  view?.mock_enabled || (view?.toss_enabled && view?.purchase_enabled === true),
)
```

Leave the existing `live` filter, consent version, `openTossBilling` calls, checkout enablement, recovery controls, and `!admin` catalog boundary unchanged. No API type or component prop changes are needed.

- [ ] **Step 4: Run GREEN frontend tests**

Run the Step 2 command again.

Expected: PASS; available live shows Start only, closed live shows preparation copy, local mock/test behavior remains, and admin has no purchase button.

- [ ] **Step 5: Commit the UI slice**

```bash
git add frontend/src/components/subscription/SubscriptionCard.tsx frontend/src/components/subscription/SubscriptionCard.test.tsx frontend/src/components/subscription/TargetPlanCatalog.test.tsx frontend/src/pages/UsagePage.test.tsx frontend/src/pages/admin/AdminWorkspacesTab.test.tsx
git commit -m "feat: align production Start subscription UI"
```

---

### Task 5: Legal Consent, Guide, Snapshots, and Final Verification

**Files:**
- Modify: `frontend/src/components/subscription/TargetPlanCatalog.tsx`
- Modify: `frontend/src/content/legal/terms-of-service.md`
- Modify: `frontend/src/content/legal/privacy-policy.md`
- Modify: `frontend/src/content/guide/user-guide.md`
- Test: `frontend/src/components/subscription/TargetPlanCatalog.test.tsx`
- Test: `frontend/src/pages/TermsPage.test.tsx`
- Test: `frontend/src/pages/PrivacyPolicyPage.test.tsx`
- Test: `frontend/src/pages/GuidePage.test.tsx`
- Modify: `backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/retention/JdbcRetainedTransactionRepository.kt`
- Create: `backend-kotlin/infrastructure/src/main/resources/legal/terms-2026-11-01.md`
- Create: `backend-kotlin/infrastructure/src/main/resources/legal/privacy-2026-11-01.md`
- Test: `backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/retention/JdbcRetainedTransactionRepositoryTest.kt`

**Interfaces:**
- Consumes: existing `CURRENT_CONSENT_VERSION="start-monthly-v1"`, `legalResource(path)`, Task 4's live consent UI, and public Markdown sources.
- Produces: `LEGAL_SNAPSHOT_VERSION="2026-11-01"` and current-snapshot eligibility beginning `2026-11-01T00:00:00+09:00`; archived and published documents have identical effective paid conditions.

- [ ] **Step 1: Add failing legal and snapshot assertions**

Add to `TargetPlanCatalog.test.tsx`:

```tsx
it('shows the fixed paid effective date beside live consent', () => {
  render(<TargetPlanCatalog workspaceId="w1" live checkoutEnabled tossEnabled />)
  expect(screen.getByText('유료 서비스 적용일: 2026년 11월 1일')).toBeInTheDocument()
  expect(screen.getByLabelText(/금액, 제공량, 매월 자동결제/)).toBeInTheDocument()
})
```

Change Terms expectations to `terms-2026-11-01`, `검토일: 2026-10-26`, and add:

```tsx
expect(screen.getByText(/유료 서비스 적용일: 2026년 11월 1일/)).toBeInTheDocument()
expect(screen.getByText(/Start는 월 99,000원에 50크레딧/)).toBeInTheDocument()
expect(screen.queryByText(/출시 전 공고로 확정/)).not.toBeInTheDocument()
expect(screen.queryByText(/확정 전에는 신규 실결제를 받지 않습니다/)).not.toBeInTheDocument()
```

Change Privacy expectations to `privacy-2026-11-01`, `검토일: 2026-10-26`, and add:

```tsx
expect(screen.getByText(/유료 거래 관련 변경 적용일: 2026년 11월 1일/)).toBeInTheDocument()
expect(screen.queryByText(/유료 거래 관련 변경 적용일: 출시 전 공고/)).not.toBeInTheDocument()
```

In `GuidePage.test.tsx`, remove the `TARGET_PLANS` loop and assert the open pricing disclosure contains Start conditions and no other sale promise:

```tsx
expect(details).toHaveTextContent('Start')
expect(details).toHaveTextContent('월 99,000원 · 50크레딧')
expect(details).toHaveTextContent('2026년 11월 1일')
expect(details).toHaveTextContent('실제 승인일부터 한 달')
expect(details).toHaveTextContent('다음 결제가 중단')
expect(details).toHaveTextContent('이미 결제한 이용 기간은 유지')
expect(details).toHaveTextContent('고객 지원')
expect(details).not.toHaveTextContent('Basic')
expect(details).not.toHaveTextContent('Pro')
```

In `JdbcRetainedTransactionRepositoryTest.kt`, update archived snapshot assertions and fixture consent timestamps to November:

```kotlin
assertThat(archivedConsent.payloadJson).contains("terms_snapshot", "2026-11-01")
assertThat(archivedConsent.payloadJson).contains("privacy_snapshot", "2026-11-01")
assertThat(archivedConsent.payloadJson).contains("2026년 11월 1일", "99,000원", "50크레딧")
assertThat(archivedConsent.summary.manualReview).isFalse()
```

Use `consentAt = Instant.parse("2026-11-01T00:00:00Z")` in `sessionModel` and the same date in the seeded `owner` SQL. Add a direct resource parity check that does not need a database:

```kotlin
@Test
fun `November legal resources contain the published Start conditions`() {
    listOf("terms", "privacy").forEach { kind ->
        val text = requireNotNull(javaClass.classLoader.getResource("legal/$kind-2026-11-01.md"))
            .readText()
        assertThat(text).contains("$kind-2026-11-01", "2026-10-26", "2026년 11월 1일")
        assertThat(text).doesNotContain("출시 전 공고로 확정", "확정 전에는 신규 실결제를 받지 않습니다")
    }
    val terms = requireNotNull(javaClass.classLoader.getResource("legal/terms-2026-11-01.md")).readText()
    assertThat(terms).contains("월 99,000원", "50크레딧", "start-monthly-v1")
}
```

For the last assertion, include the existing consent identifier in the terms paid-use clause so the published condition and archived consent key are explicitly linked.

- [ ] **Step 2: Run RED legal tests**

Run:

```bash
cd frontend && npm run test -- --run src/components/subscription/TargetPlanCatalog.test.tsx src/pages/TermsPage.test.tsx src/pages/PrivacyPolicyPage.test.tsx src/pages/GuidePage.test.tsx
cd ../backend-kotlin && ./gradlew :infrastructure:test --tests 'kr.easydoc.infrastructure.retention.JdbcRetainedTransactionRepositoryTest'
```

Expected: frontend FAILS on old versions/missing date/Basic-Pro guide; backend FAILS because November resources and selector do not exist.

- [ ] **Step 3: Apply exact public copy and snapshot changes**

In `TargetPlanCatalog.tsx`, add this paragraph immediately before the consent label:

```tsx
{live && <p>유료 서비스 적용일: 2026년 11월 1일</p>}
```

In Terms, replace the header paid fields with:

```markdown
- **버전: `terms-2026-11-01`**
- 검토일: 2026-10-26
- 사업자 정보 갱신일: 2026-10-20 (연락처·통신판매업 신고번호)
- 유료 서비스 적용일: 2026년 11월 1일
```

Remove the launch-preparation sentence after 제9조 and replace 부칙 with:

```markdown
## 부칙

이 약관의 Start 유료 조건과 `start-monthly-v1` 동의 문구는 2026년 11월 1일부터 적용합니다.
```

Keep 제9조's existing price, 50 credits, actual-approval month, monthly card charge, renewal cancellation, statutory refund/customer-support rules; do not introduce a guaranteed refund amount.

In Privacy, replace its first three metadata lines with:

```markdown
- **버전: `privacy-2026-11-01`**
- 검토일: 2026-10-26
- 유료 거래 관련 변경 적용일: 2026년 11월 1일
```

Replace the outdated §3.1/§3.2 statements saying separated live-transaction retention is not implemented with the existing behavior: live payment/supply/refund/consent evidence is archived separately for five years, complaint/dispute decisions for three years, expired eligible records are purged, and incomplete/manual-review evidence blocks account-deletion finalization. Preserve the existing backup, contact, and data-rights text.

Replace the Guide pricing block from `**Start**` through the Pro line with:

```markdown
**Start** — 월 99,000원 · 50크레딧 · 유료 서비스 적용일 2026년 11월 1일

월 정기결제 상품입니다. 실제 승인일부터 한 달간 이용하며 등록 카드로 매월 자동 결제합니다. **사용량**에서 갱신을 중단하면 다음 결제가 중단되고 이미 결제한 이용 기간은 유지됩니다. 환불은 적용 법령, 공개 약관, 결제·이용 내역에 따라 고객 지원에서 확인합니다. Basic·Pro는 판매하지 않으며 가격·제공량·출시 시점을 약속하지 않습니다.
```

In `JdbcRetainedTransactionRepository.kt`, retain `CURRENT_CONSENT_VERSION` and change only:

```kotlin
const val LEGAL_SNAPSHOT_VERSION = "2026-11-01"
```

Change the `current` cutoff to:

```kotlin
occurredAt != null && !occurredAt.isBefore(Instant.parse("2026-11-01T00:00:00+09:00"))
```

After public Markdown is final, create byte-identical backend snapshots:

```bash
cp frontend/src/content/legal/terms-of-service.md backend-kotlin/infrastructure/src/main/resources/legal/terms-2026-11-01.md
cp frontend/src/content/legal/privacy-policy.md backend-kotlin/infrastructure/src/main/resources/legal/privacy-2026-11-01.md
cmp frontend/src/content/legal/terms-of-service.md backend-kotlin/infrastructure/src/main/resources/legal/terms-2026-11-01.md
cmp frontend/src/content/legal/privacy-policy.md backend-kotlin/infrastructure/src/main/resources/legal/privacy-2026-11-01.md
```

Do not alter or delete the October snapshots; historical archives remain readable and no migration is added.

- [ ] **Step 4: Run focused GREEN tests**

Run both Step 2 commands again and the two `cmp` commands.

Expected: PASS; new live consents on/after the effective instant archive November documents, public copy has one Start offer and no deferred-launch wording, and public/snapshot files are byte-identical.

- [ ] **Step 5: Run final backend/frontend/Compose verification on the final diff**

Run from the worktree root, retaining the non-secret Compose fixture exports from Task 3 (re-export those exact six fixture values in a new shell):

```bash
cd backend-kotlin && ./gradlew build
cd ../frontend && npm run check && npm run test -- --run && npm run build
cd .. && docker compose --env-file .env.example -f compose.yml config
docker compose --env-file .env.example -f compose.yml -f compose.pilot.yml config --quiet
git diff --exit-code -- contracts/easy-doc-v1.yaml frontend/src/api
```

Expected: backend build PASS, frontend type/lint checks and all tests PASS, frontend production build PASS, both Compose configurations PASS, and contract/API diff is empty. Repeat Task 3's exact local/Production profile assertions on this final diff. Do not start services, deploy, read real `.env`, inject live keys, or execute transactions.

- [ ] **Step 6: Commit legal content and snapshots**

```bash
git add frontend/src/components/subscription/TargetPlanCatalog.tsx frontend/src/components/subscription/TargetPlanCatalog.test.tsx frontend/src/content/legal/terms-of-service.md frontend/src/content/legal/privacy-policy.md frontend/src/content/guide/user-guide.md frontend/src/pages/TermsPage.test.tsx frontend/src/pages/PrivacyPolicyPage.test.tsx frontend/src/pages/GuidePage.test.tsx backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/retention/JdbcRetainedTransactionRepository.kt backend-kotlin/infrastructure/src/main/resources/legal/terms-2026-11-01.md backend-kotlin/infrastructure/src/main/resources/legal/privacy-2026-11-01.md backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/retention/JdbcRetainedTransactionRepositoryTest.kt
git commit -m "docs: publish November Start billing terms"
```
