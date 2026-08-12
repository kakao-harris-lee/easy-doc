# Phase 1 — Spring Boot 4.0.7 → 4.1 계열 업그레이드

작성: kotlin-implementer / 2026-08-12
근거: 계획 §3.1("4.1 계열 후보"), 리뷰 지적 P1-1·K-3, 2026-08-12 사용자 승인 결정
범위: `backend-kotlin/` 만. `.github/workflows/ci.yml`, `.github/parity-canonical-floor.txt`,
`contracts/`, `.claude/`, `00_progress.md` 은 열지 않았다.

---

## 1. 결론

**4.1.0 으로 올렸고, Phase 1 종료 조건은 전부 그대로 유지된다.** 막힌 것은 없고,
되돌릴 이유도 발견하지 못했다. 코드 수정은 **한 줄도 필요하지 않았다** — 바뀐 파일은
version catalog 와 락파일 5개뿐이다.

| 항목 | 4.0.7 (이전) | 4.1.0 (확정) |
|---|---|---|
| `./gradlew clean build` | BUILD SUCCESSFUL / tests=75 failures=0 | **BUILD SUCCESSFUL / tests=75 failures=0** |
| ktlint / detekt | 위반 0 | **위반 0** |
| 컴파일 경고 | 0 (`allWarningsAsErrors=true`) | **0** |
| Python 게이트 | 820 passed | **820 passed** |

바꾼 파일:

```
M backend-kotlin/gradle/libs.versions.toml
M backend-kotlin/{core,application,infrastructure,api,worker}/gradle.lockfile
```

`settings-gradle.lockfile` 은 내용이 `empty=incomingCatalogForLibs0` 하나뿐이라 변화 없음.

---

## 2. 확정 버전 조합

4.1 계열의 **안정판은 4.1.0 하나뿐이다.** 추측이 아니라 Maven Central 메타데이터 실측이다.

```
$ curl -s https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/maven-metadata.xml
  ... <version>4.0.7</version>
      <version>4.1.0-M1..M4</version> <version>4.1.0-RC1</version> <version>4.1.0</version>
  <latest>4.1.0</latest>
  <release>4.1.0</release>
  <lastUpdated>20260625105751</lastUpdated>
```

4.1.1 이후는 존재하지 않는다. 4.1.0 은 전체 Boot 릴리스 기준으로도 `<latest>`·`<release>` 다.

### 2.1 우리가 실제로 쓰는 좌표

Kotlin 은 **BOM 이 정렬하는 값(`kotlin.version`)을 그대로 따랐다.** BOM 밖에서 따로 고르지 않았다.

| 좌표 | 4.0.7 | 4.1.0 | 출처 |
|---|---|---|---|
| Spring Boot | 4.0.7 | **4.1.0** | catalog `springBoot` |
| Kotlin | 2.2.21 | **2.3.21** | catalog `kotlin` = BOM `kotlin.version` |
| Flyway | 11.14.1 | **12.4.0** | BOM |
| kotlinx-serialization | 1.9.0 | **1.11.0** | BOM |
| Spring Framework | 7.0.8 | 7.0.8 | BOM (동일) |
| Jackson | 3.1.4 | 3.1.4 | BOM (동일) |
| JUnit Jupiter | 6.0.3 | 6.0.3 | BOM (동일) |
| Testcontainers | 2.0.5 | 2.0.5 | BOM (동일) |
| PostgreSQL 드라이버 | 42.7.11 | 42.7.11 | BOM (동일) |
| AssertJ | 3.27.7 | 3.27.7 | BOM (동일) |
| Gradle | 9.1.0 | 9.1.0 | wrapper (그대로) |
| Java toolchain | 21 | 21 | `jvmToolchain(21)` (그대로) |
| ktlint plugin / CLI | 14.2.0 / 1.8.0 | 14.2.0 / 1.8.0 | 그대로 (§6 참고) |
| detekt | 1.23.8 | 1.23.8 | 그대로 (§6 참고) |

**Kotlin 2.3.21 이 실제로 런타임까지 갔다는 증거**(추정 아님) — parity 자체 점검 산출물은
JVM 이 실행 중에 `KotlinVersion.CURRENT` 를 읽어 쓴다:

```
$ cat parity/_harness-selfcheck/kotlin.json
{ "runtime": "kotlin",
  "jvm": { "version": "21.0.4", "vendor": "Eclipse Adoptium", "kotlinVersion": "2.3.21" }, ... }
```

### 2.2 BOM 관리값 전체 diff 중 주목할 것

두 BOM POM 을 받아 `*.version` 프로퍼티를 전량 대조했다. 우리 클래스패스에 실제로 올라오는
것만 추리면 아래가 전부다(나머지는 Kafka·MongoDB·Elasticsearch 등 미사용 스택).

```
flyway               11.14.1 -> 12.4.0     ← 메이저. §5 에서 따로 검증
kotlin                2.2.21 -> 2.3.21     ← 마이너
kotlin-serialization   1.9.0 -> 1.11.0
micrometer            1.16.6 -> 1.17.0
mockito               5.20.0 -> 5.23.0
snakeyaml                2.5 -> 2.6
commons-lang3         3.19.0 -> 3.20.0
commons-codec         1.19.0 -> 1.21.0
byte-buddy            1.18.3 -> 1.18.10
xmlunit2              2.10.4 -> 2.11.0
```

---

## 3. 락파일 diff 요약 — 무엇이 올라갔고 위험한 것이 있는가

### 3.1 제품 클래스패스의 버전 변화

위 §2.2 목록이 그대로 반영됐고, 그 밖에 `org.springframework.boot:*` 좌표 30여 개가
4.0.7 → 4.1.0 으로 따라 올라갔다. **제품 클래스패스에서 버전이 갈린 좌표는 없다** —
`kotlin-stdlib` 은 `compileClasspath`·`testCompileClasspath`·`testFixturesCompileClasspath`·
`testRuntimeClasspath`·`productionRuntimeClasspath`·`runtimeClasspath` 전부 2.3.21 단일값이다.

Phase 1 에서 kotlinx-serialization 이 만들었던 "테스트 클래스패스의 stdlib 만 올라가는"
드리프트는 재발하지 않았다. 이번에는 BOM 이 1.11.0 을 관리하고 Kotlin 도 2.3.21 이라
같은 1.11.0 인데도 갈리지 않는다. **갈림을 막은 것은 버전을 잘 고른 것이 아니라
BOM 밖에서 고르지 않은 것이다** — 이 판단은 그대로 유지한다.

### 3.2 위험 판정: Jackson 2 가 클래스패스에서 사라졌다 (개선)

가장 큰 구조 변화다. **Flyway 11 → 12 가 Jackson 2 에서 Jackson 3 으로 갈아탔다.**

```
flyway-core 11.14.1 POM:  com.fasterxml.jackson.core:jackson-databind
flyway-core 12.4.0  POM:  tools.jackson.core:jackson-databind
```

그 결과 api·infrastructure·worker 세 모듈에서 이렇게 바뀌었다:

```
- com.fasterxml.jackson.core:jackson-core:2.21.4      (제거)
- com.fasterxml.jackson.core:jackson-databind:2.21.4  (제거)
- com.fasterxml:jackson-bom:2.21.4                    (제거)
+ tools.jackson.core:jackson-core:3.1.4               (infrastructure 신규 / worker 는 runtime 으로 확대)
```

**위험이 아니라 위험 제거다.** 4.0.7 에서는 api 의 `runtimeClasspath` 에 Jackson 2 databind 와
Jackson 3 databind 가 **동시에** 올라와 있었다(Spring Boot 4 는 Jackson 3 을 쓰고, Flyway 11 이
Jackson 2 를 끌어왔다). 직렬화 라이브러리가 두 벌 올라온 상태는 나중에 오류 본문·
`Content-Disposition` 같은 계약 지점에서 "어느 ObjectMapper 가 잡혔는가"로 번지기 쉬운 배치다.
지금은 Jackson 3 한 벌이다. `com.fasterxml.jackson.core:jackson-annotations:2.21` 만 남는데
이것은 Jackson 3 이 계속 쓰는 공용 애너테이션 아티팩트다.

`CoreModuleBoundaryTest` 가 core 클래스패스에 `com.fasterxml.jackson.databind.ObjectMapper` 와
`tools.jackson.databind.ObjectMapper` 둘 다 없음을 확인하며, 이 테스트도 그대로 통과한다.

### 3.3 위험 판정: 나머지 전이 의존성 상승

`micrometer` `mockito` `snakeyaml` `commons-lang3` `commons-codec` `byte-buddy` `xmlunit2` 는
모두 패치~마이너 상승이고 전부 BOM 이 정렬한 값이다. **Fernet·JWT·문서 파서처럼 바이트 단위
호환이 걸린 좌표는 이번 diff 에 하나도 없다**(그 라이브러리들은 Phase 3~4 에 들어온다).
위험하다고 볼 근거를 찾지 못했다.

### 3.4 락파일 재생성 방법에 대한 발견 — **증분 `--write-locks` 로는 부족하다**

이것이 이번 작업에서 locking 이 잡아낸 실질적인 문제다.

Phase 1 방식대로 `./gradlew build --write-locks` 를 기존 락파일 **위에** 돌렸더니,
`kotlinCompilerClasspath` 항목이 **2.2.21 로 남았다**:

```
org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21=kotlinCompilerClasspath,ktlint,ktlintRuleset
org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.21=kotlinBuildToolsApiClasspath
```

`dependencyInsight` 로 캐물으니 원인이 나왔다:

```
$ ./gradlew :core:dependencyInsight --configuration kotlinCompilerClasspath \
      --dependency org.jetbrains.kotlin:kotlin-compiler-embeddable

org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21
  Selection reasons:
      - By constraint: Dependency version enforced by Dependency Locking   ← 락파일이 강제
org.jetbrains.kotlin:kotlin-compiler-embeddable:{strictly 2.2.21} -> 2.2.21
org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.21 -> 2.2.21           ← 2.3.21 요청이 강등됨
```

KGP 2.3 은 `kotlinCompilerClasspath` 를 더 이상 해석하지 않는다(빌드는 build-tools API 경로로
돈다). 해석되지 않는 configuration 의 락 항목은 `--write-locks` 가 갱신하지 못하고 **그대로
남는다.** 남은 항목은 죽은 줄이 아니라 살아 있는 제약이라, 누군가 그 configuration 을 건드리는
순간 2.3.21 요청을 2.2.21 로 **강등시킨다.**

→ **조치**: 락파일 5개를 전부 지우고 `./gradlew clean build --write-locks --no-build-cache` 로
새로 만들었다. 재생성 후 `kotlinCompilerClasspath` 는 어느 락파일에도 없다.

→ **다음에 버전을 올릴 때도 같은 절차를 쓸 것**: 증분 `--write-locks` 는 계열이 바뀌는
업그레이드에서 stale 제약을 남긴다.

한 가지 함정을 더 겪었으므로 적어 둔다 — 락파일을 지운 뒤 **up-to-date 상태에서** `--write-locks`
를 돌리면 태스크가 실행되지 않아 configuration 이 해석되지 않고, 락파일이 실제보다 **비어 있게**
생성된다. `clean` + `--no-build-cache` 가 함께 있어야 한다.

### 3.5 `application` 모듈의 락 항목이 줄었다 — 4.1 탓이 아니다

재생성 후 `application/gradle.lockfile` 에서 `compileClasspath`·`kotlinBuildToolsApiClasspath`·
`kotlinCompilerPluginClasspathMain` 항목이 사라졌다(53줄 → 45줄). 업그레이드 부작용으로 보일 수
있어 **원인을 실험으로 갈랐다.**

catalog 를 4.0.7 / 2.2.21 로 되돌리고 같은 방식으로 재생성한 대조군:

```
$ (catalog = 4.0.7 / 2.2.21) rm application/gradle.lockfile
$ ./gradlew :application:build --write-locks --no-build-cache
$ grep -oE "=[a-zA-Z,]+$" application/gradle.lockfile | tr ',' '\n' | tr -d '=' | sort -u
kotlinScriptDefExtensions
ktlint
ktlintReporter
ktlintRuleset
testKotlinScriptDefExtensions      ← 4.1.0 결과와 완전히 동일
```

**결론: Boot 4.1 회귀가 아니라 재생성 방법의 차이다.** `application` 은 아직 소스가 0개라
`compileKotlin` 이 NO-SOURCE 이고, 그래서 컴파일 관련 configuration 이 해석되지 않는다.
Phase 3 에서 유스케이스 코드가 들어가면 자동으로 되돌아온다. 그 전까지 이 모듈의
`compileClasspath` 는 사실상 잠기지 않은 상태인데, 의존이 `:core` + BOM 뿐이라 노출 면적은 작다.

### 3.6 락 커버리지 밖에 있는 것 (기존 상태, 이번에 바꾸지 않음)

`dependencyLocking { lockAllConfigurations() }` 은 `subprojects {}` 안에 있어 **Gradle 플러그인
클래스패스(Kotlin·Boot·ktlint·detekt 플러그인의 전이 의존성)는 잠기지 않는다.** 플러그인 자체
버전은 version catalog 가 고정하므로 조용히 바뀌지는 않지만, 그 전이 의존성은 잠금 밖이다.
이번 업그레이드로 생긴 문제는 아니고 이번에 손대지도 않았다 — 판단이 필요하면 §9 로.

---

## 4. 이동·폐기된 좌표 확인 — **4.1 에서 추가로 옮긴 것은 없다**

Phase 1 에서 4.0 이 3.x 대비 옮긴 세 곳을 실제 빌드로 재확인했다. 셋 다 그대로다.

| Phase 1 에서 옮겼던 것 | 4.1.0 에서 | 근거 |
|---|---|---|
| `FlywayMigrationStrategy` → `spring-boot-starter-flyway` | 그대로 | `org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy` import 가 무수정 컴파일 (`FlywayBaselineGuard.kt:5`) |
| `@WebMvcTest`/MockMvc → `spring-boot-starter-webmvc-test` | 그대로 | 락파일 `spring-boot-starter-webmvc-test:4.1.0` 해석, api 테스트 45건 통과 |
| Testcontainers `org.testcontainers:postgresql` → `testcontainers-postgresql` | 그대로 | 락파일 `org.testcontainers:testcontainers-postgresql:2.0.5` |

version catalog 의 좌표 중 4.1.0 에서 해석 실패하거나 deprecated 경고를 내는 것은 없었다.
빌드 로그 전체에서 컴파일 경고 0건(`allWarningsAsErrors=true` 라 있으면 실패했을 것),
Gradle 경고는 `Deprecated Gradle features ... incompatible with Gradle 10` 한 줄뿐인데
**이것은 4.0.7 기준선 로그에도 똑같이 있던 기존 항목**이다(이번 업그레이드가 만든 것이 아니다).

---

## 5. Flyway 11 → 12 (메이저) 검증

버전 diff 중 유일한 메이저 상승이라 따로 본다. 스키마 배선이 전부 여기에 걸려 있다.

| 확인 | 결과 |
|---|---|
| 빈 DB 에 V1·V2 적용 | `ApiStartupOnEmptyDatabaseTest` 2건 통과 (`containsExactly("1","2")`) |
| 기존 Python 스냅샷에 baseline 기록 | `ApiStartupOnPythonSnapshotTest` 2건 통과 |
| `alembic_version` 불변 | 같은 테스트가 `0006` 확인 |
| `FlywayMigrationStrategy` 커스텀 전략 | `FlywayBaselineGuardTest` 4건 전부 통과 |
| V1 ≡ Alembic 0001~0006 지문 | `PythonSchemaBaselineTest` 4건 전부 통과 |
| **Flyway 11 이 쓴 장부를 12 가 읽는가** | compose 실측 — 기존 볼륨의 `flyway_schema_history`(11.14.1 작성)를 12.4.0 이 `Successfully validated 2 migrations` 로 수용. 체크섬 재계산 요구 없음 |

마지막 항목은 테스트로는 안 나오는 것이라 컨테이너 로그로 확인했다:

```
org.flywaydb.core.internal.command.DbValidate : Successfully validated 2 migrations (execution time 00:00.008s)
org.flywaydb.core.internal.command.DbMigrate  : Current version of schema "public": 2
org.flywaydb.core.internal.command.DbMigrate  : Schema "public" is up to date. No migration necessary.
kr.easydoc.infrastructure.db.FlywayBaselineGuard : Flyway 마이그레이션 완료: applied=0 targetSchemaVersion=(변경 없음)
```

---

## 6. detekt · ktlint 호환성 판단

### 6.1 사실 정정

지시문에는 "detekt 1.23.8 은 Kotlin **1.9** 파서를 내장한다"고 되어 있는데, 실측은 다르다.
detekt 1.23.8 의 `detekt-parser` POM 이 의존하는 것은:

```
org.jetbrains.kotlin:kotlin-compiler-embeddable  2.0.21
```

즉 **Kotlin 2.0 파서**다. 우리가 컴파일하는 2.3.21 과의 간격은 1.9 대비 훨씬 좁다.

같은 종류의 간격이 ktlint 에도 있다 — 이쪽은 Phase 1 문서에 언급이 없었다:

```
ktlint-cli 1.8.0 → kotlin-compiler-embeddable 2.2.21  (락파일 실측: ktlint,ktlintRuleset)
```

4.0.7 시절에는 ktlint 의 2.2.21 이 우리 컴파일러와 **우연히 같아서** 눈에 띄지 않았다.
2.3.21 로 올리면서 갈렸다. 이것도 함께 기록해 둔다.

### 6.2 판단: **둘 다 그대로 둔다 (1.23.8 / 1.8.0)**

- **파싱 오류는 나지 않는다.** `./gradlew clean build` 에서 `core`·`infrastructure`·`api`·`worker`
  네 모듈의 `detekt` 태스크가 전부 실행되어 `Total: 0`, ktlint 보고서는 전부 0바이트다.
  기동 실패·경고·예외 없음. 현재 소스가 Kotlin 2.3 신문법을 쓰지 않기 때문이다.
- **detekt 2.x 로 올릴 수 없다.** 2026-08-12 실측으로 안정판이 없다. 좌표가
  `io.gitlab.arturbosch.detekt` → `dev.detekt` 로 바뀌었고 `dev.detekt` 쪽에는
  `2.0.0-alpha.0` ~ `alpha.6` 만 있다. 품질 게이트를 alpha 에 걸면 게이트가 깨졌을 때
  "우리 코드가 나쁜가, 게이트가 미완성인가"를 가릴 수 없다 — 게이트로서 기능을 잃는다.
- **ktlint 1.8.0 이 최신이다.** CLI 1.8.0 / Gradle 플러그인 14.2.0 모두 그 이상이 없다.
  올릴 여지 자체가 없다.

### 6.3 남는 위험과 그 성질

임베디드 파서가 2.0.21·2.2.21 이므로 **Kotlin 2.3 에서 새로 생긴 문법을 쓰면 그때 파싱에
실패한다.** 성질을 분명히 해 둔다:

- **조용히 틀리는 종류가 아니다.** 파싱 실패는 태스크 실패로 드러나므로 잘못된 통과를 만들지
  않는다. 계약·parity·보안 불변식에 영향을 주는 축이 아니다.
- **지금 대응할 것이 없다.** detekt 는 올릴 곳이 없고 ktlint 는 이미 최신이다. 미리 할 수 있는
  유일한 조치는 "2.3 신문법을 쓰지 않는다"인데, 포팅 단계의 코드가 그런 문법을 필요로 할 이유가
  없다(§4.6 동등 포팅 우선).
- **트리거는 명확하다.** detekt/ktlint 태스크가 파싱 오류를 내면 그때 판단한다. 선택지는
  ① 해당 문법 회피, ② detekt 2.x 안정판 대기, ③ 해당 파일만 게이트 예외 — 셋 다 그 시점에
  근거를 갖고 고르는 편이 낫다.

이 내용은 `libs.versions.toml` 의 `[versions]` 주석에도 남겼다 — 다음에 이 값을 만지는 사람이
문서를 찾아가지 않아도 되게.

---

## 7. 문서 spike 조합(POI·PDFBox) — 해석·컴파일만 확인

지시대로 **문서 spike 전체를 다시 돌리지 않았다.** 요구된 범위인 "좌표 해석·컴파일" 만 봤다.
`backend-kotlin/` 을 오염시키지 않으려고 별도 scratch Gradle 프로젝트에서 확인했다.

조건: Kotlin 2.3.21 + Boot 4.1.0 BOM + POI 5.4.1 + PDFBox 3.0.5 + commons-compress 1.27.1

```
DEP org.apache.poi:poi:5.4.1                DEP org.apache.pdfbox:pdfbox:3.0.5
DEP org.apache.poi:poi-ooxml:5.4.1          DEP org.apache.pdfbox:pdfbox-io:3.0.5
DEP org.apache.poi:poi-ooxml-lite:5.4.1     DEP org.apache.pdfbox:fontbox:3.0.5
DEP org.apache.xmlbeans:xmlbeans:5.3.0      DEP org.apache.commons:commons-compress:1.27.1
DEP org.jetbrains.kotlin:kotlin-stdlib:2.3.21
```

Phase 0 spike 표(§238~253)와 **버전이 전부 일치**한다. 그리고 spike 가 쓴 진입점들
(`XWPFDocument`, `Loader.loadPDF`, `PDFTextStripper`, commons-compress `ZipFile` +
`SeekableInMemoryByteChannel`)을 호출하는 Kotlin 파일이 2.3.21 로 **컴파일된다**
(`build/classes/kotlin/main/Probe.class` 생성, exit 0).

BOM 이 정렬하는 값이 두 개 섞여 들어온다는 점은 적어 둔다 — `commons-codec 1.21.0`,
`commons-lang3 3.20.0` 은 POI 가 요구하는 값이 아니라 Boot 4.1.0 BOM 값이다(4.0.7 이었다면
1.19.0 / 3.19.0). 지금은 해석·컴파일에 문제가 없지만 런타임 동작까지 보증하지는 않는다.

> **Phase 4 착수 전 필수**: 이 조합(Kotlin 2.3.21 / POI 5.4.1 / PDFBox 3.0.5 /
> commons-compress 1.27.1 / Boot 4.1.0 BOM)으로 Phase 0 문서 spike 의 **DOCX 동등성 7항목을
> 다시 확인해야 한다.** 원 spike 는 Kotlin 2.2.0 위에서 통과한 것이고, 여기서 확인한 것은
> 좌표 해석과 컴파일뿐이다. 이 문장은 `libs.versions.toml` 주석에도 남겼다.

---

## 8. 검증 결과 전부

전부 실제로 돌렸다. 명령과 출력을 그대로 옮긴다.

### 8-1. 기준선 확보 (업그레이드 **전**, 4.0.7)

```
$ ./gradlew clean build
BUILD SUCCESSFUL
core: tests=19 failures=0 errors=0   infrastructure: tests=8 failures=0 errors=0
api:  tests=45 failures=0 errors=0   worker:         tests=3 failures=0 errors=0
TOTAL: tests=75 failures=0 errors=0
```

### 8-2. `./gradlew clean build` (4.1.0)

```
$ ./gradlew clean build
BUILD SUCCESSFUL in 12s / 72 actionable tasks
TOTAL: tests=75 failures=0 errors=0 skipped=0
```

락파일을 다시 쓰지 않는 **검증 모드**로 돌린 결과이며, 실행 전후 락파일 md5 가 동일하다
(= CI 가 `--write-locks` 없이 돌아도 드리프트로 실패하지 않는다).

### 8-3. ktlint · detekt 위반 0

```
$ grep -oE "Total: [0-9]+" */build/reports/detekt/detekt.html
core: Total: 0   infrastructure: Total: 0   api: Total: 0   worker: Total: 0

$ (ktlint 보고서 중 비어 있지 않은 파일 수)
0 개
```

`:application` 은 소스가 없어 `detekt NO-SOURCE` / `ktlint SKIPPED` 다.

### 8-4. 빈 DB 경로

`ApiStartupOnEmptyDatabaseTest` (Testcontainers 실제 PostgreSQL, `@SpringBootTest(RANDOM_PORT)`,
JDK `HttpClient` 로 실제 소켓 호출):

```
ok  빈 DB 에서 기동하고 /health 가 200 ok 를 돌려준다     ← 본문 {"status":"ok"} 동일성 검사
ok  Flyway 가 V1·V2 를 적용했다                          ← containsExactly("1","2")
```

### 8-5. 기존 스냅샷 경로

`ApiStartupOnPythonSnapshotTest`:

```
ok  기존 Python 스키마 위에서 기동하고 /health 가 200 ok 를 돌려준다
ok  baseline 이 기록되고 alembic_version 은 그대로다      ← 장부 [1,2] + alembic_version='0006'
```

`FlywayBaselineGuardTest` 4건 · `PythonSchemaBaselineTest` 4건도 전부 통과 (§5 표 참고).

### 8-6. compose 실측

```
$ docker compose --profile kotlin up -d --build --force-recreate kotlin-migrate kotlin-api kotlin-worker
EXIT=0   Image easy-doc-kotlin:local Built

$ docker inspect easy-doc-kotlin-migrate-1 --format '{{.State.Status}} exit={{.State.ExitCode}}'
exited exit=0

$ docker compose ps -a
easy-doc-kotlin-api-1        Up 20 seconds (healthy)
easy-doc-kotlin-migrate-1    Exited (0) 20 seconds ago
easy-doc-kotlin-worker-1     Up 20 seconds
easy-doc-api-1               Up 2 hours (healthy)      ← Python, 동시 기동
easy-doc-postgres-1          Up 2 hours (healthy)

$ curl -i http://127.0.0.1:8100/health     → HTTP/1.1 200  {"status":"ok"}   (Kotlin)
$ curl -i http://127.0.0.1:8000/health     → HTTP/1.1 200  {"status":"ok"}   (Python)
```

컨테이너 이미지는 락파일을 복사해 컨테이너 안에서 다시 빌드하므로, 이 성공은
**재생성된 락파일이 호스트 밖에서도 해석된다**는 확인을 겸한다.

### 8-7. C-1 회귀 — 세 케이스 모두 500 으로 돌아가지 않았다

단위 테스트(`FrameworkErrorContractTest` 9건)와 **실행 중인 컨테이너** 양쪽에서 확인했다.

```
$ curl -s -w "status=%{http_code}" http://127.0.0.1:8100/nope
status=404   {"detail":"Not Found"}

$ curl -s -X POST -D- http://127.0.0.1:8100/health
status=405   Allow: GET   {"detail":"Method Not Allowed"}

$ curl -s -H "Accept: application/xml" -w "status=%{http_code}" http://127.0.0.1:8100/health
status=200   {"status":"ok"}
```

### 8-8. CORS 회귀 — preflight·실요청 양쪽

`CorsContractTest` 10건 통과 + 컨테이너 실측:

```
$ curl -i -X OPTIONS http://127.0.0.1:8100/health \
      -H "Origin: http://localhost:5173" -H "Access-Control-Request-Method: GET"
HTTP/1.1 200
Access-Control-Allow-Origin: http://localhost:5173
Access-Control-Allow-Methods: GET,POST,PUT,PATCH,DELETE
Access-Control-Expose-Headers: Content-Disposition, Location      ← 둘 다 있음
Access-Control-Max-Age: 600

$ curl -i http://127.0.0.1:8100/health -H "Origin: http://localhost:5173"
HTTP/1.1 200
Access-Control-Allow-Origin: http://localhost:5173
Access-Control-Expose-Headers: Content-Disposition, Location      ← 둘 다 있음
```

### 8-9. parity 하네스

```
$ ./gradlew parityHarness
> Task :core:parityHarness
parity 선언 0개 — 포팅을 끝냈다고 선언한 도메인이 없다. (= 통과가 아니라 '검증 대상 없음')
BUILD SUCCESSFUL

$ cat parity/_harness-selfcheck/kotlin.json
{ "runtime": "kotlin",
  "purpose": "Phase 1 배선 증명 전용. 게이트 판정에 쓰지 않는다.",
  "jvm": { "version": "21.0.4", "vendor": "Eclipse Adoptium", "kotlinVersion": "2.3.21" },
  "domainsDeclaredIn": "backend-kotlin/parity-domains.txt" }
```

`runtime: kotlin` 확인. `parityHarness` 실행 결과 `tests=1 failures=0`
(`@Tag("parity")` 가 붙은 자체 점검 1건). 산출물은 게이트 디렉터리 `parity/actual/` 의
**형제**에 쓰이므로 `parityManifestCheck` 의 도메인 집합과 섞이지 않는다 — 설계대로다.

### 8-10. Python 게이트 무손상

`app/` 은 한 줄도 건드리지 않았다.

```
$ uv run ruff check .   → All checks passed!
$ uv run mypy .         → Success: no issues found in 116 source files
$ uv run pytest         → 820 passed, 68 skipped, 4 deselected, 1 warning in 5.57s
```

---

## 9. 실패했거나 미검증인 항목

**실패한 항목은 없다.** 아래는 "돌리지 않았거나 이 작업의 범위 밖"인 것들이다 — 됐다고 적지 않는다.

| 항목 | 상태 | 사유 |
|---|---|---|
| 문서 spike DOCX 동등성 7항목 | **미실행** | 지시대로 다시 돌리지 않았다. 좌표 해석·컴파일만 확인(§7). **Phase 4 착수 전 재확인 필요** |
| HWPX/PDF 추출 실동작 | **미실행** | 위와 같음. Phase 4 |
| `@pytest.mark.llm` / `@Tag("llm")` | **미실행** | 기본 실행에서 제외되는 태그. 이번 업그레이드가 건드리는 영역 아님 |
| `uv run pytest tests/golden` | **미실행** | 프롬프트·스타일 규칙을 건드리지 않았다(버전만 변경). 전체 `pytest` 820건에 포함되는 규칙 기반 골든 검사는 통과 |
| CI 워크플로에서의 재현 | **미실행** | `.github/workflows/ci.yml` 은 열지 말라는 지시. 로컬에서 CI 와 같은 명령을 돌렸을 뿐이고, **CI 러너에서의 확인은 남아 있다** |
| `application` 모듈 `compileClasspath` 잠금 | **미적용** | 소스 0개라 해석되지 않는다(§3.5). Phase 3 에 자동 복구 |
| Gradle 플러그인 클래스패스 잠금 | **범위 밖** | 기존 상태. 이번에 바꾸지 않았다(§3.6) |
| configuration cache | **범위 밖** | `gradle.properties` 에서 계속 off. 이번 업그레이드가 이유가 아님 |

### 판단을 요청하는 것 (코드에 넣지 않았다)

1. **`resolveAndLockAll` 태스크 도입** — §3.4 의 stale 제약과 §3.5 의 커버리지 구멍을 한 번에
   막는 Gradle 공식 방법이다. 다만 락파일 내용이 크게 늘고 빌드 스크립트가 바뀌므로,
   버전 업그레이드와 섞지 않고 별건으로 남긴다.
2. **Gradle 플러그인 클래스패스 locking** — §3.6.
3. **Gradle 10 호환 경고 해소** — 기존 경고이고 Gradle 9.1.0 에서는 무해하다. 별건.

이 세 개는 리더가 명시적으로 승인하기 전까지 코드에 넣지 않는다.

---

## 10. 되돌리기 판단

**되돌릴 이유를 찾지 못했다.** 근거:

- 코드 수정이 0줄이다. 이동·폐기된 좌표가 없어 소스가 그대로 컴파일된다.
- 테스트 75건이 그대로 통과하고, 실패한 검증 항목이 없다.
- 유일한 메이저 상승(Flyway 12)은 §5 에서 별도로 검증했고, **Flyway 11 이 만든 기존 장부를
  12 가 그대로 수용**한다는 것까지 실측했다.
- Jackson 2/3 이중 적재가 사라져 클래스패스가 오히려 단순해졌다(§3.2).

되돌린다면 비용은 지금이 가장 싸다는 판단은 유효하지만, 되돌릴 근거가 없다.

되돌려야 할 상황이 온다면 절차는: `libs.versions.toml` 의 `kotlin`·`springBoot` 두 값을
`2.2.21`·`4.0.7` 로 되돌리고, 락파일 5개를 지운 뒤 `./gradlew clean build --write-locks
--no-build-cache` 를 돌린다(§3.4 의 함정 때문에 증분 재생성은 쓰지 않는다). 이 절차는
§3.5 의 대조 실험에서 실제로 한 번 수행해 동작을 확인했다.
