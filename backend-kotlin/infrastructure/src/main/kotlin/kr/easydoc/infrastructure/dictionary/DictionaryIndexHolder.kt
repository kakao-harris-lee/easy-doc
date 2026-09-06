package kr.easydoc.infrastructure.dictionary

import kr.easydoc.core.dictionary.DictionaryIndex

/**
 * 사전 색인을 **최대 한 번, 그리고 실제로 필요할 때만** 읽는 보관소 (2026-09-06 정정,
 * `docs/kotlin-redevelopment-backlog.md` §1.1).
 *
 * ## 왜 `@Bean` 이 직접 읽으면 안 되는가
 *
 * `DictionaryConfiguration` 은 `@Profile` 이 없어 API 전용 프로세스에도 항상 조립된다.
 * [DictionaryProperties.enabled](worker 프롬프트 주입 스위치) 의 기본값은 **켜짐**이다 —
 * 그래서 두 스위치의 합집합을 그대로 `@Bean` 메서드 본문에서 계산해 즉석에서 색인을 읽으면,
 * Spring 이 `@Bean` 메서드를 조립 시점(`preInstantiateSingletons`)에 **즉시** 호출하는 성질과
 * 맞물려 **API 전용 컨텍스트조차** worker 스위치 기본값 때문에 조립 시점에 1.5MB 색인을
 * 무조건 읽게 된다 — 그 컨텍스트의 어떤 소비자도 실제로 색인을 요구하지 않았는데도다. 여러
 * `@SpringBootTest` 컨텍스트가 캐시돼 동시에 떠 있는 테스트 스위트에서 이 무조건 로드가
 * 겹치면 힙을 실제로 고갈시킨다(실측: `:api:test` 의 `Java heap space`).
 *
 * ## 이 클래스가 하는 일
 *
 * [enabled] 는 조립 시점에 계산해 생성자에 박아 두지만, 실제 읽기([loader] 호출)는 [indexOrNull]
 * 을 **처음 부를 때** `by lazy` 로 미룬다. `@Bean` 메서드가 이 홀더를 만드는 것은 값싸다 —
 * 객체 하나와 아직 실행되지 않은 lazy delegate 뿐이다. 소비자([DictionaryConfiguration
 * .termCandidateSource]·[kr.easydoc.infrastructure.queue.ConversionWorkerConfiguration
 * .dictionaryContextSource])는 **자기 스위치가 켜졌을 때만** [indexOrNull] 을 부르므로, 두
 * 소비자 모두 꺼져 있으면 이 홀더가 존재해도 색인은 결코 읽히지 않는다. 두 소비자가 모두
 * 켜져 있으면(worker 프로필 + API 프로필을 한 프로세스에 함께 켠 경우) 어느 쪽이 먼저 부르든
 * `by lazy` 의 기본 동기화 모드가 단 한 번만 읽어 그 결과를 공유하게 한다.
 *
 * [enabled] 가 거짓이면 [indexOrNull] 은 `lazyIndex` 에 손대지 않고 곧장 `null` 을 돌려준다 —
 * "느리게 읽는다"가 아니라 "이 실행에서는 아예 읽지 않는다"를 보장하는 안전판이다.
 *
 * ## 읽기가 실패하면
 *
 * `loader` 가 던지면(예: 클래스패스 리소스 없음) `by lazy` 는 그 실패를 **기억하지 않는다** —
 * 다음 [indexOrNull] 호출이 다시 `loader` 를 부른다(Kotlin `Lazy` 구현이 실패 시 초기화
 * 상태를 되돌린다). 다만 이것이 재시도 정책은 아니다: 이 호출은 조립 시점(Spring 기동 중)
 * 에 일어나므로 첫 실패가 곧 기동 실패이고, 기동이 실패하면 프로세스가 그대로 죽어 "다음
 * 호출"이 실제로 일어날 일이 없다 — 그러므로 이 성질은 프로덕션 동작에 영향을 주지 않는다.
 */
class DictionaryIndexHolder(
    private val enabled: Boolean,
    loader: () -> DictionaryIndex,
) {
    // `loader` 는 생성자 매개변수일 뿐 프로퍼티로 유지하지 않는다 — 아래 초기화 한 줄에만
    // 쓰이므로 클래스 필드로 남겨 별도로 참조를 붙잡을 이유가 없다.
    private val lazyIndex: DictionaryIndex by lazy(LazyThreadSafetyMode.SYNCHRONIZED, loader)

    /** [enabled] 가 거짓이면 절대 읽지 않고 `null` 이다. 참이면 처음 부를 때만 읽어 공유한다. */
    fun indexOrNull(): DictionaryIndex? = if (enabled) lazyIndex else null
}
