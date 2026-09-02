import { useEffect, useState, useSyncExternalStore } from 'react'
import { Link } from 'react-router-dom'
import { FilePlus2, FileText, Trash2 } from 'lucide-react'

import { ApiError, deleteDocument, listDocuments } from '../api/client'
import type { DocumentListItem } from '../api/types'
import { conversionPath, HOME_PATH } from '../routes/paths'
import { useWorkspace } from '../workspace/context'
import { PageHeader } from '../components/PageHeader'
import { Badge } from '../components/ui/Badge'
import type { BadgeProps } from '../components/ui/Badge'
import { Button } from '../components/ui/Button'

/** 한 번에 불러올 개수. 백엔드 기본값과 같다. */
const PAGE_SIZE = 20

/**
 * 삭제 전에 묻는 말.
 *
 * 무엇이 사라지는지(§9: 삭제되는 대상의 제목)와 되돌릴 수 없다는 사실을 함께 쓴다 —
 * 줄마다 같은 문장이 뜨면 어느 문서를 고른 것인지 확인할 방법이 대화상자 안에 없다.
 */
function deleteConfirmMessage(title: string): string {
  return `‘${title}’ 문서와 변환 결과가 즉시 삭제됩니다. 되돌릴 수 없습니다. 삭제할까요?`
}

/**
 * 기록 한 줄에서 사용자가 **지금 해야 할 일**(DESIGN.md §6.6).
 *
 * `pending`·`processing` 같은 내부 처리 상태 대신 행동으로 읽는 말만 남긴다.
 * `변환 없음`은 §6.6의 네 표현에 없지만, 계약상 존재할 수 있는 줄이라 이름을 준다.
 */
type NextAction = '변환 중' | '검수 필요' | '검수 완료' | '실패' | '변환 없음'

/**
 * 서버 상태를 「지금 해야 할 일」로 옮긴다.
 *
 * 판정 순서에 뜻이 있다.
 * 1. `status`가 `null`이면 이 문서에는 변환 행 자체가 없다 — 백엔드 목록 질의가
 *    최신 변환을 `LEFT JOIN LATERAL`로 붙이므로 `status`·`reviewed_at`·`conversion_id`가
 *    함께 `null`이 된다. 진행 중인 일이 없으니 `변환 중`이라 하면 기다리면 끝난다는
 *    거짓말이 되고, `실패`도 아니다. 일어난 일을 그대로 `변환 없음`이라 적는다.
 * 2. `failed`가 검수 여부보다 앞선다. 실패한 변환에는 검수할 초안이 없다.
 * 3. `reviewed_at`이나 `feedback_submitted_at` 중 **하나만 있어도** `검수 완료`다. 두 값은
 *    검수의 서로 다른 결말을 적은 것이라 둘 다 채워지는 것이 규칙이 아니다 — 초안이
 *    그대로 쓸 만해 한 글자도 고치지 않고 의견만 보낸 담당자는 `reviewed_at`을 영영
 *    남기지 않는다. `reviewed_at`만 보면 그 사람에게는 자기가 이미 끝낸 문서가 계속
 *    `검수 필요`로 되돌아와, 화면이 자기 일을 못 본 척하는 셈이 된다.
 * 4. 남은 `done`은 아무도 이 초안을 들여다보지 않았다는 뜻 — 할 일은 `검수 필요`다.
 */
function nextAction(
  item: Pick<DocumentListItem, 'status' | 'reviewed_at' | 'feedback_submitted_at'>,
): NextAction {
  if (item.status === null) {
    return '변환 없음'
  }
  if (item.status === 'failed') {
    return '실패'
  }
  // `!== null`이 아니라 「값이 있는가」로 묻는다. 계약은 두 키가 늘 존재한다고 정하지만
  // 그것은 서버의 약속이지 이 함수가 받는 값의 보장이 아니다 — 필드를 아직 안 싣는 서버,
  // 배포 시차로 남아 있는 옛 번들, 목을 덜 고친 테스트에서는 `undefined`가 들어온다.
  // 그때 `undefined !== null`은 **참**이라, 아무도 손대지 않은 초안이 전부 `검수 완료`로
  // 뒤집힌다. 없는 값은 「제출 안 함」으로 읽는 쪽이 안전한 오답이다.
  if (typeof item.reviewed_at === 'string' || typeof item.feedback_submitted_at === 'string') {
    return '검수 완료'
  }
  return item.status === 'done' ? '검수 필요' : '변환 중'
}

/**
 * 할 일별 배지 색.
 *
 * 색은 거들 뿐이다(§8.1) — 배지에는 문구가 늘 함께 있고, `neutral`을 뺀 각 tone은
 * 서로 다른 아이콘 모양을 그린다. 색을 못 보는 화면에서도 넷이 구분된다.
 */
const NEXT_ACTION_TONE: Record<NextAction, NonNullable<BadgeProps['tone']>> = {
  '변환 중': 'info',
  '검수 필요': 'warning',
  '검수 완료': 'success',
  실패: 'danger',
  '변환 없음': 'neutral',
}

/**
 * 원본 형식을 사람이 읽는 말로. 계약이 아직 enum을 닫지 않아 모르는 값은 대문자로 둔다.
 *
 * `text`(붙여넣기)와 `txt`(업로드한 평문 파일)는 서로 다른 값이라 표기도 다르다 — 둘 다
 * "텍스트"라고 적으면 이 문서가 붙여넣기인지 파일 업로드인지 목록에서 구분할 수 없다.
 */
const SOURCE_FORMAT_TEXT: Record<string, string> = {
  text: '붙여넣기',
  docx: 'DOCX',
  pdf: 'PDF',
  hwpx: 'HWPX',
  txt: 'TXT',
}

/**
 * 제목 아래 보조 정보: `DOCX · 2026. 8. 26.`(§6.6).
 *
 * 날짜만 쓴다 — 표에서 열을 하나 줄이는 것이 목적이고, 시각까지 필요한 사용자는
 * 검수 화면에서 본다. 정확한 시점은 `<time datetime>`에 그대로 남긴다.
 */
function secondaryInfo(item: DocumentListItem): string {
  const format = SOURCE_FORMAT_TEXT[item.source_format] ?? item.source_format.toUpperCase()
  return `${format} · ${new Date(item.created_at).toLocaleDateString('ko-KR')}`
}

/** 표로 담을 수 있는 최소 너비. 767px 이하는 카드 목록이다(§6.6·§10). */
const TABLE_VIEW_QUERY = '(min-width: 768px)'

function tableViewMedia(): MediaQueryList | null {
  // matchMedia가 없는 환경(jsdom)도 있다. 없으면 표로 본다 — 표는 열 이름이 붙어 있어
  // 어떤 값인지 설명이 가장 많이 남는 쪽이다.
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia(TABLE_VIEW_QUERY)
    : null
}

function subscribeToTableView(onChange: () => void): () => void {
  const media = tableViewMedia()
  media?.addEventListener('change', onChange)
  return () => media?.removeEventListener('change', onChange)
}

function getTableView(): boolean {
  return tableViewMedia()?.matches ?? true
}

/**
 * 지금 화면이 표를 담을 만큼 넓은지.
 *
 * 표와 카드를 둘 다 그려 두고 CSS로 한쪽을 감추지 않는 이유: 낭독기는 `display:none`이
 * 아닌 이상 둘 다 읽고, 감춘 쪽까지 접근성 트리에 남으면 같은 문서 목록이 두 번 들린다.
 * 표에는 열 이름과 캡션이, 카드에는 목록 의미가 붙으므로 "같은 것의 다른 모양"도
 * 아니다. 그래서 폭을 JS로 판정해 **한 벌만** DOM에 넣는다(`ReviewEditor`의 탭 판정과
 * 같은 이유다).
 */
function useTableView(): boolean {
  return useSyncExternalStore(subscribeToTableView, getTableView, () => true)
}

/**
 * 변환 기록 화면.
 *
 * 총 개수를 세지 않는 목록이라(서버가 has_more만 준다) 쪽 번호 대신 "더 보기"로
 * 이어 붙인다. 이미 본 줄이 사라지지 않아 검수 이력을 훑기에도 이쪽이 낫다.
 *
 * 조회를 offset 상태 하나에 매달아 둔 이유: "더 보기"가 직접 요청을 보내면 조회 코드가
 * 첫 쪽용과 다음 쪽용 둘로 갈린다. 버튼은 어디까지 봤는지만 바꾸고, 읽어 오는 일은 한
 * 자리에서 한다.
 */
export function HistoryPage() {
  const { workspaces, currentId: workspaceId } = useWorkspace()
  const currentName = workspaces.find((workspace) => workspace.id === workspaceId)?.name ?? null
  const tableView = useTableView()
  const [offset, setOffset] = useState(0)
  const [items, setItems] = useState<DocumentListItem[]>([])
  const [hasMore, setHasMore] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // 화면이 나타나는 즉시 첫 조회가 시작되므로 처음부터 true다.
  const [loading, setLoading] = useState(true)
  // 삭제 뒤 같은 offset(0)으로 다시 읽으려면 조회를 다시 걸 신호가 하나 더 필요하다 —
  // offset만 보고 있으면 "0에서 0으로" 바뀌지 않아 effect가 돌지 않는다.
  const [reloadToken, setReloadToken] = useState(0)
  const [deletingId, setDeletingId] = useState<string | null>(null)
  // 작업 공간이 바뀌면 첫 쪽부터 다시 본다. 렌더 중에 맞추는 이유: effect로 미루면
  // 바뀐 작업 공간과 예전 offset으로 한 번 더 조회가 나가고, 그 응답이 잘못된 목록을
  // 잠깐 보여준다(React 공식 "렌더 중 상태 조정" 패턴).
  const [renderedWorkspaceId, setRenderedWorkspaceId] = useState(workspaceId)
  if (renderedWorkspaceId !== workspaceId) {
    setRenderedWorkspaceId(workspaceId)
    setOffset(0)
    setItems([])
    setHasMore(false)
    setLoading(true)
  }

  useEffect(() => {
    const controller = new AbortController()

    async function load(): Promise<void> {
      try {
        const page = await listDocuments(
          {
            limit: PAGE_SIZE,
            offset,
            // 아직 목록을 못 받았으면 거르지 않는다 — 전체를 보여주는 편이 빈 화면보다 낫다.
            ...(workspaceId === null ? {} : { workspaceId }),
          },
          controller.signal,
        )
        // 첫 쪽은 갈아 끼우고 다음 쪽은 이어 붙인다.
        setItems((previous) => (offset === 0 ? page.items : [...previous, ...page.items]))
        setHasMore(page.has_more)
        setError(null)
      } catch (caught) {
        if (caught instanceof DOMException && caught.name === 'AbortError') {
          // 화면을 떠나 취소된 요청이다 — 사용자에게 알릴 일이 아니다.
          return
        }
        setError(
          caught instanceof ApiError
            ? caught.message
            : '변환 기록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
        )
      } finally {
        setLoading(false)
      }
    }

    void load()
    return () => controller.abort()
  }, [offset, reloadToken, workspaceId])

  /**
   * 문서를 파기한다. 되돌릴 수 없으므로 반드시 먼저 묻는다.
   *
   * 지운 뒤 첫 쪽부터 다시 읽는 이유: 삭제로 다음 쪽 경계가 한 칸 밀려, 이어 붙여
   * 둔 쪽을 그대로 두면 아직 못 본 문서가 조용히 건너뛰어진다.
   */
  async function handleDelete(item: DocumentListItem): Promise<void> {
    if (!window.confirm(deleteConfirmMessage(item.title))) {
      return
    }
    setDeletingId(item.id)
    try {
      await deleteDocument(item.id)
      setError(null)
      setLoading(true)
      setOffset(0)
      setReloadToken((token) => token + 1)
    } catch (caught) {
      setError(
        caught instanceof ApiError
          ? caught.message
          : '문서를 삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      )
    } finally {
      setDeletingId(null)
    }
  }

  /**
   * 대표 행 행동은 제목 링크 하나다(§6.6).
   *
   * `conversion_id`가 `null`이면 변환 행이 없어 열 화면 자체가 없다 — 링크로 만들면
   * 어디로도 가지 않는 통로가 생긴다. 이때는 제목을 그대로 두고, 왜 열 수 없는지는
   * 옆의 `변환 없음` 배지가 말한다.
   */
  function documentTitle(item: DocumentListItem) {
    // 모바일에서만 터치 대상 44px을 확보한다(§10). 표에서까지 44px을 잡으면 두 줄짜리
    // 제목 셀이 §6.6의 56~64px 행 높이를 넘긴다. 표에서는 22px 줄 높이를 고정해
    // 보조 정보 16px·셀 여백 24px과 합쳐 62px에 앉힌다.
    const shape = tableView
      ? 'text-[15px] font-semibold leading-[22px]'
      : 'inline-flex min-h-11 items-center text-[17px] font-bold leading-6'
    return item.conversion_id === null ? (
      <span className={shape}>{item.title}</span>
    ) : (
      <Link
        className={`${shape} text-primary underline-offset-4 hover:underline`}
        to={conversionPath(item.conversion_id)}
      >
        {item.title}
      </Link>
    )
  }

  /** 지금 할 일 배지. 색만이 아니라 문구와 아이콘 모양으로도 구분된다(§8.1). */
  function actionBadge(item: DocumentListItem) {
    const action = nextAction(item)
    return <Badge tone={NEXT_ACTION_TONE[action]}>{action}</Badge>
  }

  /** 삭제는 낮은 강조로 행 끝에 둔다(§6.6). */
  function deleteButton(item: DocumentListItem) {
    return (
      <Button
        // 모바일 카드에서는 터치 대상 44×44px을 지킨다(§10).
        className={tableView ? undefined : 'min-h-11 min-w-11'}
        variant="ghost"
        size="sm"
        type="button"
        // 줄마다 같은 "삭제"가 반복되므로 어떤 문서인지 이름에 실어 준다.
        aria-label={`${item.title} 삭제`}
        onClick={() => void handleDelete(item)}
        disabled={deletingId === item.id}
      >
        <Trash2 className="size-4" aria-hidden="true" />
        삭제
      </Button>
    )
  }

  /** 표 캡션과 카드 목록 이름이 같은 문장을 쓴다 — 모양이 달라도 보는 범위는 같다. */
  const listDescription =
    currentName === null
      ? '내가 변환한 문서 목록입니다.'
      : `‘${currentName}’에서 변환한 문서 목록입니다.`

  return (
    <section aria-labelledby="history-heading">
      <PageHeader
        // 어느 작업 공간의 기록을 보고 있는지가 맥락이다(DESIGN.md §6.6) — 작업 공간을
        // 바꾸면 라벨과 설명이 함께 바뀌어 선택 결과가 화면에서 곧바로 보인다.
        context={currentName === null ? '변환 기록' : `${currentName} · 변환 기록`}
        title="변환한 문서를 확인합니다"
        description={
          currentName === null
            ? '문서의 변환 상태를 보고, 이어서 검수하거나 삭제할 수 있습니다.'
            : `‘${currentName}’에서 변환한 문서의 상태를 보고, 이어서 검수하거나 삭제할 수 있습니다.`
        }
        titleId="history-heading"
        action={{ label: '새 문서 변환', to: HOME_PATH, icon: FilePlus2 }}
      />

      <div className="flex flex-col gap-6">
        {error !== null && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}

        <div className="rounded-[12px] border border-border bg-card px-5 pb-5 shadow-[0_1px_2px_rgba(20,33,31,0.04)]">
          {items.length === 0 && !loading && error === null ? (
            // 빈 상태도 지금 작업 공간의 이야기로 말하고, 다음 할 일 하나를 함께 준다(§6.6).
            <div className="flex flex-col items-center gap-4 py-14 text-center">
              <FileText className="size-8 text-muted-foreground" aria-hidden="true" />
              <p className="text-muted-foreground">
                {currentName === null
                  ? '아직 변환한 문서가 없습니다.'
                  : `‘${currentName}’에는 아직 변환한 문서가 없습니다.`}
              </p>
              <Link
                className="inline-flex h-11 items-center justify-center gap-2 rounded-md bg-primary px-4 text-[15px] font-semibold text-primary-foreground no-underline transition-colors hover:bg-primary-hover"
                to={HOME_PATH}
              >
                <FilePlus2 className="size-[18px]" aria-hidden="true" />첫 문서 변환하기
              </Link>
            </div>
          ) : tableView ? (
            <table className="history-table">
              {/* 어느 작업 공간을 보고 있는지 표 설명에 적는다 — 목록이 걸러졌다는 사실이
              화면을 보지 않는 사용자에게도 전달되어야 한다(KWCAG). */}
              <caption>{listDescription} 제목을 누르면 검수 화면이 열립니다.</caption>
              <thead>
                <tr>
                  <th scope="col">제목</th>
                  {/* 두 번째 열은 처리 상태가 아니라 사용자가 지금 할 일이다(§6.6). */}
                  <th scope="col">지금 할 일</th>
                  <th scope="col">글자 수</th>
                  <th scope="col">삭제</th>
                </tr>
              </thead>
              <tbody>
                {items.map((item) => (
                  // 행 높이 56~64px(§6.6). 제목 22px + 보조 정보 16px에 셀 위아래 여백
                  // 24px을 더해 62px에 앉으므로, h-[60px]은 그보다 짧아지지 않게 하는 바닥이다.
                  <tr className="h-[60px]" key={item.id}>
                    <th scope="row">
                      {documentTitle(item)}
                      <p className="text-[13px] font-normal leading-4 text-muted-foreground">
                        {secondaryInfo(item)}
                      </p>
                    </th>
                    <td>{actionBadge(item)}</td>
                    <td>{item.char_count.toLocaleString('ko-KR')}자</td>
                    <td>{deleteButton(item)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            // 767px 이하에서는 표를 가로로 밀지 않고 카드 목록으로 바꾼다(§6.6·§10).
            // 카드 안에서도 §6.6의 위계를 DOM 순서로 지킨다: 제목 → 할 일 → 보조 정보 → 삭제.
            <ul aria-label={listDescription} className="flex flex-col gap-3 py-4">
              {items.map((item) => (
                <li
                  className="flex flex-col items-start gap-2 rounded-[12px] border border-border p-4"
                  key={item.id}
                >
                  {documentTitle(item)}
                  {actionBadge(item)}
                  <p className="text-[13px] leading-4 text-muted-foreground">
                    {secondaryInfo(item)} · {item.char_count.toLocaleString('ko-KR')}자
                  </p>
                  {deleteButton(item)}
                </li>
              ))}
            </ul>
          )}

          {loading && (
            <p className="py-6 text-center text-sm text-primary" role="status">
              불러오는 중입니다…
            </p>
          )}

          {hasMore && (
            <Button
              variant="outline"
              type="button"
              onClick={() => {
                setLoading(true)
                setOffset(items.length)
              }}
              disabled={loading}
            >
              더 보기
            </Button>
          )}
        </div>
      </div>
    </section>
  )
}
