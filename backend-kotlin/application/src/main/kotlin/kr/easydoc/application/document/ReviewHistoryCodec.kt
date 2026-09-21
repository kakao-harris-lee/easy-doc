package kr.easydoc.application.document

import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.actionguide.ActionGuideCandidate
import kr.easydoc.core.actionguide.ActionGuideItem
import kr.easydoc.core.actionguide.ActionGuideSection
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBytes
import kr.easydoc.core.easyread.ReviewAnalysis
import kr.easydoc.core.easyread.ReviewItem
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.StorageException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.DateTimeException
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * History snapshots use a small versioned binary envelope inside the AEAD payload. This avoids
 * base64-inflating user text before applying the 64KiB plaintext limit. The API still exposes the
 * artifact as a JSON string; the binary form is storage-only.
 */
object ReviewHistorySnapshotCodec {
    const val MAX_PLAINTEXT_BYTES: Int = 64 * 1024
    private const val VERSION: Int = 1

    fun encode(value: ReviewHistorySnapshotValue): ByteArray? {
        if (!value.hasContent()) return null
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeByte(VERSION)
            data.writeByte(value.kind?.ordinal ?: -1)
            writeNullable(data, value.contentText)
            writeNullable(data, value.artifactJson)
        }
        return out.toByteArray().takeIf { it.size <= MAX_PLAINTEXT_BYTES }
    }

    @Suppress("ThrowsCount") // Binary envelope checks fail closed at each structural boundary.
    fun decode(bytes: ByteArray): ReviewHistorySnapshotValue {
        if (bytes.size > MAX_PLAINTEXT_BYTES) throw InvalidInputException(INVALID_SNAPSHOT_MESSAGE)
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readUnsignedByte() != VERSION) throw InvalidInputException(INVALID_SNAPSHOT_MESSAGE)
                val kindOrdinal = input.readByte().toInt()
                val kind =
                    if (kindOrdinal == -1) {
                        null
                    } else {
                        ReviewHistorySnapshotKind.entries.getOrNull(kindOrdinal)
                            ?: throw InvalidInputException(INVALID_SNAPSHOT_MESSAGE)
                    }
                val content = readNullable(input)
                val artifact = readNullable(input)
                if (input.available() != 0) throw InvalidInputException(INVALID_SNAPSHOT_MESSAGE)
                ReviewHistorySnapshotValue(kind, content, artifact)
            }
        } catch (failure: InvalidInputException) {
            throw failure
        } catch (failure: java.io.IOException) {
            throw InvalidInputException(INVALID_SNAPSHOT_MESSAGE).also { it.initCause(failure) }
        }
    }

    fun encrypt(
        value: ReviewHistorySnapshotValue,
        snapshotId: UUID,
        cipher: ContentCipher,
    ): EncryptedContent? =
        encode(value)?.let {
            cipher.encryptBytes(PlainBytes(it), snapshotId, EncryptedField.REVIEW_HISTORY_SNAPSHOT)
        }

    fun decrypt(
        payload: EncryptedContent,
        snapshotId: UUID,
        cipher: ContentCipher,
    ): ReviewHistorySnapshotValue {
        // Preserve the cipher's authentication failure as a storage failure. If authentication
        // succeeds but the plaintext envelope is malformed, it is still stored-data corruption,
        // never a client input error that should become HTTP 422.
        val plain = cipher.decryptBytes(payload, snapshotId, EncryptedField.REVIEW_HISTORY_SNAPSHOT)
        return try {
            decode(plain.value)
        } catch (failure: InvalidInputException) {
            throw StorageException(CORRUPT_SNAPSHOT_MESSAGE).also { it.initCause(failure) }
        }
    }

    private fun writeNullable(
        output: DataOutputStream,
        value: String?,
    ) {
        if (value == null) {
            output.writeInt(-1)
            return
        }
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readNullable(input: DataInputStream): String? {
        val length = input.readInt()
        if (length == -1) return null
        if (length < 0 || length > MAX_PLAINTEXT_BYTES || length > input.available()) {
            throw InvalidInputException(INVALID_SNAPSHOT_MESSAGE)
        }
        return String(ByteArray(length).also(input::readFully), StandardCharsets.UTF_8)
    }

    const val INVALID_SNAPSHOT_MESSAGE: String = "검수 기록 스냅샷을 읽을 수 없습니다"
    const val CORRUPT_SNAPSHOT_MESSAGE: String = "검수 기록 스냅샷이 손상되었습니다"
}

/** Opaque, conversion-bound cursor with a fixed upper cutoff for stable pagination. */
object ReviewHistoryCursorCodec {
    private const val MAGIC_0: Byte = 0x52 // R
    private const val MAGIC_1: Byte = 0x48 // H
    private const val VERSION: Byte = 1
    private const val BYTE_SIZE: Int = 59
    const val INVALID_CURSOR_MESSAGE: String = "검수 기록 커서가 올바르지 않습니다"

    fun encode(cursor: ReviewHistoryCursor): String {
        validate(cursor)
        val bytes = ByteBuffer.allocate(BYTE_SIZE)
        bytes.put(MAGIC_0).put(MAGIC_1).put(VERSION)
        putUuid(bytes, cursor.conversionId)
        putInstant(bytes, cursor.cutoff)
        putInstant(bytes, cursor.createdAt)
        putUuid(bytes, cursor.eventId)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array())
    }

    @Suppress("ThrowsCount", "CyclomaticComplexMethod") // Wire parser validates each cursor component before use.
    fun decode(
        encoded: String,
        conversionId: UUID,
    ): ReviewHistoryCursor {
        if (encoded.isBlank() || encoded.length > MAX_CURSOR_LENGTH) {
            throw InvalidInputException(INVALID_CURSOR_MESSAGE)
        }
        return try {
            val bytes = Base64.getUrlDecoder().decode(encoded)
            if (bytes.size != BYTE_SIZE) throw InvalidInputException(INVALID_CURSOR_MESSAGE)
            val input = ByteBuffer.wrap(bytes)
            if (input.get() != MAGIC_0 || input.get() != MAGIC_1 || input.get() != VERSION) {
                throw InvalidInputException(INVALID_CURSOR_MESSAGE)
            }
            val cursorConversionId = readUuid(input)
            val cutoff = readInstant(input)
            val createdAt = readInstant(input)
            val eventId = readUuid(input)
            val cursor = ReviewHistoryCursor(cursorConversionId, cutoff, createdAt, eventId)
            if (cursorConversionId != conversionId || input.hasRemaining()) {
                throw InvalidInputException(INVALID_CURSOR_MESSAGE)
            }
            validate(cursor)
            cursor
        } catch (failure: InvalidInputException) {
            throw failure
        } catch (failure: IllegalArgumentException) {
            throw InvalidInputException(INVALID_CURSOR_MESSAGE).also { it.initCause(failure) }
        } catch (failure: BufferUnderflowException) {
            throw InvalidInputException(INVALID_CURSOR_MESSAGE).also { it.initCause(failure) }
        } catch (failure: DateTimeException) {
            throw InvalidInputException(INVALID_CURSOR_MESSAGE).also { it.initCause(failure) }
        }
    }

    private fun putUuid(
        buffer: ByteBuffer,
        value: UUID,
    ) {
        buffer.putLong(value.mostSignificantBits).putLong(value.leastSignificantBits)
    }

    private fun readUuid(buffer: ByteBuffer): UUID = UUID(buffer.long, buffer.long)

    private fun putInstant(
        buffer: ByteBuffer,
        value: Instant,
    ) {
        buffer.putLong(value.epochSecond).putInt(value.nano)
    }

    private fun readInstant(buffer: ByteBuffer): Instant {
        val seconds = buffer.long
        val nanos = buffer.int
        if (nanos !in 0 until NANOS_PER_SECOND) throw InvalidInputException(INVALID_CURSOR_MESSAGE)
        val instant = Instant.ofEpochSecond(seconds, nanos.toLong())
        if (instant < MIN_SUPPORTED_INSTANT || instant > MAX_SUPPORTED_INSTANT) {
            throw InvalidInputException(INVALID_CURSOR_MESSAGE)
        }
        return instant
    }

    private fun validate(cursor: ReviewHistoryCursor) {
        requireSupported(cursor.cutoff)
        requireSupported(cursor.createdAt)
        if (cursor.createdAt > cursor.cutoff) throw InvalidInputException(INVALID_CURSOR_MESSAGE)
    }

    private fun requireSupported(value: Instant) {
        if (value < MIN_SUPPORTED_INSTANT || value > MAX_SUPPORTED_INSTANT) {
            throw InvalidInputException(INVALID_CURSOR_MESSAGE)
        }
    }

    private const val MAX_CURSOR_LENGTH: Int = 256
    private const val NANOS_PER_SECOND: Int = 1_000_000_000
    private val MIN_SUPPORTED_INSTANT: Instant = Instant.EPOCH
    private val MAX_SUPPORTED_INSTANT: Instant = Instant.parse("9999-12-31T23:59:59.999999999Z")
}

/** Deterministic JSON for encrypted artifact history; it is never parsed as executable markup. */
object ReviewHistoryArtifactJson {
    fun reviewAnalysis(value: ReviewAnalysis): String =
        buildString {
            append("{\"coverage\":")
            quote(value.coverage.wireName)
            append(",\"limitations\":[")
            value.limitedReasons.joinTo(this) { jsonString(it.wireName) }
            append("],\"items\":[")
            value.items.joinTo(this, separator = ",") { reviewItem(it) }
            append("]}")
        }

    fun actionGuide(value: ActionGuideCandidate): String =
        buildString {
            append("{\"schema_version\":").append(value.schemaVersion)
            append(",\"sections\":[")
            value.sections.joinTo(this, separator = ",") { section(it) }
            append("]}")
        }

    private fun reviewItem(value: ReviewItem): String =
        buildString {
            append("{\"item_id\":")
            quote(value.itemId.toString())
            append(",\"kind\":")
            quote(value.kind.wireName)
            append(",\"rule_code\":")
            quote(value.ruleCode)
            append(",\"source_anchors\":[")
            value.sourceAnchors.joinTo(this, separator = ",") { anchor ->
                buildString {
                    append("{\"source_unit_indexes\":[")
                    anchor.sourceUnitIndexes.joinTo(this)
                    append("],\"quote\":")
                    quote(anchor.quote)
                    append('}')
                }
            }
            append("],\"easy_unit_indexes\":[")
            value.easyUnitIndexes.joinTo(this)
            append("],\"state\":")
            quote(value.state.wireName)
            append(",\"reason\":")
            nullableString(value.reason)
            append(",\"confirmed_by\":")
            nullableString(value.confirmedBy?.toString())
            append(",\"confirmed_at\":")
            nullableString(value.confirmedAt?.toString())
            append('}')
        }

    private fun section(value: ActionGuideSection): String =
        buildString {
            append("{\"kind\":")
            quote(value.kind.wireName)
            append(",\"status\":")
            quote(value.status.wireName)
            append(",\"items\":[")
            value.items.joinTo(this, separator = ",") { item(it) }
            append("]}")
        }

    private fun item(value: ActionGuideItem): String =
        buildString {
            append("{\"text\":")
            quote(value.text)
            append(",\"cautions\":[")
            value.cautions.joinTo(this) { jsonString(it) }
            append("],\"source_anchors\":[")
            value.sourceAnchors.joinTo(this, separator = ",") { anchor ->
                buildString {
                    append("{\"source_unit_indexes\":[")
                    anchor.sourceUnitIndexes.joinTo(this)
                    append("],\"quote\":")
                    quote(anchor.quote)
                    append('}')
                }
            }
            append("]}")
        }

    private fun StringBuilder.nullableString(value: String?) {
        if (value == null) append("null") else quote(value)
    }

    private fun jsonString(value: String): String = buildString { quote(value) }

    private fun StringBuilder.quote(value: String) {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                in '\u0000'..'\u001F' -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
        }
        append('"')
    }

    private fun <T> Iterable<T>.joinTo(
        builder: StringBuilder,
        separator: String = ",",
        render: (T) -> String,
    ) {
        forEachIndexed { index, item ->
            if (index > 0) builder.append(separator)
            builder.append(render(item))
        }
    }
}
