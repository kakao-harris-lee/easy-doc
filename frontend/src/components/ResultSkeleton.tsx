/** 스켈레톤 한 줄. 길이를 달리해 글줄처럼 보이게 한다. */
function Line({ width }: { width: string }) {
  return <span className="block h-3 rounded-full bg-muted" style={{ width }} />
}

/** 스켈레톤 한 칸(검수 화면의 한 열). 맨 위 짧은 막대는 열 제목 자리다. */
function Column() {
  return (
    <div className="flex flex-col gap-3 rounded-[12px] border border-border p-4">
      <span className="block h-3 w-20 rounded-full bg-muted-foreground/25" />
      <Line width="100%" />
      <Line width="92%" />
      <Line width="97%" />
      <Line width="70%" />
      <Line width="88%" />
      <Line width="45%" />
    </div>
  )
}

/**
 * 다음 화면(검수 2열)의 형태를 미리 보여주는 스켈레톤(§6.3).
 *
 * 두 가지 규칙을 지킨다.
 *
 * 1. **하나의 로딩 상태 이름만 낭독한다**(§11). 블록 하나하나가 읽히면 의미 없는 소리가
 *    수십 번 나므로, 바깥을 `role="img"`로 묶어 이름을 한 번만 준다 — `role="img"`는
 *    자식을 표현 전용으로 만들어 안쪽 블록이 따로 읽히지 않는다.
 * 2. **움직이지 않는다**(§12). 이 화면의 반복 모션은 진행 문장 옆 회전 표시 하나뿐이다.
 *    스켈레톤까지 깜빡이면 한 화면에 반복 모션이 둘이 된다.
 */
export function ResultSkeleton() {
  return (
    <div
      role="img"
      aria-label="검수 화면 미리보기입니다. 결과를 준비하고 있습니다."
      className="grid gap-4 sm:grid-cols-2"
    >
      <Column />
      <Column />
    </div>
  )
}
