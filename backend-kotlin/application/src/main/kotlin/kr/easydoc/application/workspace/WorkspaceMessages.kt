package kr.easydoc.application.workspace

// 작업 공간 유스케이스가 사용자에게 내보내는 문구.
//
// ## 왜 한 파일에 모으는가
//
// 이 중 둘은 **두 계층이 함께 쓴다.** 「문서가 남아 있다」는 유스케이스가 트랜잭션 안에서
// 세어 판정하지만, 그 판정과 DELETE 사이에 문서가 들어오면 같은 결론을 DB 의 외래 키가
// 대신 낸다(`fk_documents_workspace_id_workspaces`, `ON DELETE` 없음). 두 층이 같은 상황을
// 각자 적으면 **한쪽만 고쳐지는 날**이 온다 — 같은 409 인데 문구가 갈리면 화면이 두 가지
// 안내를 하게 되고, 사용자는 무엇이 다른지 알 수 없다.
//
// 문구를 값으로 두는 것은 문구가 **응답 바이트**이기 때문이다. 계약이 정하는 것은 나간
// 바이트까지이고, 예시가 있는 문구는 계약이 정본이다.
//
// ## 이름 규칙 위반 문구는 여기 없다
//
// 그쪽(`WorkspaceNameRules`)은 판정과 문구가 같은 함수 안에서 끝나고 다른 계층이 쓰지
// 않는다. 쓰이지 않는 자리까지 공개 상수로 올리면 「어디서 쓰이는가」가 흐려진다.

/** 없는 자원과 남의 자원이 **함께 쓰는** 문구. */
const val WORKSPACE_NOT_FOUND_MESSAGE: String = "작업 공간을 찾을 수 없습니다"

/** 계약 `delete.responses.409.examples.last_one`. */
const val LAST_WORKSPACE_MESSAGE: String = "작업 공간은 적어도 하나 있어야 합니다"

/** 계약 `delete.responses.409.examples.has_documents`. 유스케이스와 저장소가 함께 쓴다. */
const val WORKSPACE_HAS_DOCUMENTS_MESSAGE: String = "작업 공간에 문서가 남아 있습니다 — 먼저 비운 뒤 삭제해 주세요"

/** 같은 사용자 안에서 이름이 겹쳤다 — `uq_workspaces_user_id_name`. */
const val DUPLICATE_WORKSPACE_NAME_MESSAGE: String = "같은 이름의 작업 공간이 이미 있습니다"
