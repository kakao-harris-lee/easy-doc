// infrastructure — JDBC repository, 암호화, 문서 파서, LLM provider 구현, 작업 큐.
//
// 벤더 LLM 접근은 **이 모듈에서만** 의존성으로 선언한다 (프로젝트 CLAUDE.md 아키텍처
// 규칙 1). 규칙을 문서로만 두지 않고 빌드로 강제하기 위해 `implementation` 으로 선언한다 —
// api/worker 의 컴파일 클래스패스에 HTTP·JSON 타입이 올라오지 않는다.
//
// LLM 어댑터는 벤더 SDK 대신 Spring RestClient + Jackson 을 쓴다(사유는 version catalog).

plugins {
    // @Configuration·@Component 클래스를 자동으로 open 으로 만든다.
    alias(libs.plugins.kotlin.spring)
    // Testcontainers 기동 코드를 api 테스트가 함께 쓴다.
    `java-test-fixtures`
}

dependencies {
    api(project(":core"))
    api(project(":application"))

    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    // LLM provider 어댑터의 HTTP·JSON. 서블릿 컨테이너를 끌고 오는 starter-web 이 아니라
    // spring-web 만 쓴다 — infrastructure 는 요청을 받는 쪽이 아니라 보내는 쪽이다.
    implementation(libs.spring.web)
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    // 인증 — Argon2id 해시(검증된 인코더 + PHC 인코딩)와 HS256 토큰.
    // 두 라이브러리 모두 이 모듈 밖으로 새지 않는다: api·worker 는 infrastructure 를
    // runtimeOnly 로만 의존하므로 컴파일 시점에 `com.nimbusds.*` 를 볼 수 없다.
    implementation(libs.spring.security.crypto)
    implementation(libs.nimbus.jose.jwt)
    // 메일 발송(P0-3). JavaMailSender 타입은 이 모듈 밖으로 새지 않는다 — `SmtpMailSender`
    // 만 이 SDK 를 알고, 공개 포트는 `application.mail.MailSender`(순수 Kotlin) 다.
    implementation(libs.spring.boot.starter.mail)
    // 문서 추출(Phase 4). 파서 라이브러리도 이 모듈 밖으로 새지 않는다 — api·worker 는
    // infrastructure 를 runtimeOnly 로만 의존하므로 `org.apache.poi.*`·`org.apache.pdfbox.*`
    // ·`kr.dogfoot.hwpxlib.*` 를 컴파일 시점에 볼 수 없다.
    implementation(libs.poi.ooxml)
    implementation(libs.pdfbox)
    implementation(libs.hwpxlib)
    // POI 전이지만 직접 쓴다(zip 예산 방어). 전이에 기대면 POI 업그레이드가 API 를 바꾼다.
    implementation(libs.commons.compress)
    // POI 전이 둘. `implementation` 으로 선언하는 이유는 **컴파일과 런타임이 같은 버전을
    // 보게** 하기 위해서다 — `runtimeOnly` 로 고정하면 compileClasspath 는 POI 가 끌고 온
    // 값으로, runtimeClasspath 는 여기 값으로 갈린다. xmlbeans 는 실제로 import 한다
    // (`XmlObject.getDomNode()` — DOCX DOM 순회의 기반, spike S-1).
    implementation(libs.commons.io)
    implementation(libs.xmlbeans)
    // POI 의 log4j-api 를 slf4j 로 잇는다(spike S-10). 우리 코드는 log4j 타입을 모른다.
    runtimeOnly(libs.log4j.to.slf4j)
    // Argon2PasswordEncoder 가 런타임에 요구한다. 우리 코드는 BC 타입을 import 하지 않는다.
    runtimeOnly(libs.bouncycastle.bcprov)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testFixturesImplementation(platform(libs.spring.boot.bom))
    testFixturesApi(libs.testcontainers.postgresql)
    // 테스트 Spring 컨텍스트에 실제 암호화 키를 넣는 EnvironmentPostProcessor
    // (`TestEncryptionKeys`)가 `org.springframework.boot.EnvironmentPostProcessor` 를
    // 구현한다. testFixtures 산출물은 `testFixtures(project(...))` 로 명시적으로 당긴
    // 테스트 클래스패스에만 올라가므로 bootJar 의 runtimeClasspath 에는 실리지 않는다.
    testFixturesImplementation(libs.spring.boot.starter)
    testFixturesRuntimeOnly(libs.postgresql)
    // 실제 OLE2 컨테이너 fixture 빌더(`Ole2ContainerFixtures`)가 POIFS 를 쓴다. `api` 는
    // `testFixtures(project(":infrastructure"))` 로만 이 산출물을 당기므로, `implementation`
    // 이 아니라 `testFixturesImplementation`으로 둬 `api` 의 테스트 **컴파일** 클래스패스에는
    // POI 타입이 새지 않는다(공개 시그니처가 POI 타입을 노출하지 않는 이유이기도 하다) —
    // 런타임에는 testFixtures 산출물을 통해 전이적으로 실린다.
    testFixturesImplementation(libs.poi.ooxml)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.spring.boot.starter.flyway)
    testImplementation(testFixtures(project(":core")))
    testRuntimeOnly(libs.junit.platform.launcher)
    // 설정 바인딩 회귀 테스트가 실행 환경과 같은 조건에서 돌아야 한다.
    testRuntimeOnly(libs.kotlin.reflect)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.flyway.postgresql)
    // SmtpMailSender 어댑터 테스트용 임베디드 fake SMTP 서버 — 실제 네트워크로 나가지 않는다.
    testImplementation(libs.greenmail.junit5)
}

// e2e 레인이 쓰는 저장 암호화 키 생성 실행 경로 (게이트 28 C-3).
//
// `e2e` 잡과 `frontend/e2e/run-local.sh` 는 매 실행 새 키로 API 를 띄운다. 그런데
// `CryptoConfiguration` 의 기동 자기점검이 키와 **검사값(KCV)** 을 함께 요구하고, KCV 는
// AES-256-GCM 인증 태그라 셸(`openssl enc`)로는 계산할 수 없다 — 태그를 내주지 않는다.
//
// **계산을 옮겨 적지 않고 제품 코드를 실행한다.** 이 태스크는 testFixtures 의
// `EncryptionKeyEnv.kt` 를 도는데, 그것은 `TestEncryptionKeys` 를 부르고 그것이 제품
// `KeyCheckValue.of` 로 검사값을 구한다. 저장소에서 KCV 를 계산하는 코드는 한 곳뿐이다.
//
// 출력 파일은 **인자로 받는다**(CI 는 `$GITHUB_ENV` 를 준다). 키를 표준출력으로 흘리면
// 로그에 남고, 로그에 남은 키는 회수 말고는 되돌릴 방법이 없다.
//
// `bootJar` 는 영향을 받지 않는다 — testFixtures 산출물은 runtimeClasspath 에 없다.
tasks.register<JavaExec>("writeEncryptionKeyEnv") {
    group = "verification"
    description = "저장 암호화 키 한 세대를 만들어 -Peasydoc.encryptionEnvOut 파일에 덧붙인다 (KCV 는 제품 KeyCheckValue 가 계산)."
    mainClass.set("kr.easydoc.infrastructure.crypto.EncryptionKeyEnvKt")
    classpath = sourceSets["testFixtures"].runtimeClasspath

    val destination = providers.gradleProperty("easydoc.encryptionEnvOut")
    argumentProviders.add {
        listOf(
            destination.orNull
                ?: error("-Peasydoc.encryptionEnvOut=<환경변수를 덧붙일 파일> 을 준다."),
        )
    }

    // 매번 새 키를 내야 한다. UP-TO-DATE 로 건너뛰면 파일이 비어 있는 채 통과한다.
    outputs.upToDateWhen { false }
}

// ── 사전 색인 배포 (easy-dictionary) ────────────────────────────────────────────────
//
// 정본은 `dictionary/dist/easy_dict.index.json` 이지만, 제품은 **여기 커밋된 사본**을
// 클래스패스 리소스로 읽는다. 사본이 필요한 이유는 도커다: compose 의 백엔드 빌드 컨텍스트가
// `./backend-kotlin` 이라 컨테이너 안 Gradle 은 `../dictionary/` 를 볼 수 없다. 빌드 시점에
// 정본을 끌어오는 방식은 CI 의 backend 잡(도커 빌드)에서 그대로 깨진다.
//
// 사본이 생기면 갈라질 자리도 생긴다. 아래 검사 태스크가 `check` 에 붙어 그것을 막는다 —
// 새 검증 하네스가 아니라 **기존 Gradle 검증에 태스크 하나를 얹은 것**이다.
val dictionaryIndexSource: File = File(rootDir.parentFile, "dictionary/dist/easy_dict.index.json")
val dictionaryIndexResource: File = file("src/main/resources/dictionary/easy_dict.index.json")

tasks.register<Copy>("syncDictionaryIndex") {
    group = "build"
    description = "dictionary/dist/easy_dict.index.json 을 infrastructure 리소스 사본으로 복사한다."
    from(dictionaryIndexSource)
    into(dictionaryIndexResource.parentFile)

    // 정본이 없는 체크아웃(도커 빌드 컨텍스트)에서도 태스크가 실패하지 않게 둔다 —
    // 그 환경에는 복사할 것이 없고, 사본은 이미 커밋돼 있다.
    onlyIf { dictionaryIndexSource.isFile }
}

val checkDictionaryIndex =
    tasks.register("checkDictionaryIndex") {
        group = "verification"
        description = "커밋된 사전 색인 사본이 dictionary/dist 정본과 같은지 본다(정본이 없으면 [건너뜀])."

        // 정본은 Gradle 루트(backend-kotlin) 밖이라 어차피 빌드 지문에 잡히지 않는다.
        // 두 파일을 견주는 비용은 밀리초 단위이므로 매번 실제로 본다 — UP-TO-DATE 로
        // 건너뛴 검사는 검사하지 않은 것과 같다.
        outputs.upToDateWhen { false }

        val source = dictionaryIndexSource
        val copy = dictionaryIndexResource
        doLast {
            if (!source.isFile) {
                logger.lifecycle(
                    "[건너뜀] ${source.path} 없음 — 사본이 낡았는지 검사하지 않았다. " +
                        "\"통과\"가 아니라 \"검사 안 함\"이다.",
                )
                return@doLast
            }
            check(copy.isFile) {
                "사전 색인 사본이 없다: ${copy.path} — ./gradlew :infrastructure:syncDictionaryIndex 를 돌린다."
            }
            check(source.readBytes().contentEquals(copy.readBytes())) {
                "사전 색인 사본이 정본과 다르다: ${copy.path} — " +
                    "./gradlew :infrastructure:syncDictionaryIndex 를 돌리고 사본을 함께 커밋한다."
            }
        }
    }

tasks.named("check") {
    dependsOn(checkDictionaryIndex)
}
