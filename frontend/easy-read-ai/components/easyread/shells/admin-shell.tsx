'use client'

import * as React from 'react'
import { NavLink, useNavigate } from 'react-router-dom'
import { cn } from '@/lib/utils'
import { Logo } from '../ui/logo'
import { Menu } from '../ui/menu'
import { SkipLink } from '../ui/layout-bits'
import { useAuth } from '../lib/auth'
import {
  LayoutDashboard,
  Building2,
  Repeat,
  Coins,
  FileText,
  SlidersHorizontal,
  Megaphone,
  ScrollText,
  UserCircle2,
  LogOut,
  ArrowLeftRight,
  Menu as MenuIcon,
  X,
} from 'lucide-react'

const nav = [
  { to: '/admin', label: '대시보드', icon: LayoutDashboard, end: true },
  { to: '/admin/customers', label: '고객 기관', icon: Building2 },
  { to: '/admin/subscriptions', label: '구독 관리', icon: Repeat },
  { to: '/admin/usage', label: '사용량·비용', icon: Coins },
  { to: '/admin/conversions', label: '변환 내역', icon: FileText },
  { to: '/admin/adjustments', label: '토큰 조정', icon: SlidersHorizontal },
  { to: '/admin/notices', label: '공지 관리', icon: Megaphone },
  { to: '/admin/audit', label: '감사 로그', icon: ScrollText },
]

export function AdminShell({ children }: { children: React.ReactNode }) {
  const navigate = useNavigate()
  const { user, logout } = useAuth()
  const [mobileOpen, setMobileOpen] = React.useState(false)

  function handleLogout() {
    logout()
    navigate('/')
  }

  const linkClass = ({ isActive }: { isActive: boolean }) =>
    cn(
      'flex items-center gap-3 rounded-[10px] px-3 py-2.5 text-[15px] font-semibold transition-colors focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-white/70',
      isActive
        ? 'bg-white/15 text-white'
        : 'text-white/70 hover:bg-white/10 hover:text-white',
    )

  const SidebarNav = (
    <nav aria-label="운영자 메뉴" className="flex flex-col gap-1">
      {nav.map((item) => (
        <NavLink
          key={item.to}
          to={item.to}
          end={item.end}
          onClick={() => setMobileOpen(false)}
          className={linkClass}
        >
          <item.icon className="size-[18px] shrink-0" aria-hidden="true" />
          {item.label}
        </NavLink>
      ))}
    </nav>
  )

  return (
    <div className="min-h-dvh bg-background lg:flex">
      <SkipLink />

      {/* Desktop sidebar */}
      <aside className="sticky top-0 hidden h-dvh w-64 shrink-0 flex-col bg-admin px-4 py-5 lg:flex">
        <NavLink to="/admin" className="rounded-md px-1 focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-white/70">
          <Logo variant="admin" className="[&_span]:text-white [&_.text-muted-foreground]:text-white/60" />
        </NavLink>
        <div className="mt-6 flex-1">{SidebarNav}</div>
        <div className="mt-4 rounded-[12px] bg-white/10 p-3">
          <p className="text-sm font-semibold text-white">운영 환경</p>
          <p className="mt-0.5 text-xs text-white/70">
            변경 사항은 감사 로그에 기록됩니다.
          </p>
        </div>
      </aside>

      <div className="flex min-h-dvh flex-1 flex-col">
        {/* Top bar */}
        <header className="sticky top-0 z-40 border-b border-border bg-card/95 backdrop-blur">
          <div className="flex h-16 items-center gap-3 px-4 sm:px-6">
            <button
              type="button"
              aria-label={mobileOpen ? '메뉴 닫기' : '메뉴 열기'}
              aria-expanded={mobileOpen}
              onClick={() => setMobileOpen((o) => !o)}
              className="flex size-10 items-center justify-center rounded-[10px] border border-border text-foreground hover:bg-secondary focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring lg:hidden"
            >
              {mobileOpen ? <X className="size-5" /> : <MenuIcon className="size-5" />}
            </button>

            <div className="flex items-center gap-2 lg:hidden">
              <Logo variant="admin" />
            </div>

            <p className="ml-1 hidden text-sm font-semibold text-muted-foreground lg:block">
              운영 관리자 콘솔
            </p>

            <div className="ml-auto flex items-center gap-3">
              <Menu
                align="end"
                header={
                  <div className="flex flex-col gap-0.5">
                    <span className="text-sm font-bold text-foreground">{user?.name ?? '운영자'} 운영자</span>
                    <span className="text-xs text-muted-foreground">{user?.org ?? '플랫폼 운영팀'}</span>
                  </div>
                }
                items={[
                  { label: '기관 사용자 화면으로 전환', icon: ArrowLeftRight, onSelect: () => navigate('/app') },
                  { label: '로그아웃', icon: LogOut, danger: true, onSelect: handleLogout },
                ]}
                trigger={({ toggle, ref, open }) => (
                  <button
                    ref={ref}
                    onClick={toggle}
                    aria-haspopup="menu"
                    aria-expanded={open}
                    className="flex items-center gap-2 rounded-full border border-border bg-card py-1 pl-1 pr-2.5 transition-colors hover:bg-secondary focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring"
                  >
                    <span className="flex size-8 items-center justify-center rounded-full bg-admin-surface text-admin">
                      <UserCircle2 className="size-5" aria-hidden="true" />
                    </span>
                    <span className="hidden text-sm font-semibold text-foreground sm:inline">
                      {user?.name ?? '운영자'}
                    </span>
                  </button>
                )}
              />
            </div>
          </div>

          {mobileOpen && (
            <div className="border-t border-border bg-admin px-4 py-3 lg:hidden">
              {SidebarNav}
            </div>
          )}
        </header>

        <main id="main" className="flex-1 px-4 py-8 sm:px-6">
          <div className="mx-auto w-full max-w-6xl">{children}</div>
        </main>
      </div>
    </div>
  )
}
