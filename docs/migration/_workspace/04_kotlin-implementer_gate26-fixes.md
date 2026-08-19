# 게이트 26 (`04_gate25-fixes`) — Kotlin 몫 조치

**작성:** kotlin-implementer / **일자:** 2026-08-19 / **기준 HEAD:** `0ce88b4`
**입력:** `reviews/04_gate25-fixes_cross.md` §2.3·§5.2·§5.4·행 21 · `reviews/04_security-crypto-fixes_privacy-gate.md`(S-2·R-2·R-5) · `04_kotlin-implementer_crypto-fixes.md`
**범위:** `backend-kotlin/**` 만. `tests/**`·`.claude/**`·`.github/**`·`reviews/**`·`00_progress.md`·`parity/fixtures/**`·`CLAUDE.md` 무접촉.

---

## 0. 계획 (구현 착수 전에 적는다 — 프로젝트 `CLAUDE.md` 「구현 전 리서치·계획」)

### 0.1 라이브러리·프레임워크 리서치 — **기억이 아니라 이 저장소가 실제로 쓰는 산출물로 확인했다**

| 확인한 것 | 방법 | 결과 |
|---|---|---|
| Spring Boot 4.1.0 이 `EnvironmentPostProcessor` 를 `META-INF/spring.factories` 로 여전히 적재하는가 | `spring-boot-4.1.0.jar` 의 `META-INF/spring.factories` 를 풀어 읽음 | **그렇다.** 키는 `org.springframework.boot.EnvironmentPostProcessor` — Boot 3 의 `org.springframework.boot.env.*` 가 **아니다**(패키지가 옮겨졌다). 자동설정만 `.imports` 로 갔다 |
| 그 인터페이스의 시그니처 | `javap -cp spring-boot-4.1.0.jar org.springframework.boot.EnvironmentPostProcessor` | `postProcessEnvironment(ConfigurableEnvironment, org.springframework.boot.SpringApplication)` |
| `ApplicationContextRunner` 의 좌표 | `unzip -l spring-boot-test-4.1.0.jar` | `org.springframework.boot.test.context.runner.ApplicationContextRunner` (존재) |
| **서명되지 않은 JCE `Provider`** 로 `Cipher` 를 바꿔치기할 수 있는가 (조치 4 의 전제) | 툴체인과 같은 JDK(Temurin 21.0.4)에서 `javac -d out` → `java -cp out` 로 실측 | **된다.** `Security.insertProviderAt(p, 1)` 뒤 `Cipher.getInstance("AES/GCM/NoPadding").getProvider()` 가 우리 것이고 `engineDoFinal` 의 `ProviderException` 이 그대로 올라온다. 단일 파일 실행이 아니라 **디렉터리 클래스패스**에서도 같다(JceSecurity 서명 검증이 통과 조건이 아님을 실측으로 확인) |

**직접 구현하는 자리와 그 사유**(라이브러리가 주지 않는 성질):
- 프로필별 면제는 Spring 의 `@Profile` 로 표현한다 — 조건 평가를 우리가 쓰지 않는다.
- 「값이 빈 키 세대 거부」는 도메인 규칙이라 Bean Validation 으로 표현하기 어렵다(세대 간 관계·kcv 대조가 얽힌다). 기존 `verify()` 자리에 붙인다.

### 0.2 기구현 확인 — 새로 만들지 않고 재사용하는 것

| 재사용 | 위치 |
|---|---|
| KCV 계산 | `infrastructure/.../crypto/KeyCheckValue.of` — 테스트 키의 kcv 를 **제품 코드로** 계산한다. 알고리즘을 어디에도 복제하지 않는다 |
| 비밀 래퍼 | `core/.../security/Secret` |
| 기동 자기점검 케이스 형태 | `CryptoStartupVerificationTest`(직접 조립 · 실행 시점 난수 키 · 소스에 키 리터럴 없음) |
| Testcontainers DB | `infrastructure` testFixtures 의 `PostgresTestSupport` |
| 프로필 이름 `migrate` | `api` 의 `ApiApplication.kt` · `api/src/main/resources/application.yml` 의 `on-profile: migrate` · compose `kotlin-migrate` |

### 0.3 순서와 검증

1. **조치 5**(원시 제어문자 2자리) — 먼저 한다. 이 파일들이 diff 로 읽히기 시작해야 이후 조치가 리뷰된다.
2. **조치 1**(면제 스위치 제거) + 그 필연적 귀결(테스트 Spring 컨텍스트에 **진짜 키**를 준다).
3. **조치 2**(`migrate` 면제를 `@Profile("!migrate")` 로 선언하고 테스트로 고정).
4. **조치 3**(값이 빈 세대 거부).
5. **조치 4**(R-4 음성 통제).

각 조치마다 **음성 대조**(그 조치를 되돌리면 빨개지는가)를 일회용 `git worktree` 에서 실행하고 §3 에 실측을 적는다.

---

*(이하 §1~ 은 구현 후에 채운다.)*
