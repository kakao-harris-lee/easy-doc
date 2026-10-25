# Operator notification final-review fixes

## Scope and outcome

Resolved the final-review findings in `backend-kotlin` only. No frontend, contract, or Compose files were changed. No real Telegram or paid LLM calls were made, and no `.env` file was read or created. Compose validation was intentionally not run.

## Initial findings

The baseline `./gradlew detekt` reproduced 10 findings:

- Application: `TossBillingService.failed` `LongMethod`; `TossBillingService.finish` `CyclomaticComplexMethod`; `TossBillingService.failedOrderEvent` `ReturnCount`; `DeleteAccountService` constructor `LongParameterList`; `AuthServiceTest` and `DeleteAccountServiceTest` `UseCheckOrError`.
- Infrastructure: `AuthConfiguration.deleteAccountService` `LongParameterList`; `TelegramConfiguration` `MaxLineLength` and `MagicNumber`; `TelegramPropertiesTest` `MaxLineLength`.

The final triage also required explicit operator-notification dependencies, formatting-independent configuration assertions, and direct no-notification tests for scheduled-renewal creation and release-only retry.

## Changes

- Removed default no-op `OperatorNotifications` constructor arguments from `AuthService` and `SocialLoginService`; updated all affected construction sites explicitly.
- Added narrow, justified `LongParameterList` suppressions for the account-deletion use case and its bean factory.
- Replaced test-only `throw IllegalStateException(...)` with `error(...)`.
- Extracted Telegram timeout constants and applied project Kotlin formatting.
- Refactored `TossBillingService` failure/finish paths without changing notification policy.
- Added direct assertions that scheduled-renewal creation and release-only retry emit no operator notifications.
- Strengthened configuration binding checks using `YamlPropertySourceLoader`, exact Telegram environment assignments, duplicate/malformed-line rejection, and base-url absence independent of YAML formatting.
- Added redacting `toString()` implementations for account-deletion and member notification data classes; updated the source-declaration gate from 496 to 501.

## TDD evidence

The two new notification tests passed against the unmodified production behavior (`final-new-tests-baseline.log`). Temporary production mutations proved they were non-vacuous:

- Scheduled-renewal mutation: RED with unexpected `RENEWAL_APPROVED` (`final-scheduled-red.log`).
- Release-only mutation: RED with unexpected `PAYMENT_APPROVED` (`final-release-red.log`).
- Mutations were immediately reverted; the tests returned to GREEN (`final-new-tests-green.log`, then a clean focused execution in `final-focused-application.log`).

The first full build after the review fixes also exposed two `SensitiveToStringReachTest` failures: 501 source declarations versus the expected 496, and generated `toString()` leaks in four account/member types. After redaction fixes, this command passed:

```bash
./gradlew :api:cleanTest :api:test --tests 'kr.easydoc.api.SensitiveToStringReachTest'
```

Result: `BUILD SUCCESSFUL in 3s` (`final-sensitive-tostring.log`).

## Final focused verification

Static checks:

```bash
./gradlew detekt ktlintCheck
```

Result: `BUILD SUCCESSFUL in 2s` (`final-static-checks.log`).

Application tests:

```bash
./gradlew :application:cleanTest :application:test --tests 'kr.easydoc.application.notification.OperatorNotificationServiceTest' --tests 'kr.easydoc.application.auth.AuthServiceTest' --tests 'kr.easydoc.application.auth.SocialLoginServiceTest' --tests 'kr.easydoc.application.account.DeleteAccountServiceTest' --tests 'kr.easydoc.application.subscription.TossBillingServiceTest'
```

Result: `BUILD SUCCESSFUL in 1s` (`final-focused-application.log`).

Infrastructure tests:

```bash
./gradlew :infrastructure:cleanTest :infrastructure:test --tests 'kr.easydoc.infrastructure.telegram.TelegramOperatorNotificationAdapterTest' --tests 'kr.easydoc.infrastructure.telegram.TelegramPropertiesTest' --tests 'kr.easydoc.infrastructure.telegram.TelegramConfigurationTest' --tests 'kr.easydoc.infrastructure.auth.JdbcAccountDeletionRepositoryTest'
```

Result: `BUILD SUCCESSFUL in 12s` (`final-focused-infrastructure.log`).

API tests:

```bash
./gradlew :api:cleanTest :api:test --tests 'kr.easydoc.api.ConfigurationPropertiesBindingTest' --tests 'kr.easydoc.api.SensitiveToStringReachTest'
```

Result: `BUILD SUCCESSFUL in 2s` (`final-focused-api.log`).

## Full build limitation

Command, run twice after the final production changes:

```bash
./gradlew build
```

Neither invocation completed within the mandatory 300-second tool timeout. The first reached `:api:check` and `:api:build` before termination (`final-build.log`). The resumed invocation completed API and worker build tasks, then was terminated while `:infrastructure:test` was running (`final-build-rerun.log`). Neither log reported a test failure, but neither contains a successful full-build result. Full-build completion therefore remains **unconfirmed**, not passed.

`git diff --check` completed with exit code 0. Final review covered substantive changes with whitespace-insensitive diffs as well as the complete changed-file list.
