package kr.easydoc.infrastructure.crypto

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

/** 저장 암호화 키 한 세대를 만들어 **환경변수 파일에 덧붙이는** 진입점. e2e 레인 전용이다. */
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
