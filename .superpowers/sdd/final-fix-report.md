# Production Start billing final-fix report

## Status and scope

Completed all three final whole-branch review findings in the requested worktree:

1. Production `publicBaseUrl` now accepts only an empty path or `/`, matching the controller's append behavior without changing `TossController` or adding a shared URL builder.
2. Production HTTPS host validation normalizes scheme/host case and one trailing dot, rejects localhost variants and non-public IPv4/IPv6 literals, and preserves ordinary public-domain acceptance. Domain names are never passed to `InetAddress`; only syntactically identified IP literals use standard-library classification. No dependency was added.
3. `docs/pilot-runbook.md` now contains the complete first-charge/refund reconciliation, pending/retry, callback security, and compatible rollback checklist while preserving operator-only transactions and the 2026-11-01 gate.

Changed product/documentation files:

- `backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/subscription/SubscriptionConfiguration.kt`
- `backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/subscription/PaymentConfigurationTest.kt`
- `docs/pilot-runbook.md`

No real `.env`, real secret, DNS lookup, payment/paid call, deployment, contract change, frontend API change, or unrelated refactor was performed. No full backend build was run, as instructed.

## TDD RED evidence

Tests were added before production code for:

- rejection of `https://easydoc.kr/app` without echoing the raw URL, plus acceptance of root `/`;
- rejection of uppercase/trailing-dot localhost, `.localhost`, `localhost.localdomain`, `127.0.0.2`, RFC1918 IPv4, link-local, unspecified and multicast IPv4, and IPv6 loopback/unspecified/unique-local/site-local/link-local/multicast literals, all with sanitized errors;
- acceptance of `HTTPS://EASYDOC.KR.`.

An initial invocation from the worktree root was a setup error, not a test result:

```bash
./gradlew :infrastructure:test --tests 'kr.easydoc.infrastructure.subscription.PaymentConfigurationTest' --console=plain
```

Result: exit 127, `./gradlew: No such file or directory`; the wrapper is under `backend-kotlin`.

The actual RED command, run from `backend-kotlin`, was:

```bash
./gradlew :infrastructure:test --tests 'kr.easydoc.infrastructure.subscription.PaymentConfigurationTest' --console=plain
```

Result: exit 1; 11 tests completed and exactly the three new tests failed for the intended reasons:

```text
production rejects localhost variants and non public IP literals without DNS or raw URL errors:
  AssertionError: Expecting code to raise a throwable (PaymentConfigurationTest.kt:110)
production accepts an ordinary public domain independent of case and trailing dot:
  IllegalStateException: valid credential-free HTTPS public URL is required
production rejects non root public URL paths without echoing them:
  AssertionError: Expecting code to raise a throwable (PaymentConfigurationTest.kt:81)
11 tests completed, 3 failed
BUILD FAILED
```

The failure output contained only sanitized configuration diagnostics, not the rejected raw URL values.

## GREEN and final focused verification

After the minimal implementation, this command was run from `backend-kotlin`:

```bash
./gradlew :infrastructure:test --tests 'kr.easydoc.infrastructure.subscription.PaymentConfigurationTest' --console=plain
```

Result: exit 0, `BUILD SUCCESSFUL`; all 11 payment configuration tests passed.

Static checks initially identified only style issues in the new helpers. The same combined command was rerun after behavior-preserving cleanup; intermediate results were, in order: seven detekt issues (return counts and magic numbers), one detekt complexity issue, two detekt issues (class function count and return count), and one ktlint multiline-expression issue. No test expectations were weakened. The final command was:

```bash
./gradlew :infrastructure:test --tests 'kr.easydoc.infrastructure.subscription.PaymentConfigurationTest' :infrastructure:detekt :infrastructure:ktlintCheck --console=plain
```

Final result: exit 0, `BUILD SUCCESSFUL in 3s`, 25 actionable tasks (7 executed, 18 up-to-date). Payment tests, detekt, and all infrastructure ktlint source-set checks passed. The final JUnit XML reports `tests="11" skipped="0" failures="0" errors="0"`.

The affected API configuration binding test was also run from `backend-kotlin` against the final code:

```bash
./gradlew :api:test --tests 'kr.easydoc.api.ConfigurationPropertiesBindingTest' --console=plain
```

Result: exit 0, `BUILD SUCCESSFUL in 1s`; final JUnit XML reports `tests="25" skipped="0" failures="0" errors="0"`. No worker-specific configuration test matched the touched area; the shared production validation is directly covered by `PaymentConfigurationTest`.

## Safe Compose and runbook checks

No plain `docker compose config` was run. Only `.env.example` and literal non-secret `compose-fixture` values were used. The worktree initially had no `.env`; an attempt using only `--env-file .env.example` failed because `compose.yml` has `env_file: .env`:

```text
env file .../.env not found: stat .../.env: no such file or directory
```

A second attempt with a temporary `.env -> .env.example` symlink reached the profile assertions but exited 1 because the example's local `EASYDOC_WORKER_PROFILES_INCLUDE` overrode the pilot fallback. The final safe command explicitly supplied the Production include fixture, and removed the temporary symlink with a trap:

```bash
set -euo pipefail
trap 'rm -f .env' EXIT
ln -s .env.example .env
export SPRING_DATASOURCE_PASSWORD=compose-fixture
export EASYDOC_PHONE_VERIFICATION_PEPPER=compose-fixture
export EASYDOC_SENS_SERVICE_ID=compose-fixture
export EASYDOC_SENS_ACCESS_KEY=compose-fixture
export EASYDOC_SENS_SECRET_KEY=compose-fixture
export EASYDOC_SMS_FROM=01000000000
local_config="$(docker compose --env-file .env.example -f compose.yml config)"
case "$local_config" in *"--spring.profiles.active=api,local"*) ;; *) exit 1 ;; esac
case "$local_config" in *"--spring.profiles.active=worker,local"*) ;; *) exit 1 ;; esac
export EASYDOC_WORKER_PROFILES_INCLUDE=production
pilot_config="$(docker compose --env-file .env.example -f compose.yml -f compose.pilot.yml config)"
case "$pilot_config" in *"--spring.profiles.active=api,production"*) ;; *) exit 1 ;; esac
case "$pilot_config" in *"--spring.profiles.active=worker,production"*) ;; *) exit 1 ;; esac
case "$pilot_config" in *"SPRING_PROFILES_INCLUDE: production"*) ;; *) exit 1 ;; esac
case "$pilot_config" in *"--spring.profiles.active=api,local"*) exit 1 ;; *) ;; esac
case "$pilot_config" in *"--spring.profiles.active=worker,local"*) exit 1 ;; *) ;; esac
docker compose --env-file .env.example -f compose.yml config --quiet
docker compose --env-file .env.example -f compose.yml -f compose.pilot.yml config --quiet
```

Result: exit 0. Local API/worker profiles remained `local`; pilot API/worker and one-shot include profiles were `production`; local app profiles were absent from pilot; both Compose parses passed. No container was started. A final `test ! -e .env` passed, proving the temporary symlink was removed.

Runbook content inspection found the required items at `docs/pilot-runbook.md` lines 24-46: the unchanged 2026-11-01/operator gate, external HTTPS callback and credential-log prohibition, all first-charge and refund reconciliation fields, no-new-charge retry observation, and compatible fix-release rollback prohibitions.

## Final self-review

Commands run from the worktree root:

```bash
git diff --check
git diff --exit-code -- contracts/easy-doc-v1.yaml frontend/src/api
test ! -e .env
```

Result: all exited 0. `git diff --check` reported no whitespace errors; contract/frontend API diff was empty; no `.env` remained.

Manual diff review confirmed:

- `TossController` is untouched; validation still checks the same callback origin and now separately enforces a root base path.
- Existing query, fragment, and userInfo rejection remains in `parsePublicUrl`.
- Validation errors contain only profiles, property names, and fixed reasons; raw URLs and callback credentials are not interpolated.
- Ordinary domain names return before any `InetAddress` call. Strict dotted IPv4 and colon-containing IPv6 literals alone are classified, so validation performs no domain startup DNS resolution.
- Standard address predicates cover any-local, loopback, site-local, link-local, and multicast; an explicit `fc00::/7` check covers IPv6 unique-local private addresses.
- No Compose, dependency, controller, contract, frontend API, or unrelated source changes are present.

## Remaining verification boundary

The full backend build and final whole-branch verification were intentionally not run in this fix task; they remain for the controller as instructed. Actual Production deployment and real transactions remain operator-only release activities and were not attempted.
