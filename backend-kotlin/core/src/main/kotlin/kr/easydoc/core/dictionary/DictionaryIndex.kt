package kr.easydoc.core.dictionary

/**
 * 조회 전용 사전 색인 — `dictionary/src/easydict/lookup.py` 의 `EasyDict` 이식분.
 *
 * 표면형(표제어 + 변형형)을 문자 트라이로 올려 텍스트 위에서 **각 위치 최장일치 + 경계 검사**로
 * 용어를 찾는다. 적재가 끝나면 상태를 바꾸지 않으므로 여러 스레드가 동시에 읽어도 안전하다.
 *
 * ## 이식하지 않은 것
 *
 * - `annotate()` 치환 엔진과 §6.7 (1)(2) 조사 이형태 교정. 이번 릴리스는 문자열 치환을 하지
 *   않는다 — 사전은 [buildPromptContext] 로 LLM 에게 근거를 주고 변환 판단 자체는 LLM 이 한다.
 *   프롬프트에는 §6.6 대로 표제어 원형만 실리므로 조사 교정이 개입할 자리가 없다.
 * - `from_sqlite`/`search()`/FTS5. 정본 SQLite 를 직접 읽는 어드민·검수 도구 전용 경로라
 *   제품 런타임(§7.1)이 쓰지 않는다.
 *
 * ## §6.8 승자 정렬은 이식할 것이 없다
 *
 * 같은 표면형에 후보가 여럿일 때의 승자 순서는 **export 시점에 구워져** `surface_index` 의 id
 * 나열 순서로 들어온다. 그래서 여기서는 동률일 때 "리스트에서 먼저 오는 원소"를 고르기만 하면
 * 된다 — [longestMatchAt] 이 `maxBy`(첫 최대 원소 반환)를 쓰는 이유다. 다만 §6.7 (0) 정확
 * 일치 필터는 조회 시점 규칙이라 여기서 이식한다.
 */
class DictionaryIndex private constructor(
    private val entries: Map<Int, DictionaryEntry>,
    private val root: TrieNode,
    private val josa: List<String>,
) {
    /**
     * 텍스트 전체에서 사전 용어를 모두 찾는다 (§6.5).
     *
     * 각 시작 위치에서 최장일치 후보를 모은 뒤, 겹치는 매칭은 `priority` 가 큰 쪽 → 긴 쪽 →
     * 앞선 쪽 순으로 그리디하게 채택한다. 반환값은 **문서 등장 순서**다.
     */
    fun findAll(text: String): List<DictionaryMatch> {
        val raw = ArrayList<DictionaryMatch>()
        for (at in text.indices) {
            longestMatchAt(text, at)?.let { raw.add(it) }
        }

        val accepted = ArrayList<DictionaryMatch>()
        for (match in raw.sortedWith(BY_OVERLAP_PRECEDENCE)) {
            val overlaps = accepted.any { match.start < it.end && it.start < match.end }
            if (!overlaps) accepted.add(match)
        }
        return accepted.sortedBy { it.start }
    }

    /**
     * LLM 프롬프트에 주입할 컨텍스트 블록을 만든다 (§7.2).
     *
     * **이것이 사전의 실제 쓰임이다.** 전체 사전이 아니라 이 문서에 실제로 등장한 용어만,
     * 전략별 세 구역으로 나눠 싣는다. 자세한 규칙은 [renderDictionaryPromptContext] 참고.
     */
    fun buildPromptContext(
        text: String,
        policy: DictionaryContextPolicy = DictionaryContextPolicy(),
    ): String = renderDictionaryPromptContext(text, findAll(text), policy)

    /**
     * 위치 [at] 에서 시작하는 최장일치 매칭 하나를 찾는다.
     *
     * 왼쪽 경계부터 본다 — 실패하면 트라이를 걸을 필요도 없이 이 위치에서는 어떤 길이의 후보도
     * 무효다(단어 중간에서 매칭을 시작할 수 없다). 통과했으면 트라이를 끝까지 걸으며 지나친
     * 모든 표면형을 기록해 두고 **가장 긴 것부터** 경계 검사를 시도한다. 가장 긴 후보가 경계
     * 검사에서 떨어져도(`내방객`의 `내방`) 더 짧은 후보가 경로상에 있으면 이어서 시도해야
     * 최장일치와 경계 검사를 동시에 만족시킬 수 있다.
     */
    private fun longestMatchAt(
        text: String,
        at: Int,
    ): DictionaryMatch? {
        if (!leftBoundaryOk(text, at)) return null
        return candidatesAt(text, at)
            .asReversed()
            .firstNotNullOfOrNull { candidate -> acceptCandidate(text, at, candidate) }
    }

    /** [at] 에서 시작해 트라이를 걸으며 만난 표면형들. **짧은 것부터** 담긴다. */
    private fun candidatesAt(
        text: String,
        at: Int,
    ): List<SurfaceCandidate> {
        val found = ArrayList<SurfaceCandidate>()
        var node = root
        var cursor = at
        while (cursor < text.length) {
            node = node.children[text[cursor]] ?: break
            cursor += 1
            node.entryIds?.let { found.add(SurfaceCandidate(cursor - at, it)) }
        }
        return found
    }

    /**
     * 후보 하나가 경계 검사를 통과하면 매칭으로 만든다.
     *
     * ### §6.7 (0) 표면형 소유권이 priority 보다 먼저다
     *
     * 같은 표면형에 엔트리가 여럿이면 **그 표면형이 자기 표제어인 엔트리**부터 추리고, 있으면
     * 그 안에서만 `priority` 로 승자를 고른다. 이 필터가 없으면 명사 `내방`(표제어 길이 2)이
     * 동사 `내방하다`(길이 4, 그래서 priority 가 언제나 높다)에게 자기 표면형에서조차 영원히
     * 져서, 사전에 있는데도 절대 뽑히지 않는 죽은 데이터가 된다.
     *
     * 최장일치와 충돌하지 않는다 — 여기 [SurfaceCandidate.entryIds] 는 **전부 같은 표면형**을
     * 공유하는 엔트리들이라, 그 시점의 표제어 길이(=priority)는 "어느 게 더 긴 매치인가"를
     * 말해 주지 못한다.
     */
    private fun acceptCandidate(
        text: String,
        at: Int,
        candidate: SurfaceCandidate,
    ): DictionaryMatch? {
        val end = at + candidate.length
        val surface = text.substring(at, end)
        if (candidateRejected(text, at, end, surface)) return null

        val exactIds = candidate.entryIds.filter { entries.getValue(it).term == surface }
        val winnerId = exactIds.ifEmpty { candidate.entryIds }.maxBy { entries.getValue(it).priority }
        return DictionaryMatch(
            start = at,
            end = end,
            surface = surface,
            entryId = winnerId,
            entry = entries.getValue(winnerId),
        )
    }

    private fun candidateRejected(
        text: String,
        at: Int,
        end: Int,
        surface: String,
    ): Boolean {
        if (!boundaryOk(text, end)) return true
        val singleHangul = surface.length == 1 && surface[0].isHangulSyllable()
        return singleHangul && !singleHangulHeadwordOk(text, at, end)
    }

    /**
     * 매칭 직후 위치가 어절 경계인지 검사한다.
     *
     * ### §6.7 (4) 로마자·숫자 경계 — 실측 결함
     *
     * 조사 기반 검사는 "다음이 한글 음절이 아니면 통과"라 로마자·숫자끼리 이어 붙는 경우를
     * 전혀 못 걸렀다. `CCTV` 에서 `CT` 를 매칭하면 다음 글자 `V` 가 한글이 아니라 그냥 통과해
     * 원문이 파괴됐다. 매칭된 표면형의 **마지막 글자**가 로마자·숫자면 바로 다음 글자도
     * 로마자·숫자여서는 안 된다. `TF팀` 처럼 뒤에 한글이 붙는 정상 결합은 걸리지 않는다.
     */
    private fun boundaryOk(
        text: String,
        end: Int,
    ): Boolean {
        val latinRun =
            end > 0 && end < text.length &&
                text[end - 1].isLatinOrDigit() &&
                text[end].isLatinOrDigit()
        return !latinRun && josaChainReachesWordBoundary(text, end)
    }

    /**
     * §6.7 (3) 「표제어 + (조사)* + 어절경계」를 검사한다.
     *
     * 조사 하나가 걸린다고 경계로 인정하면 복합어가 훼손된다 — `급여과장에게` 의 `과` 를
     * 조사로 오인해 `지원금과장에게` 로 직책명을 파괴한 실측 결함이 있었다. 조사를 0개 이상
     * 소비한 뒤 **다음이 한글 음절이면 실패**여야 한다.
     *
     * 참조 구현은 이것을 `(?:조사대안)*(?![가-힣])` 정규식의 그리디 소비 + 백트래킹으로 푼다.
     * 여기서는 같은 판정을 **도달 가능한 위치 집합**으로 편다 — 정규식이 묻는 것은 결국 "어떤
     * 조사 나열이든 하나라도 어절 경계에 닿는가"라는 존재 명제라, 어느 조사를 먼저 시도하는지가
     * 결과를 바꾸지 않는다. 그래서 조사 목록이 길이 내림차순으로 정렬돼 있지 않아도 된다(정규식
     * 대안 순서와 달리 여기서는 순서가 무의미하다). 같은 위치를 두 번 넓히지 않으므로 백트래킹
     * 폭발도 없다.
     */
    private fun josaChainReachesWordBoundary(
        text: String,
        from: Int,
    ): Boolean {
        val visited = HashSet<Int>()
        val pending = ArrayDeque<Int>()
        visited.add(from)
        pending.addLast(from)
        while (pending.isNotEmpty()) {
            val at = pending.removeFirst()
            if (at >= text.length || !text[at].isHangulSyllable()) return true
            for (particle in josa) {
                val next = at + particle.length
                if (text.startsWith(particle, at) && visited.add(next)) pending.addLast(next)
            }
        }
        return false
    }

    /**
     * 매칭 시작 위치의 왼쪽 경계를 검사한다.
     *
     * 오른쪽([boundaryOk])과 원칙은 같지만 **조사 연쇄 허용이 없다** — 조사는 체언 뒤에만 붙지
     * 앞에는 오지 않으므로 "직전이 한글 음절이 아니어야 한다"만 보면 된다. 이 검사가 없으면
     * `신청자`·`대상자` 의 `자` 처럼 복합어 중간의 한 글자가 단어 경계 없이 매칭된다.
     *
     * 로마자·숫자 경계는 §6.7 (4) 의 왼쪽 판본이다. **한글로 시작하는 표제어에는 걸지 않는다**
     * — `3개월` 의 `개월` 은 숫자 뒤에서 정상적으로 매칭돼야 한다.
     */
    private fun leftBoundaryOk(
        text: String,
        at: Int,
    ): Boolean =
        when {
            at == 0 -> true
            text[at - 1].isHangulSyllable() -> false
            else -> !(text[at].isLatinOrDigit() && text[at - 1].isLatinOrDigit())
        }

    /**
     * §6.7 (5) 길이 1 한글 표제어에서만 도는 추가 오탐 방지.
     *
     * 앞뒤 어절 경계가 "정상"인데도 표제어와 무관한 다른 것을 가리키는 두 형태가 실측에서
     * 확인됐다(easy-doc A/B 56건, 문서 051).
     *
     * 1. **수량 단위**: `200자 이내` 의 `자`(글자 수 단위)가 표제어 `자`(사람)로 잡혔다. 바로
     *    앞이 ASCII 숫자면 거부한다(전각 숫자는 실측 코퍼스에 사례가 없어 다루지 않는다).
     * 2. **가나다 목록 기호**: `자. 「학교 밖 청소년…` 처럼 줄머리(문서 시작 또는 개행 직후,
     *    공백·탭만 허용)에 단독으로 나오고 바로 뒤가 `.`/`)` 이면 항목 번호이지 표제어가 아니다.
     *
     * `부정한 방법으로 교부받은 자는`(법률문투) 은 두 조건 다 아니므로 계속 허용된다 — 규칙을
     * "길이 1 + 두 조건 중 하나"로 좁게 정의한 것이 그 매칭을 깨뜨리지 않기 위해서다.
     */
    private fun singleHangulHeadwordOk(
        text: String,
        at: Int,
        end: Int,
    ): Boolean {
        val afterDigit = at > 0 && text[at - 1].isAsciiDigit()
        val lineStart = text.lastIndexOf('\n', at - 1) + 1
        val standsAlone = (lineStart until at).all { text[it] == ' ' || text[it] == '\t' }
        val listMarker = standsAlone && end < text.length && text[end] in LIST_MARKER_SUFFIXES
        return !afterDigit && !listMarker
    }

    /** 같은 표면형을 공유하는 후보 묶음. `length` 는 매칭 길이, `entryIds` 는 §6.8 순서 그대로다. */
    private class SurfaceCandidate(
        val length: Int,
        val entryIds: List<Int>,
    )

    /** 문자 단위 트라이 노드. `entryIds` 가 있으면 그 경로가 사전에 있는 표면형이다. */
    private class TrieNode {
        val children: MutableMap<Char, TrieNode> = HashMap()
        var entryIds: List<Int>? = null
    }

    companion object {
        /**
         * 이미 파싱된 색인 조각으로 사전을 만든다.
         *
         * [surfaceIndex] 의 값(entry id 나열)은 **§6.8 승자 순서**를 담고 있으므로 정렬하지
         * 않고 그대로 보존한다. [josa] 는 조사 경계 검사용 목록이며 순서는 무의미하다
         * ([josaChainReachesWordBoundary] 참고).
         *
         * 끊어진 참조(엔트리 없는 id)는 **적재 시점에 거절한다**. 조회 시점까지 미루면 그
         * 표면형이 실제로 문서에 나오는 날에만 터져서, 배포된 색인이 깨졌다는 사실을 사용자
         * 문서가 먼저 알게 된다.
         */
        fun of(
            entries: Map<Int, DictionaryEntry>,
            surfaceIndex: Map<String, List<Int>>,
            josa: List<String>,
        ): DictionaryIndex {
            val root = TrieNode()
            surfaceIndex.forEach { (surface, ids) ->
                val dangling = ids.filterNot { it in entries }
                require(dangling.isEmpty()) {
                    "표면형 '$surface' 이(가) 없는 엔트리를 가리킨다: $dangling"
                }
                if (surface.isNotEmpty()) addSurface(root, surface, ids)
            }
            return DictionaryIndex(entries.toMap(), root, josa.filter { it.isNotEmpty() })
        }

        private fun addSurface(
            root: TrieNode,
            surface: String,
            ids: List<Int>,
        ) {
            var node = root
            for (character in surface) {
                node = node.children.getOrPut(character) { TrieNode() }
            }
            node.entryIds = (node.entryIds ?: emptyList()) + ids
        }

        /** 겹치는 매칭의 채택 순서 — priority 큰 것 → 긴 것 → 앞선 것 (§6.5). */
        private val BY_OVERLAP_PRECEDENCE: Comparator<DictionaryMatch> =
            compareByDescending<DictionaryMatch> { it.entry.priority }
                .thenByDescending { it.end - it.start }
                .thenBy { it.start }

        /** 가나다 목록 기호의 꼬리표 (`자.`, `자)`). */
        private const val LIST_MARKER_SUFFIXES = ".)"
    }
}

private fun Char.isHangulSyllable(): Boolean = this in '가'..'힣'

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

private fun Char.isLatinOrDigit(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'
