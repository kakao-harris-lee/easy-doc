package kr.easydoc.infrastructure.crypto

import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.Key
import java.security.Provider
import java.security.ProviderException
import java.security.SecureRandom
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.CipherSpi

/** JCA 공급자가 비검사 예외를 내는 상황을 실제로 만든다 — 게이트 26 조치 4 (codex D-3). */
class ProviderExceptionProvider : Provider(NAME, "1.0", "복호화 경로의 비검사 공급자 실패를 재는 테스트 전용 공급자") {
    init {
        put("Cipher.$TRANSFORMATION", ProviderExceptionCipherSpi::class.java.name)
    }

    companion object {
        const val NAME: String = "EasyDocProviderExceptionProbe"

        const val TRANSFORMATION: String = "AES/GCM/NoPadding"

        /** [ProviderExceptionCipherSpi.engineDoFinal] 이 실제로 불린 횟수. */
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

        return ProviderException("테스트 공급자의 비검사 실패")
    }

    private companion object {
        const val BLOCK_BYTES = 16

        const val NONCE_BYTES = 12
    }
}
