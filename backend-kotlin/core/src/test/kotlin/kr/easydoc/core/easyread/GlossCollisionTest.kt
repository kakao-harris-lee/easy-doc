package kr.easydoc.core.easyread

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** 치환 비문(뜻풀이 축자 삽입) 검출. */
class GlossCollisionTest {
    @Test
    @DisplayName("패턴 ① 명사형 뜻풀이 + 용언")
    fun `내어 줌 받아 를 잡는다`() {
        assertThat(findGlossCollisions("신청서를 내어 줌 받아 주세요.")).containsExactly("내어 줌")
    }

    @Test
    @DisplayName("패턴 ② 한 낱말짜리 명사형 뜻풀이 + 체언")
    fun `뽑음 결과 를 잡는다`() {
        assertThat(findGlossCollisions("뽑음 결과를 알려 드립니다.")).containsExactly("뽑음")
    }

    @Test
    @DisplayName("패턴 ③ 복합어 앞자리 낱말 + 관형구 뜻풀이")
    fun `신청 정해진 날 을 잡는다`() {
        // 기존에는 "기한"(정해진 날짜)으로 이 패턴을 확인했으나, "기한"이
        // easy-dictionary 소비자 중복 정책으로 사전에서 빠지면서 COMPOUND_TAIL_KEYS
        // 에서도 빠졌다(GlossCollision.kt 주석 참고). 같은 패턴을 여전히 검증하는
        // "기일"(정해진 날)로 바꿔 확인한다.
        assertThat(findGlossCollisions("신청 정해진 날까지 내세요.")).containsExactly("정해진 날")
        assertThat(findGlossCollisions("접수 정해진 날이 지났습니다.")).containsExactly("정해진 날")
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [

            "도움 받으실 수 있습니다.",
            "배움을 원하는 분은 신청하세요.",

            "알림 문자를 받으세요.",
            "돌봄 서비스를 신청하세요.",
            "이름 하나만 적으세요.",
            "걸림 없이 진행합니다.",

            "줄바꿈 기준으로 나눕니다.",
        ],
    )
    @DisplayName("정상 표현을 비문으로 잡지 않는다")
    fun `오탐이 없다`(sentence: String) {
        assertThat(findGlossCollisions(sentence)).isEmpty()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "매달 정해진 금액을 드립니다.",
            "미리 정해진 날에 오세요.",
            "학생에게 정해진 금액을 드립니다.",
        ],
    )
    @DisplayName("부사·시간명사가 관형구를 꾸미는 정상 문장은 통과한다")
    fun `복합어 앞자리 열거가 오탐을 막는다`(sentence: String) {
        assertThat(findGlossCollisions(sentence)).isEmpty()
    }

    @Test
    fun `비문이 없는 문장은 빈 목록이다`() {
        assertThat(findGlossCollisions("오늘 안에 서류를 내세요.")).isEmpty()
    }

    @Test
    fun `빈 입력에서 예외를 던지지 않는다`() {
        assertThat(findGlossCollisions("")).isEmpty()
    }

    @Test
    @DisplayName("검출된 비문은 스타일 검사 결과에 재서술 지시로 실린다")
    fun `checkStyle 이 치환 비문을 지적한다`() {
        val result = checkStyle("뽑음 결과를 알려 드립니다.")
        val issue = result.issues.single { it.reason.startsWith("뜻풀이 축자 삽입") }

        assertThat(issue.reason).isEqualTo("뜻풀이 축자 삽입(뽑음) — 그 뜻이 통하게 문장을 자연스럽게 다시 쓸 것")

        assertThat(issue.word).isNull()
    }
}
