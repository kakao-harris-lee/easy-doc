package kr.easydoc.core.dictionary

// 사전 테스트가 공유하는 **손으로 만든 작은 색인**.
//
// 1.5MB 실제 `easy_dict.index.json` 을 여기서 읽지 않는다 — core 본 소스에 JSON 라이브러리가
// 없고(core/build.gradle.kts), 실제 색인과의 대조는 그 파일을 파싱하는 infrastructure 어댑터
// 테스트 몫이다. 여기 테스트는 `dictionary/DESIGN.md` §6.7 이 실측 결함에서 도출한 규칙을
// **하나씩** 겨눈다 — 규칙마다 사례를 하나만 두어야 실패했을 때 어느 규칙이 깨졌는지가
// 테스트 이름으로 바로 읽힌다.

/**
 * 조사 목록 — 실제 색인의 `josa`(44개)를 그대로 옮겼다.
 *
 * 제품에서는 이 목록이 `index.json` 에서 들어오므로 core 는 값을 소유하지 않는다. 그런데
 * §6.7 (3)(조사 경계) 테스트는 `에서`+`는` 같은 **연쇄**를 실제로 밟아야 의미가 있어서,
 * 축약한 목록이 아니라 제품과 같은 목록을 쓴다.
 */
internal val TEST_JOSA: List<String> =
    (
        "은 는 이 가 을 를 에 에서 에게 에겐 께 한테 으로 로 로서 으로서 로써 으로써 와 과 의 도 만 " +
            "부터 까지 이나 나 보다 처럼 라도 이라도 조차 마저 밖에 이란 란 이라는 라는 입니다 이다 " +
            "이며 이고 인 임"
    ).split(" ")

/**
 * 엔트리와 표면형을 함께 쌓아 [DictionaryIndex] 를 만든다.
 *
 * 표제어(`term`)는 언제나 자기 표면형으로 자동 등록된다 — 실제 `export.py` 가 그렇게 굽고,
 * §6.7 (0)(표면형 소유권)이 "표면형이 자기 표제어인 엔트리"를 찾을 수 있어야 하기 때문이다.
 * 변형형은 [add] 의 가변 인자로 따로 준다.
 */
internal class DictionaryFixture {
    private val entries = LinkedHashMap<Int, DictionaryEntry>()
    private val surfaces = LinkedHashMap<String, MutableList<Int>>()
    private var lastId = 0

    fun add(
        entry: DictionaryEntry,
        vararg variants: String,
    ): DictionaryFixture {
        lastId += 1
        entries[lastId] = entry
        surfaces.getOrPut(entry.term) { mutableListOf() }.add(lastId)
        variants.forEach { surfaces.getOrPut(it) { mutableListOf() }.add(lastId) }
        return this
    }

    fun build(): DictionaryIndex = DictionaryIndex.of(entries, surfaces, TEST_JOSA)
}
