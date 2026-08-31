import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

// 다섯 모듈이 공유하는 설정. 버전은 여기 적지 않는다 — gradle/libs.versions.toml 한 곳이다.

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

// 계약 정본. 테스트가 실행 시점에 시스템 속성으로 경로를 만들어 읽으므로 Gradle 의 입력
// 지문에 잡히지 않았고, 그래서 **계약만 바꾼 변경이 계약 테스트를 한 번도 돌리지 않고**
// UP-TO-DATE / FROM-CACHE 로 초록이 됐다(contract-keeper 6ece404 §5 실측).
// X-J2 가 요구한 「계약 값을 바꾸면 테스트가 깨진다」는 테스트가 돌 때만 참이므로,
// 파일을 선언 입력으로 걸어 그 전제를 빌드가 지키게 한다.
val apiContractFile: File = File(rootDir.parentFile, "contracts/easy-doc-v1.yaml")
// `-Peasydoc.golden.documents.dir=<절대경로>` 로 덮어쓸 수 있다 — 게이트 ⓪ 측정처럼
// 승인 코퍼스 밖 표본을 레인으로 잴 때 쓴다. 로더(GoldenDocumentLoader)는 이미 같은
// 이름의 시스템 속성을 읽으므로, 여기는 그 속성에 실을 값을 고르는 것뿐이다.
val goldenDocumentsDir: File =
    (findProperty("easydoc.golden.documents.dir") as String?)?.let(::File)
        ?: File(rootDir.parentFile, "data/golden/documents")
val goldenConversionsDir: File = File(rootDir.parentFile, "data/golden/conversions")

allprojects {
    group = "kr.easydoc"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    // --- toolchain ----------------------------------------------------------
    // 로컬에 설치된 JDK가 무엇이든 Java 21로 컴파일·실행한다. 이것이 없으면
    // 개발자 기계마다 다른 바이트코드가 나오고 "내 컴퓨터에선 되는데"가 시작된다.
    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
        compilerOptions {
            // 경고를 남긴 채 통과로 보고하지 않는다 (계획 §6 Build 게이트).
            allWarningsAsErrors.set(true)
        }
    }

    // --- dependency locking -------------------------------------------------
    // 전이 의존성이 조용히 올라가면 Fernet·JWT·문서 파서처럼 바이트 단위 호환이 걸린
    // 지점에서 재현 불가능한 차이가 난다. 락파일은 "어제는 통과했는데 오늘 실패"의
    // 원인 후보에서 의존성 드리프트를 제거한다.
    dependencyLocking {
        lockAllConfigurations()
    }

    dependencies {
        add("detektPlugins", rootProject.libs.detekt.formatting)
    }

    // --- 품질 게이트 --------------------------------------------------------
    extensions.configure<KtlintExtension> {
        version.set(
            rootProject.libs.versions.ktlintCli
                .get(),
        )
        // 규칙의 정본은 backend-kotlin/.editorconfig 다.
    }

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        parallel = true
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = "21"
        reports {
            html.required.set(true)
            xml.required.set(false)
            sarif.required.set(false)
            md.required.set(false)
        }
    }

    // --- 테스트 -------------------------------------------------------------
    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            // 실제 LLM API를 부르는 테스트는 기본 실행에서 뺀다
            // (Python의 @pytest.mark.llm 에 대응). 비용 승인 후에만 `testLlm` 으로 연다.
            if (name == "testLlm") {
                includeTags("llm")
            } else {
                excludeTags("llm")
            }
        }
        testLogging {
            events("failed", "skipped")
            exceptionFormat = TestExceptionFormat.FULL
        }
        // Testcontainers 컨테이너 재사용 — 테스트 클래스마다 새 PostgreSQL을 띄우면
        // 전체 실행이 분 단위로 늘어난다.
        systemProperty("testcontainers.reuse.enable", "true")

        // 소스 전수를 훑는 탐지기(허용목록 가드 등)가 쓰는 루트. 테스트 작업 디렉터리는
        // 모듈 디렉터리라, 코드에서 상대 경로로 거슬러 올라가면 모듈이 늘 때 조용히 어긋난다.
        systemProperty("easydoc.kotlin.source.root", rootDir.absolutePath)

        // 저장 암호화 기동 자기점검을 끄는 시스템 속성이 **여기 없다.** 게이트 26 조치 1로
        // 그 프로퍼티(`easydoc.encryption.verify-on-startup`) 자체를 없앴다 — 평범한
        // `@ConfigurationProperties` 필드라 JVM `-D`·환경변수·`SPRING_APPLICATION_JSON`·
        // 저장소 밖 매니페스트에서 배포 시점에 끌 수 있었고, 그것을 지키던 탐지기는
        // 저장소 안 파일만 훑어 그 경로 어디에도 닿지 못했다(codex A-1).
        //
        // 테스트 Spring 컨텍스트는 이제 자기점검을 **끄지 않고 지난다** — infrastructure
        // testFixtures 의 `TestEncryptionKeys` 가 실행 시점 난수 키와 그 검사값을
        // 넣어 준다. 여기 그 스위치를 다시 더하지 마라.

        // 계약 파일을 **선언 입력**으로 건다. 지금은 api 만 읽지만 모든 테스트 태스크에
        // 거는 이유는 도달 범위다 — 다음 모듈이 계약을 읽기 시작할 때 이 선언을 함께
        // 옮겨 적어야 한다면, 옮겨 적지 않은 채로 같은 결함이 되살아난다.
        // 경로 민감도를 NONE 으로 둔다: 절대 경로가 지문에 들어가면 기계가 다른 CI 에서
        // 빌드 캐시가 재사용되지 않아, 캐시를 끄는 것과 같아진다.
        systemProperty("easydoc.golden.documents.dir", goldenDocumentsDir.absolutePath)
        systemProperty("easydoc.golden.conversions.dir", goldenConversionsDir.absolutePath)

        inputs
            .file(apiContractFile)
            .withPropertyName("apiContract")
            .withPathSensitivity(PathSensitivity.NONE)

        inputs
            .dir(goldenDocumentsDir)
            .withPropertyName("goldenDocuments")
            .withPathSensitivity(PathSensitivity.RELATIVE)

        inputs
            .dir(goldenConversionsDir)
            .withPropertyName("goldenConversions")
            .withPathSensitivity(PathSensitivity.RELATIVE)

        // 소스 전수를 훑는 탐지기가 **실행 시점에 읽는 것**을 선언 입력으로 건다 (β-02).
        //
        // 위 계약 파일만 걸어 두어 **비대칭**이었다. 같은 블록이 `easydoc.kotlin.source.root`
        // 로 rootDir 을 넘겨 주는데, 그 루트 아래 파일들은 선언 입력이 아니었다 — 실행
        // 시점에 읽는 파일이 바뀌어도 탐지기가 다시 돌지 않고 UP-TO-DATE 로 끝날 수 있다.
        //
        // 왜 `inputs.dir(rootDir)` 이 아닌가: 그러면 `build/` 산출물이 입력에 들어가
        // 순환이 생기고 매 실행이 out-of-date 가 된다. 그래서 **소스 트리만** 건다.
        // 모듈 자기 소스는 이미 컴파일 산출물로 입력이지만, 다른 모듈의 소스는
        // 아니었다 — 그 자리가 이 선언의 값이다.
        //
        // 비용: 어느 모듈의 소스를 고쳐도 모든 테스트 태스크가 다시 돈다. CI 는 매번 새
        // 체크아웃이라 추가 비용이 0 이고, 로컬에서는 「스캐너가 재는 것이 바뀌면 스캐너가
        // 돈다」를 사는 값이다.
        inputs
            .files(
                rootProject.fileTree(rootDir) {
                    include("**/src/**")
                    exclude("**/build/**")
                    exclude("**/.gradle/**")
                },
            ).withPropertyName("scannedSourceTree")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }

    tasks.register<Test>("testLlm") {
        group = "verification"
        description =
            "LLM-as-judge opt-in 레인. 비밀값이 없으면 skip 하고, 있으면 유료 호출로 골든 변환을 채점한다."
        val testSourceSet =
            project.extensions
                .getByType<JavaPluginExtension>()
                .sourceSets
                .getByName("test")
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        failOnNoDiscoveredTests = false

        // 레인 요약(무엇으로 쟀는지·통과/실패·인프라 흔들림)은 stdout 으로 나온다. 기본 로깅은
        // failed·skipped 만 찍어, **통과한 실행의 측정값이 어디에도 남지 않았다.**
        testLogging.showStandardStreams = true

        // 이 태스크는 **재는 행위**다. 소스가 그대로여도 다시 재야 한다 —
        // 다른 `EASYDOC_LLM_*`·키로 다시 돌린 실행이 UP-TO-DATE 로 건너뛰면, 돌리지 않은 값을
        // 돌린 값으로 읽게 된다. 환경변수는 Gradle 입력 지문에 잡히지 않으므로 여기서 끈다.
        outputs.upToDateWhen { false }
    }
}

// ── 모듈 의존 방향 강제 (Phase 3 착수 전 · 2회차 codex #5) ────────────────────────────
//
// `CoreModuleBoundaryTest` 는 **core 의 테스트 런타임에 클래스가 있는가**만 본다. 그래서
// 두 가지가 통째로 빠져 있었다.
//
//   1. `compileOnly` 로 넣으면 런타임에 없으므로 통과한다.
//   2. 그 목록에 없는 타입이면 무엇이든 통과한다.
//   3. **`api`·`worker` 가 `infrastructure` 를 어떻게 붙이는지는 아무도 안 본다** —
//      `runtimeOnly` 를 `implementation` 으로 바꾸는 **한 글자 변경**에 깨지는 테스트가
//      0건이었다. 그 순간 api 소스가 infrastructure 타입을 직접 import 할 수 있게 되고,
//      계획 §3.2 가 정한 의존 방향(어댑터는 런타임에만 붙는다)이 조용히 사라진다.
//
// 클래스 존재 검사로는 3번을 볼 수 없다 — 런타임에는 **어느 쪽이든 있기 때문**이다.
// 그래서 판정 대상을 클래스패스가 아니라 **Gradle configuration 자체**로 옮긴다.
//
// 두 축을 함께 본다. 하나만으로는 닫히지 않는다.
//   ⓐ **선언 종류** — `:infrastructure` 를 선언한 configuration 이 허용 목록 안인가.
//      선언을 읽는 것이라 해석(resolve)이 필요 없고, 빌드 순서에 영향을 주지 않는다.
//   ⓑ **compileClasspath 부재** — 실제로 컴파일 시점에 안 보이는가. ⓐ 를 우회하는 경로
//      (다른 모듈을 통한 전이 노출 등)를 여기서 잡는다.
//
// `check` 에 걸어 두므로 `./gradlew build` 가 이 판정을 지난다.

// 소비 모듈 → 그 모듈이 `:infrastructure` 를 선언해도 되는 configuration.
//
// `testImplementation` 이 허용인 이유: 테스트는 어댑터의 test fixture 를 직접 쓴다.
// 그것은 프로덕션 의존 방향과 무관하고, 막으면 Testcontainers 테스트가 불가능해진다.
val boundaryAllowedConfigurations: Map<String, Set<String>> =
    mapOf(
        "api" to setOf("runtimeOnly", "testImplementation", "testRuntimeOnly"),
        "worker" to setOf("runtimeOnly", "testImplementation", "testRuntimeOnly"),
    )
val allowedBoundaryConsumers: Set<String> = boundaryAllowedConfigurations.keys

// 판정은 **각 소비 모듈 안에서** 돈다. 다른 프로젝트의 configuration 을 바깥 태스크에서
// 해석하면 Gradle 이 "exclusive lock 없이 해석했다"로 거부한다(실측). 자기 것을 자기가
// 해석하면 그 문제가 없고, 모듈 하나만 빌드해도 그 모듈의 판정이 함께 돈다.
val moduleBoundaryChecks =
    boundaryAllowedConfigurations.map { (name, permitted) ->
        project(":$name").tasks.register("moduleBoundaryCheck") {
            group = "verification"
            description = "이 모듈이 infrastructure 를 런타임에만 붙이는지 확인한다 (계획 §3.2)"
            outputs.upToDateWhen { false }

            val module = name
            val declarations =
                project.configurations
                    .filter { it.dependencies.isNotEmpty() }
                    .associate { configuration ->
                        configuration.name to
                            configuration.dependencies
                                .filterIsInstance<ProjectDependency>()
                                .map { it.path }
                                .toSet()
                    }
            // 해석 결과를 **입력으로 선언**한다. `dependsOn` 없이 실행 중에 물으면
            // "`:application:jar` 가 끝나기 전에 mapped value 를 물었다" 로 거부된다(실측) —
            // 클래스패스 해석이 상류 모듈의 jar 를 요구하기 때문이다. 파일 컬렉션을 입력으로
            // 걸면 Gradle 이 그 산출을 먼저 만들어 준다.
            val compileClasspath = project.configurations.named("compileClasspath")
            inputs.files(compileClasspath)
            val compileIds =
                compileClasspath
                    .flatMap { it.incoming.artifacts.resolvedArtifacts }
                    .map { artifacts -> artifacts.map { it.id.componentIdentifier.displayName } }

            doLast {
                val problems = mutableListOf<String>()

                declarations
                    .filterValues { ":infrastructure" in it }
                    .keys
                    .filterNot { it in permitted }
                    .sorted()
                    .forEach { configuration ->
                        problems +=
                            "  - `:infrastructure` 를 `$configuration` 으로 선언했다. " +
                            "허용: ${permitted.sorted().joinToString(", ")}. " +
                            "컴파일 시점에 보이면 $module 소스가 어댑터 타입을 직접 import 할 수 있고, " +
                            "그러면 계획 §3.2 의 의존 방향이 사라진다."
                    }

                if (compileIds.get().any { it == "project :infrastructure" }) {
                    problems +=
                        "  - compileClasspath 에 infrastructure 가 있다. 선언 종류를 고쳐도 " +
                        "다른 모듈이 전이로 노출하고 있을 수 있다 — " +
                        "`./gradlew :$module:dependencies --configuration compileClasspath` 로 경로를 확인하라."
                }

                if (problems.isNotEmpty()) {
                    error(
                        buildString {
                            appendLine("[$module] 모듈 의존 방향이 어긋난다 (계획 §3.2 — 어댑터는 런타임에만 붙는다).")
                            problems.forEach { appendLine(it) }
                        },
                    )
                }
                logger.lifecycle("[$module] 모듈 경계 확인: 선언 종류 + compileClasspath 양쪽 통과.")
            }
        }
    }

boundaryAllowedConfigurations.keys.forEach { name ->
    project(":$name").tasks.named("check") { dependsOn("moduleBoundaryCheck") }
}

tasks.register("moduleBoundaryCheck") {
    group = "verification"
    description = "api·worker 의 모듈 경계 판정을 한 번에 돌린다"
    dependsOn(moduleBoundaryChecks)
}

tasks.register("testLlm") {
    group = "verification"
    description = "LLM-as-judge opt-in 레인을 모든 모듈에서 연다. 비밀값과 비용 승인 후에만 실행한다."
    dependsOn(subprojects.map { it.tasks.named("testLlm") })
}
