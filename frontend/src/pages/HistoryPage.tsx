import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'

import { ApiError, listDocuments } from '../api/client'
import type { ConversionStatus, DocumentListItem } from '../api/types'
import { conversionPath, HOME_PATH } from '../routes/paths'

/** 한 번에 불러올 개수. 백엔드 기본값과 같다. */
const PAGE_SIZE = 20

/** 상태 코드 → 사람이 읽는 말. */
const STATUS_TEXT: Record<ConversionStatus, string> = {
  pending: '대기 중',
  processing: '변환 중',
  done: '변환 완료',
  failed: '변환 실패',
}

/** 날짜를 한국어 표기로. 시각까지 보여준다(같은 날 여러 건을 가르는 기준이다). */
function formatDate(value: string): string {
  return new Date(value).toLocaleString('ko-KR')
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
  const [offset, setOffset] = useState(0)
  const [items, setItems] = useState<DocumentListItem[]>([])
  const [hasMore, setHasMore] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // 화면이 나타나는 즉시 첫 조회가 시작되므로 처음부터 true다.
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const controller = new AbortController()

    async function load(): Promise<void> {
      try {
        const page = await listDocuments({ limit: PAGE_SIZE, offset }, controller.signal)
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
  }, [offset])

  return (
    <section aria-labelledby="history-heading">
      <h2 id="history-heading">변환 기록</h2>

      {error !== null && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}

      {items.length === 0 && !loading && error === null ? (
        <p>
          아직 변환한 문서가 없습니다. <Link to={HOME_PATH}>문서를 올려 보세요.</Link>
        </p>
      ) : (
        <table className="history-table">
          <caption>내가 변환한 문서 목록입니다. 제목을 누르면 검수 화면이 열립니다.</caption>
          <thead>
            <tr>
              <th scope="col">제목</th>
              <th scope="col">상태</th>
              <th scope="col">글자 수</th>
              <th scope="col">올린 날짜</th>
              <th scope="col">검수</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <tr key={item.id}>
                <th scope="row">
                  {/* 변환 행이 없으면 열 화면도 없다 — 링크 대신 제목만 보여준다. */}
                  {item.conversion_id === null ? (
                    item.title
                  ) : (
                    <Link to={conversionPath(item.conversion_id)}>{item.title}</Link>
                  )}
                </th>
                <td>{item.status === null ? '알 수 없음' : STATUS_TEXT[item.status]}</td>
                <td>{item.char_count.toLocaleString('ko-KR')}자</td>
                <td>{formatDate(item.created_at)}</td>
                <td>{item.reviewed_at === null ? '초안' : '검수함'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {loading && <p role="status">불러오는 중입니다…</p>}

      {hasMore && (
        <button
          type="button"
          onClick={() => {
            setLoading(true)
            setOffset(items.length)
          }}
          disabled={loading}
        >
          더 보기
        </button>
      )}
    </section>
  )
}
