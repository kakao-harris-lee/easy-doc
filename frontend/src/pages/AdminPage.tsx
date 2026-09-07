import { useId, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'

import { cn } from '../lib/utils'
import { PageHeader } from '../components/PageHeader'
import { AdminAnnouncementsTab } from './admin/AdminAnnouncementsTab'
import { AdminErrorsTab } from './admin/AdminErrorsTab'
import { AdminInvoicesTab } from './admin/AdminInvoicesTab'
import { AdminWorkspacesTab } from './admin/AdminWorkspacesTab'

type TabKey = 'workspaces' | 'invoices' | 'errors' | 'announcements'

const TABS: readonly { key: TabKey; label: string }[] = [
  { key: 'workspaces', label: '워크스페이스' },
  { key: 'invoices', label: '세금계산서' },
  { key: 'errors', label: '오류' },
  { key: 'announcements', label: '공지' },
]

/**
 * 관리자 화면 (어드민 최소, 계약 2.25.0).
 *
 * `/admin`은 `RequireAdmin`이 감싼다 — `user.is_admin`이 아니면 이 화면 자체가 그려지지
 * 않는다(routes/RequireAdmin.tsx). 여기서는 관리자 API 403을 다시 신경 쓰지 않아도
 * 되지만, 각 탭은 그래도 서버 문구를 그대로 보여준다(회수가 즉시 반영되는 경우를 대비).
 *
 * 4개 탭을 WAI-ARIA 탭 패턴으로 묶는다(`ReviewEditor`의 탭과 같은 구현) — 화살표
 * 좌우·Home·End로 이동하고, 선택은 초점을 따라간다.
 */
export function AdminPage() {
  const [activeTab, setActiveTab] = useState<TabKey>('workspaces')
  const tabRefs = useRef<Partial<Record<TabKey, HTMLButtonElement | null>>>({})
  const panelBaseId = useId()

  function handleTabKeyDown(event: KeyboardEvent<HTMLButtonElement>): void {
    const keys = TABS.map((tab) => tab.key)
    const current = keys.indexOf(activeTab)
    const step = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : null
    const next =
      step !== null
        ? keys[(current + step + keys.length) % keys.length]
        : event.key === 'Home'
          ? keys[0]
          : event.key === 'End'
            ? keys[keys.length - 1]
            : undefined
    if (next === undefined) {
      return
    }
    event.preventDefault()
    setActiveTab(next)
    tabRefs.current[next]?.focus()
  }

  return (
    <section aria-labelledby="admin-heading">
      <PageHeader
        context="관리자"
        title="고객·사용량·오류를 관리합니다"
        description="워크스페이스와 크레딧을 조회하고, 세금계산서 요청을 처리하고, 오류와 공지를 관리합니다."
        titleId="admin-heading"
      />

      <div
        className="mb-4 flex flex-wrap gap-1 rounded-[12px] border border-border bg-muted p-1"
        role="tablist"
        aria-label="관리자 화면"
      >
        {TABS.map((tab) => (
          <button
            key={tab.key}
            ref={(node) => {
              tabRefs.current[tab.key] = node
            }}
            type="button"
            role="tab"
            id={`${panelBaseId}-${tab.key}-tab`}
            aria-selected={activeTab === tab.key}
            aria-controls={`${panelBaseId}-${tab.key}-panel`}
            tabIndex={activeTab === tab.key ? 0 : -1}
            className={cn(
              'flex h-11 flex-1 items-center justify-center rounded-[10px] px-3 text-[15px] font-semibold transition-colors',
              activeTab === tab.key
                ? 'bg-card text-primary shadow-sm'
                : 'text-muted-foreground hover:text-foreground',
            )}
            onClick={() => setActiveTab(tab.key)}
            onKeyDown={handleTabKeyDown}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/*
        네 패널 모두 계속 마운트해 둔다(`hidden` 속성으로만 감춘다) — `ReviewEditor`의
        원문/결과 패널과 같은 이유다. 조건부 마운트(`activeTab === key && <Tab/>`)로
        해 두면 탭을 벗어났다가 돌아올 때마다 그 탭이 처음부터 다시 그려져 검색어·쪽
        번호·선택한 워크스페이스 같은 로컬 상태가 날아간다.
      */}
      <div
        role="tabpanel"
        id={`${panelBaseId}-workspaces-panel`}
        aria-labelledby={`${panelBaseId}-workspaces-tab`}
        hidden={activeTab !== 'workspaces'}
      >
        <AdminWorkspacesTab />
      </div>
      <div
        role="tabpanel"
        id={`${panelBaseId}-invoices-panel`}
        aria-labelledby={`${panelBaseId}-invoices-tab`}
        hidden={activeTab !== 'invoices'}
      >
        <AdminInvoicesTab />
      </div>
      <div
        role="tabpanel"
        id={`${panelBaseId}-errors-panel`}
        aria-labelledby={`${panelBaseId}-errors-tab`}
        hidden={activeTab !== 'errors'}
      >
        <AdminErrorsTab />
      </div>
      <div
        role="tabpanel"
        id={`${panelBaseId}-announcements-panel`}
        aria-labelledby={`${panelBaseId}-announcements-tab`}
        hidden={activeTab !== 'announcements'}
      >
        <AdminAnnouncementsTab />
      </div>
    </section>
  )
}
