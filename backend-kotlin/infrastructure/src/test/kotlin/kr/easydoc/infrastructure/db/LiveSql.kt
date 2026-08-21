package kr.easydoc.infrastructure.db

/** 소스에서 뽑은 SQL 조각에서 PostgreSQL 이 술어로 평가하지 않는 부분을 지운다. */
internal object LiveSql {
    /** 판정에 쓰는 「살아 있는 SQL」. 방어 존재 판정 전용이다 — 분모 계산에 쓰면 방향이 뒤집힌다. */
    fun of(sql: String): String = redactLiterals(stripComments(sql))

    /** 주석을 걷어낸 사본. */
    fun stripComments(sql: String): String {
        val out = StringBuilder(sql.length)
        var index = 0
        while (index < sql.length) {
            index =
                when {
                    startsWith(sql, index, '/', '*') -> {
                        skipBlockComment(sql, index, out)
                    }

                    startsWith(sql, index, '-', '-') -> {
                        skipLineComment(sql, index, out)
                    }

                    else -> {
                        out.append(sql[index])
                        index + 1
                    }
                }
        }
        return out.toString()
    }

    /** 문자열 리터럴의 내용을 공백으로 바꾼 사본. 인용부호 자체도 공백이 된다. */
    fun redactLiterals(sql: String): String {
        val out = StringBuilder(sql.length)
        var index = 0
        while (index < sql.length) {
            val dollarTag = dollarTagAt(sql, index)
            index =
                when {
                    dollarTag != null -> {
                        skipDollarQuoted(sql, index, dollarTag, out)
                    }

                    sql[index] == '\'' -> {
                        skipQuoted(sql, index, out)
                    }

                    else -> {
                        out.append(sql[index])
                        index + 1
                    }
                }
        }
        return out.toString()
    }

    private fun startsWith(
        sql: String,
        index: Int,
        first: Char,
        second: Char,
    ): Boolean = sql[index] == first && index + 1 < sql.length && sql[index + 1] == second

    /** 블록 주석을 중첩 깊이를 세며 건너뛴다. 닫히지 않으면 조각 끝까지 간다. */
    private fun skipBlockComment(
        sql: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        out.append(' ')
        var index = start + 2
        var depth = 1
        while (index < sql.length && depth > 0) {
            when {
                startsWith(sql, index, '/', '*') -> {
                    depth++
                    index += 2
                }

                startsWith(sql, index, '*', '/') -> {
                    depth--
                    index += 2
                }

                else -> {
                    if (sql[index] == '\n') out.append('\n')
                    index++
                }
            }
        }
        return index
    }

    /** 줄 끝까지 건너뛴다. 개행 자체는 남긴다 — 다음 줄과 붙지 않게. */
    private fun skipLineComment(
        sql: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        out.append(' ')
        var index = start
        while (index < sql.length && sql[index] != '\n') index++
        return index
    }

    /** 작은따옴표 리터럴을 건너뛴다. `''` 와 `\'` 는 닫지 않는다(KDoc ⑴). */
    private fun skipQuoted(
        sql: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        out.append(' ')
        var index = start + 1
        while (index < sql.length) {
            val current = sql[index]
            when {
                current == '\'' && index + 1 < sql.length && sql[index + 1] == '\'' -> {
                    out.append("  ")
                    index += 2
                }

                current == '\\' && index + 1 < sql.length -> {
                    out.append("  ")
                    index += 2
                }

                current == '\'' -> {
                    out.append(' ')
                    return index + 1
                }

                else -> {
                    out.append(if (current == '\n') '\n' else ' ')
                    index++
                }
            }
        }
        return index
    }

    /** 이 자리가 달러 인용의 여는 표시면 그 태그(`$$` 나 `$tag$` 전체), 아니면 `null`. */
    private fun dollarTagAt(
        sql: String,
        index: Int,
    ): String? {
        if (sql[index] != '$') return null
        var cursor = index + 1
        while (cursor < sql.length && (sql[cursor].isLetterOrDigit() || sql[cursor] == '_')) cursor++
        val closed = cursor < sql.length && sql[cursor] == '$'
        return if (closed) sql.substring(index, cursor + 1) else null
    }

    /** 달러 인용 본문을 건너뛴다. 같은 태그로 닫히지 않으면 조각 끝까지 간다. */
    private fun skipDollarQuoted(
        sql: String,
        start: Int,
        tag: String,
        out: StringBuilder,
    ): Int {
        repeat(tag.length) { out.append(' ') }
        var index = start + tag.length
        while (index < sql.length) {
            if (sql.startsWith(tag, index)) {
                repeat(tag.length) { out.append(' ') }
                return index + tag.length
            }
            out.append(if (sql[index] == '\n') '\n' else ' ')
            index++
        }
        return index
    }
}
