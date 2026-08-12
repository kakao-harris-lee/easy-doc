import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
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

// parity 산출물의 저장소 루트 기준 경로. backend-kotlin/ 이 Gradle 루트이므로 한 단계 위다.
val parityActualDir: File = File(rootDir.parentFile, "parity/actual")

// Kotlin 이 포팅을 끝냈다고 선언한 parity 도메인 목록. 파일 자체의 설명은 그 안에 있다.
val parityDomainsFile: File = rootProject.file("parity-domains.txt")

/** 선언 파일을 읽어 도메인 이름만 남긴다 (`#` 뒤 주석·빈 줄 제거). */
fun declaredParityDomains(file: File): List<String> =
    file
        .readLines()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()

// 지난 실행의 산출물을 지운다. 이것이 없으면 Kotlin 쪽 parity 테스트를 지워도 예전
// 산출물이 parity/actual/ 에 남아 게이트가 계속 통과한다 — "무엇을 이번에 실제로
// 만들었는가"가 아래 parityManifestCheck 판정의 입력이므로 선행 조건이다.
val parityActualClean =
    tasks.register("parityActualClean") {
        group = "verification"
        description = "parity/actual/ 을 비운다 (지난 실행의 산출물이 게이트를 통과시키지 못하게)"
        val target = parityActualDir
        outputs.upToDateWhen { false }
        doLast {
            if (target.exists()) {
                check(target.deleteRecursively()) { "parity/actual 을 지우지 못했다: $target" }
            }
            check(target.mkdirs() || target.isDirectory) { "parity/actual 을 만들지 못했다: $target" }
        }
    }

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
            // (Python의 @pytest.mark.llm 에 대응). 비용 승인 후에만 돌린다.
            excludeTags("llm")
        }
        testLogging {
            events("failed", "skipped")
            exceptionFormat = TestExceptionFormat.FULL
        }
        // Testcontainers 컨테이너 재사용 — 테스트 클래스마다 새 PostgreSQL을 띄우면
        // 전체 실행이 분 단위로 늘어난다.
        systemProperty("testcontainers.reuse.enable", "true")
    }

    // 일반 `test` 는 게이트 디렉터리를 건드리지 않는다. parity 산출물은 모듈 build/ 안에
    // 버려지고, 저장소 루트 parity/actual/ 은 아래 parityHarness 태스크만 쓴다.
    tasks.named<Test>("test") {
        systemProperty(
            "parity.actual.dir",
            layout.buildDirectory
                .dir("parity-actual")
                .get()
                .asFile.absolutePath,
        )
    }

    // parity 게이트용 산출 태스크. `parity/actual/{도메인}` 아래 json 산출물 을 **Kotlin 테스트가**
    // 쓴다는 것을 보장하는 유일한 장치다 (Phase 0 필수 조치 E,
    // python-kotlin-parity 스킬 "이 게이트가 막지 못하는 것" 참고).
    val sourceSets = extensions.getByType<SourceSetContainer>()
    tasks.register<Test>("parityHarness") {
        group = "verification"
        description = "parity 산출물을 저장소 루트 parity/actual/ 에 쓴다 (@Tag(\"parity\") 테스트만)"
        dependsOn(parityActualClean)
        testClassesDirs = sourceSets.getByName("test").output.classesDirs
        classpath = sourceSets.getByName("test").runtimeClasspath
        useJUnitPlatform {
            includeTags("parity")
            excludeTags("llm")
        }
        systemProperty("parity.actual.dir", parityActualDir.absolutePath)
        // 입력이 같아도 매번 다시 써야 게이트가 최신 산출물을 본다.
        outputs.upToDateWhen { false }
    }
}

// --- 선언 ↔ 실행 대조 -------------------------------------------------------
// parity-domains.txt 는 손으로 적는 선언이라 그 자체로는 아무것도 보장하지 않는다.
// 이 태스크가 선언을 **이번 실행이 실제로 만든 산출물**과 양방향으로 대조해서 의미를
// 준다. 두 방향 모두 막아야 하는 이유는 다르다.
//
//   선언 O / 산출 X — "포팅했다"고 적어 두고 산출물이 없는 상태. 이것을 여기서 막지
//       않으면 CI 비교 단계가 그 도메인을 "결과 파일 없음"으로 잡아 주기는 하지만,
//       그 단계에는 부분 검증 사면이 걸려 있다. **탐지와 사면을 같은 단계에 두지
//       않는 것**이 이 태스크가 별도 단계로 존재하는 이유다.
//   선언 X / 산출 O — CI 비교는 선언한 도메인만 좁혀서 본다. 그러니 산출물만 만들고
//       선언에서 빼면 값 비교를 조용히 건너뛸 수 있다. 그 경로를 막는다.
val parityManifestCheck =
    tasks.register("parityManifestCheck") {
        group = "verification"
        description = "parity-domains.txt 의 선언과 실제 산출물이 정확히 일치하는지 확인한다"
        dependsOn(subprojects.map { "${it.path}:parityHarness" })
        val actualDir = parityActualDir
        val declarationFile = parityDomainsFile
        outputs.upToDateWhen { false }
        doLast {
            check(declarationFile.isFile) {
                "parity 도메인 선언 파일이 없다: $declarationFile — 무엇을 검증해야 하는지의 근거가 사라졌다."
            }
            val declared = declaredParityDomains(declarationFile).toSet()
            val produced =
                (actualDir.listFiles()?.filter { it.isDirectory } ?: emptyList())
                    .map { it.name }
                    .toSet()

            val problems = mutableListOf<String>()
            (declared - produced).sorted().forEach {
                problems +=
                    "  - [선언 O / 산출 X] $it — parity-domains.txt 는 포팅됐다고 선언하는데 " +
                    "parityHarness 가 parity/actual/$it 에 아무것도 쓰지 않았다."
            }
            (produced - declared).sorted().forEach {
                problems +=
                    "  - [선언 X / 산출 O] $it — 산출물은 있는데 선언이 없다. " +
                    "CI parity 비교가 이 도메인을 건너뛰므로 값이 달라도 드러나지 않는다."
            }
            (declared intersect produced).sorted().forEach { domain ->
                val jsons = File(actualDir, domain).listFiles { f: File -> f.isFile && f.name.endsWith(".json") }
                if (jsons.isNullOrEmpty()) {
                    problems += "  - [산출물 0건] $domain — 디렉터리는 있으나 json 이 하나도 없다."
                }
            }

            if (problems.isNotEmpty()) {
                error(
                    buildString {
                        appendLine("parity 선언과 산출물이 어긋난다.")
                        appendLine("  선언(parity-domains.txt): ${declared.sorted().joinToString(", ").ifEmpty { "없음" }}")
                        appendLine("  산출(parity/actual):      ${produced.sorted().joinToString(", ").ifEmpty { "없음" }}")
                        problems.forEach { appendLine(it) }
                    },
                )
            }
            if (declared.isEmpty()) {
                logger.lifecycle(
                    "parity 선언 0개 — 포팅을 끝냈다고 선언한 도메인이 없다. " +
                        "게이트는 아무 값도 검증하지 않는다(= 통과가 아니라 '검증 대상 없음').",
                )
            } else {
                logger.lifecycle("parity 선언 ${declared.size}개 전부 산출물 확인: ${declared.sorted().joinToString(", ")}")
            }
        }
    }

// 루트에서 한 번에 도는 편의 태스크.
tasks.register("parityHarness") {
    group = "verification"
    description = "모든 모듈의 parity 산출물을 저장소 루트 parity/actual/ 에 쓰고 선언과 대조한다"
    dependsOn(parityManifestCheck)
}
