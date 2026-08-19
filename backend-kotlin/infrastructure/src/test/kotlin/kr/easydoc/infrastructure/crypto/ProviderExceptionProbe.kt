package kr.easydoc.infrastructure.crypto

import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.Key
import java.security.Provider
import java.security.ProviderException
import java.security.SecureRandom
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.CipherSpi

/**
 * **JCA 공급자가 비검사 예외를 내는 상황**을 실제로 만든다 — 게이트 26 조치 4 (codex D-3).
 *
 * ## 무엇을 재려고 있는가
 *
 * `AesGcmContentCipher.open` 은 `GeneralSecurityException` 과 `RuntimeException` 을 **둘 다**
 * 잡는다. 둘째 갈래의 사유는 KDoc 에 적혀 있다 — `java.security.ProviderException` 처럼
 * **검사 예외가 아닌** 공급자 실패가 그대로 올라가면 호출자가 [DecryptionFailedException]
 * 이 아닌 다른 타입·다른 메시지·스택트레이스를 보게 되고, 그 순간 없애려던 복호화 oracle 이
 * **예외 타입 축으로 되살아난다.**
 *
 * codex D-3 의 지적은 그 둘째 catch 에 **음성 통제가 없다**는 것이었다 — 지워도 빨개지는
 * 테스트가 없었다. 이 공급자가 그 통제다.
 *
 * ## 왜 이렇게까지 하는가 (더 싼 방법이 없다)
 *
 * `open` 안에서 `RuntimeException` 이 나오는 자리는 **JCA 공급자 하나뿐**이다. 나머지
 * 입력(짧은 봉투·모르는 키 세대)은 호출 전에 균일화 갈래로 대체되므로 예외를 만들지
 * 못한다. 제품 코드에 시험용 이음매(`Cipher` 팩토리 주입)를 내는 대신, **표준 JCA
 * 확장점**으로 공급자를 바꿔치기한다 — 제품 코드는 한 줄도 시험을 위해 바뀌지 않는다.
 *
 * 서명되지 않은 공급자로 `Cipher` 서비스를 제공할 수 있는지는 툴체인과 같은 JDK
 * (Temurin 21.0.4)에서 실측해 확인했다.
 *
 * ## 쓰는 쪽의 규율
 *
 * 전역 상태(`java.security.Security`)를 건드리므로 **반드시 `finally` 에서 제거한다.**
 * 이 저장소의 Gradle 테스트는 병렬 실행 설정이 없어(`maxParallelForks`·JUnit 병렬 설정
 * 모두 없음) 한 JVM 안에서 순차로 돈다.
 */
class ProviderExceptionProvider : Provider(NAME, "1.0", "복호화 경로의 비검사 공급자 실패를 재는 테스트 전용 공급자") {
    init {
        put("Cipher.$TRANSFORMATION", ProviderExceptionCipherSpi::class.java.name)
    }

    companion object {
        const val NAME: String = "EasyDocProviderExceptionProbe"

        const val TRANSFORMATION: String = "AES/GCM/NoPadding"

        /**
         * [ProviderExceptionCipherSpi.engineDoFinal] 이 실제로 불린 횟수.
         *
         * 0 이면 이 공급자가 **선택되지 않았다**는 뜻이고, 그러면 그 케이스는 아무것도
         * 재지 않은 채 초록이 된다. 그 상태를 통과로 세지 않으려고 센다.
         */
        @Volatile
        @JvmStatic
        var reachedCount: Int = 0
    }
}

/**
 * `doFinal` 에서 [ProviderException] 을 던지는 `AES/GCM/NoPadding` 구현.
 *
 * 나머지 단계(mode·padding·init·AAD)는 조용히 받는다 — 재려는 것은 **복호화의 마지막
 * 단계에서 비검사 예외가 나오는 갈래** 하나이고, 앞 단계에서 끊으면 그 갈래에 닿지 못한다.
 */
@Suppress("TooManyFunctions") // CipherSpi 의 추상 메서드가 13개다. 줄일 수 있는 수가 아니다.
class ProviderExceptionCipherSpi : CipherSpi() {
    override fun engineSetMode(mode: String) = Unit

    override fun engineSetPadding(padding: String) = Unit

    override fun engineGetBlockSize(): Int = BLOCK_BYTES

    override fun engineGetOutputSize(inputLen: Int): Int = inputLen

    override fun engineGetIV(): ByteArray = ByteArray(NONCE_BYTES)

    override fun engineGetParameters(): AlgorithmParameters? = null

    override fun engineInit(
        opmode: Int,
        key: Key?,
        random: SecureRandom?,
    ) = Unit

    override fun engineInit(
        opmode: Int,
        key: Key?,
        params: AlgorithmParameterSpec?,
        random: SecureRandom?,
    ) = Unit

    override fun engineInit(
        opmode: Int,
        key: Key?,
        params: AlgorithmParameters?,
        random: SecureRandom?,
    ) = Unit

    override fun engineUpdate(
        input: ByteArray?,
        inputOffset: Int,
        inputLen: Int,
    ): ByteArray = ByteArray(0)

    override fun engineUpdate(
        input: ByteArray?,
        inputOffset: Int,
        inputLen: Int,
        output: ByteArray?,
        outputOffset: Int,
    ): Int = 0

    override fun engineUpdateAAD(
        src: ByteArray?,
        offset: Int,
        len: Int,
    ) = Unit

    override fun engineUpdateAAD(src: ByteBuffer?) = Unit

    override fun engineDoFinal(
        input: ByteArray?,
        inputOffset: Int,
        inputLen: Int,
    ): ByteArray = throw failure()

    override fun engineDoFinal(
        input: ByteArray?,
        inputOffset: Int,
        inputLen: Int,
        output: ByteArray?,
        outputOffset: Int,
    ): Int = throw failure()

    private fun failure(): ProviderException {
        ProviderExceptionProvider.reachedCount += 1
        // 하드웨어 토큰·FIPS 공급자·손상된 정책 파일에서 실제로 나오는 형태다.
        return ProviderException("테스트 공급자의 비검사 실패")
    }

    private companion object {
        const val BLOCK_BYTES = 16

        const val NONCE_BYTES = 12
    }
}
