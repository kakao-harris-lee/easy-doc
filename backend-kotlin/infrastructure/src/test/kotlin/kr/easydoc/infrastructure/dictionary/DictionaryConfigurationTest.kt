package kr.easydoc.infrastructure.dictionary

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 색인이 worker 프로필 밖에서도 조립될 수 있는지, 꺼져 있으면 읽지 않는지 확인한다. */
class DictionaryConfigurationTest {
    @Test
    @DisplayName("기본값은 꺼짐이다 - API 기동이 이번 변경만으로 색인을 읽게 되지 않는다")
    fun `기본값은 꺼짐이다`() {
        assertThat(DictionaryLookupProperties().enabled).isFalse()
    }

    @Test
    @DisplayName("꺼져 있으면 null 이다 - 빈이 등록되지 않아 색인을 읽지 않는다")
    fun `꺼져 있으면 적재하지 않는다`() {
        val index = DictionaryConfiguration().dictionaryIndex(DictionaryLookupProperties(enabled = false))

        assertThat(index).isNull()
    }

    @Test
    @DisplayName("켜면 실제 색인을 적재한다")
    fun `켜면 적재한다`() {
        val index = DictionaryConfiguration().dictionaryIndex(DictionaryLookupProperties(enabled = true))

        assertThat(index).isNotNull()
        assertThat(index!!.findAll("구비서류를 지참하세요")).isNotEmpty()
    }
}
