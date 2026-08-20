package kr.easydoc.infrastructure.crypto

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

/**
 * 저장 암호화 키 한 세대를 만들어 **환경변수 파일에 덧붙이는** 진입점. e2e 레인 전용이다.
 *
 * ```
 * ./gradlew -q :infrastructure:writeEncryptionKeyEnv -Peasydoc.encryptionEnvOut=<파일>
 * ```
 *
 * 덧붙이는 두 줄은 `api/src/main/resources/application.yml` 이 읽는 이름 그대로다
 * ([TestEncryptionKeys.KEY_PROPERTY] · [TestEncryptionKeys.CHECK_VALUE_PROPERTY]).
 *
 * ## 왜 있는가 (게이트 28 C-3)
 *
 * `e2e` 잡과 로컬 러너(`frontend/e2e/run-local.sh`)는 **매 실행 새 비밀**로 API 를 띄운다 —
 * 저장소에 키를 적으면 그것이 곧 커밋된 비밀이고, 테스트용이라는 사실은 파일을 읽는
 * 사람에게만 보인다(프로젝트 `CLAUDE.md` 보안 규칙). JWT 비밀키는 `openssl rand -hex 32`
 * 한 줄로 끝나는데 저장 암호화는 그렇지 않다: [CryptoConfiguration] 의 기동 자기점검이 키와
 * **검사값(KCV)** 을 함께 요구하고(게이트 25 F-2·F-3 · 게이트 26 조치 3), 키만 주면
 * *"키 v1 에 kcv 가 없다"* 로 앱이 아예 뜨지 않는다. 그래서 두 값을 함께 내는 자리가 필요하다.
 *
 * 그 자리가 없어서 `e2e` 잡이 두 회차 연속 죽었다 — API 가 기동하지 못해 `/health` 200 이
 * 영영 오지 않았다(run 32333596159·32309434868).
 *
 * ## KCV 를 여기서 다시 구현하지 않는다 (리더 판정, 게이트 28)
 *
 * KCV 는 AES-256-GCM 인증 태그라 `openssl enc` 로는 계산할 수 없다(태그를 내주지 않는다).
 * 그렇다고 도메인 문자열·nonce 길이·태그 길이를 셸이나 별도 도구에 **옮겨 적으면** 계산이
 * 저장소에 둘이 되고, 한쪽만 고쳐지는 날 CI 는 조용히 초록인데 실제 배포는 뜨지 않는다.
 *
 * 그래서 이 진입점은 **아무것도 계산하지 않는다.** 이미 같은 일을 하는 [TestEncryptionKeys]
 * 를 그대로 부르고, 그것은 제품 [KeyCheckValue] 로 검사값을 구한다. 저장소에서 KCV 를
 * 계산하는 코드는 여전히 `KeyCheckValue.of` **한 곳뿐**이다.
 *
 * ## 왜 testFixtures 인가
 *
 * 키 생성 + 제품 KCV 계산은 [TestEncryptionKeys] 가 이미 하고 있고, 그것이 여기 산다.
 * 같은 것을 다시 만들지 않는다(`CLAUDE.md` 「구현 전 리서치·계획」 2 — 기구현 확인).
 * 그리고 이 소스셋의 산출물은 `testFixtures(project(...))` 로 명시적으로 당긴 클래스패스에만
 * 올라가므로 **`bootJar` 에는 실리지 않는다** — 제품에 키 생성 진입점이 실리지 않는다.
 *
 * ## 비밀을 표준출력에 쓰지 않는다
 *
 * 키는 인자로 받은 파일에만 덧붙인다(CI 는 그 파일로 `$GITHUB_ENV` 를 준다). 표준출력에는
 * **검사값만** 남긴다 — KCV 는 비밀이 아니고([KeyCheckValue] KDoc), 기동이 거부됐을 때
 * 로그에서 대조할 값이 하나는 있어야 한다.
 */
fun main(args: Array<String>) {
    if (args.size != 1 || args[0].isBlank()) {
        System.err.println("사용법: writeEncryptionKeyEnv -Peasydoc.encryptionEnvOut=<환경변수를 덧붙일 파일>")
        System.err.println("  인자를 정확히 하나 받는다 — 키를 표준출력으로 흘리지 않기 위해 출력 파일이 필수다.")
        System.err.println("  로그에 남은 키는 회수 말고는 되돌릴 방법이 없다.")
        exitProcess(USAGE_EXIT_CODE)
    }

    val lines =
        TestEncryptionKeys.properties().entries.joinToString(separator = "") { (name, value) ->
            "$name=$value\n"
        }
    Files.writeString(
        Path.of(args[0]),
        lines,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND,
    )

    // 검사값만 찍는다. 키는 한 조각도 여기에 실리지 않는다.
    println("저장 암호화 키 v1 생성 완료 (kcv=${TestEncryptionKeys.checkValue})")
}

/** 인자를 잘못 준 경우의 종료 코드. 실패를 0 으로 내지 않는다. */
private const val USAGE_EXIT_CODE = 2
