/**
 * 적용 전 검토 안내 문구. 카드가 보존할 사실을 함께 보여줄 때만 그 항목까지 대조하도록
 * 적고, 도형 이름은 안내와 체크박스가 한 출처를 쓴다. 테스트와 e2e도 이 문구를 읽는다.
 */

const SHAPES = '화살표·갈림길·막대 비례'

export type IllustrationReviewCopy = {
  /** 체크박스 위에 두는 대조 안내. */
  guidance: string
  /** 검토 확인 체크박스의 라벨. */
  label: string
}

/** 대체텍스트 수정이 열려 있어 검토 확인을 미뤄야 할 때의 안내. */
export const ILLUSTRATION_REVIEW_EDIT_HINT = '대체텍스트를 저장한 뒤 확인할 수 있습니다.'

export function illustrationReviewCopy(hasPreservedFacts: boolean): IllustrationReviewCopy {
  const basis = hasPreservedFacts ? '장면·보존할 사실' : '장면'
  return {
    guidance: `적용하기 전에 그림에 ${SHAPES}가 있다면 위 ${basis}과 맞는지 확인하세요. 맞지 않으면 적용하지 말고 이 후보를 폐기하세요.`,
    label: `${basis}과 그림(${SHAPES}), 대체텍스트가 맞는지 확인했습니다.`,
  }
}
