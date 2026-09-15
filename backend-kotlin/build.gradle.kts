import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

// 모든 모듈이 공유하는 빌드 설정. 버전은 libs.versions.toml에서 관리한다.

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

// API 계약과 골든 자료를 해당 소비 테스트의 선언 입력으로 사용한다.
val apiContractFile: File = File(rootDir.parentFile, "contracts/easy-doc-v1.yaml")
// 승인된 외부 표본을 측정할 때 문서 디렉터리만 덮어쓸 수 있다.
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
            // 컴파일 경고도 빌드 실패로 처리한다.
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
            // 실제 LLM API를 부르는 테스트는 비용 승인 후 `testLlm`으로만 실행한다.
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

        // 테스트 키는 infrastructure testFixtures가 제공한다. 암호화 기동 검증은 끄지 않는다.

        systemProperty("easydoc.golden.documents.dir", goldenDocumentsDir.absolutePath)
        systemProperty("easydoc.golden.conversions.dir", goldenConversionsDir.absolutePath)

        if (project.name == "api") {
            inputs
                .file(apiContractFile)
                .withPropertyName("apiContract")
                .withPathSensitivity(PathSensitivity.NONE)
        }

        if (project.name in setOf("core", "infrastructure")) {
            inputs
                .dir(goldenDocumentsDir)
                .withPropertyName("goldenDocuments")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            inputs
                .dir(goldenConversionsDir)
                .withPropertyName("goldenConversions")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }

        // 이 세 모듈의 테스트만 다른 모듈 소스를 직접 검사한다.
        if (project.name in setOf("core", "infrastructure", "api")) {
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

// 실행 모듈은 infrastructure를 런타임과 테스트에서만 사용한다.
val boundaryAllowedConfigurations: Map<String, Set<String>> =
    mapOf(
        "api" to setOf("runtimeOnly", "testImplementation", "testRuntimeOnly"),
        "worker" to setOf("runtimeOnly", "testImplementation", "testRuntimeOnly"),
    )
val allowedBoundaryConsumers: Set<String> = boundaryAllowedConfigurations.keys

// 각 실행 모듈 안에서 선언 종류와 compileClasspath를 함께 검사한다.
val moduleBoundaryChecks =
    boundaryAllowedConfigurations.map { (name, permitted) ->
        project(":$name").tasks.register("moduleBoundaryCheck") {
            group = "verification"
            description = "이 모듈이 infrastructure를 런타임에만 붙이는지 확인한다"
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
                            "그러면 계층 의존 방향이 사라진다."
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
                            appendLine("[$module] 모듈 의존 방향이 어긋난다 — 어댑터는 런타임에만 붙인다.")
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
