package kr.easydoc.core.privacy

// 이 저장소가 다루는 문서는 공공기관이 이미 공개 배포한 안내문이라 개인정보를 담지 않는다
// (제품 결정, 2026-09-07). 그래서 이 파일에는 개인정보 마스킹 로직이 없다 — 남은 것은
// 모델 초안과 사람이 검수·제출한 본문을 구분하는 두 래퍼 타입뿐이다.
//
// ReviewedBody는 실제 사용자 제출에서만 만들고, ModelDraft는 모델 출력·저장 초안에만 사용한다.
// 새 생성 지점은 ProvenanceCreationSitesTest의 인구조사로 드러나야 한다.

/** 검수를 거치지 않은 모델 초안 (`easy_text`). */
@JvmInline
value class ModelDraft(val value: String) {
    /** 길이만 남긴다. 사유는 「value class 와 toString」 절. */
    override fun toString(): String = "ModelDraft(${value.length}자)"
}

/** 사람이 검수 화면에서 **제출한** 본문 (`edited_text`). 제출 전에는 `null` 이다. */
@JvmInline
value class ReviewedBody(val value: String) {
    /** 길이만 남긴다. 사유는 「value class 와 toString」 절. */
    override fun toString(): String = "ReviewedBody(${value.length}자)"
}
