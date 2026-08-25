'use client'

import * as React from 'react'
import { NavLink, useNavigate } from 'react-router-dom'
import { cn } from '@/lib/utils'
import { Logo } from '../ui/logo'
import { Menu } from '../ui/menu'
import { Badge } from '../ui/badge'
import { Button } from '../ui/button'
import { SkipLink } from '../ui/layout-bits'
import { useAuth } from '../lib/auth'
import {
  FilePlus2,
  History,
  CreditCard,
  Users,
  Coins,
  UserCircle2,
  Settings,
  LogOut,
  ShieldCheck,
  Menu as MenuIcon,
  X,
} from 'lucide-react'
import { formatNum, usageSummary } from '../lib/mock-data'

export function UserShell({ children }: { children: React.ReactNode }) {
  const navigate = useNavigate()
  const { user, logout } = useAuth()
  const [mobileOpen, setMobileOpen] = React.useState(false)

  const canManageTeam = user?.role === 'org' || user?.role === 'admin'
  const isAdmin = user?.role === 'admin'

  // 팀 관리 메뉴는 기관 담당자(및 운영자)에게만 노출합니다.
  const nav = [
    { to: '/app', label: '새 변환', icon: FilePlus2, end: true },
    { to: '/app/history', label: '변환 기록', icon: History, end: false },
    { to: '/app/billing', label: '요금·사용량', icon: CreditCard, end: false },
    ...(canManageTeam ? [{ to: '/app/team', label: '팀 관리', icon: Users, end: false }] : []),
  ]

  function handleLogout() {
    logout()
    navigate('/')
  }

  const linkClass = ({ isActive }: { isActive: boolean }) =>
    cn(
      'flex items-center gap-2 rounded-[10px] px-3 py-2 text-[15px] font-semibold transition-colors focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring',
      isActive
        ? 'bg-accent text-accent-foreground'
        : 'text-muted-foreground hover:bg-secondary hover:text-foreground',
    )

  const menuItems = [
    { label: '계정 설정', icon: Settings, onSelect: () => navigate(canManageTeam ? '/app/team' : '/app') },
    ...(isAdmin
      ? [{ label: '관리자 콘솔로 전환', icon: ShieldCheck, onSelect: () => navigate('/admin') }]
      : []),
    { label: '로그아웃', icon: LogOut, danger: true, onSelect: handleLogout },
  ]

  return (
    <div className="flex min-h-dvh flex-col bg-background">
      <SkipLink />
      <header className="sticky top-0 z-40 border-b border-border bg-card/95 backdrop-blur">
        <div className="mx-auto flex h-16 w-full max-w-7xl items-center gap-4 px-4 sm:px-6">
          <NavLink to="/app" className="shrink-0 rounded-md focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring">
            <Logo />
          </NavLink>

          <nav
            aria-label="주요 메뉴"
            className="ml-4 hidden items-center gap-1 lg:flex"
          >
            {nav.map((item) => (
              <NavLink key={item.to} to={item.to} end={item.end} className={linkClass}>
                <item.icon className="size-4" aria-hidden="true" />
                {item.label}
              </NavLink>
            ))}
          </nav>

          <div className="ml-auto flex items-center gap-2 sm:gap-3">
            {/* 운영자에게는 상단에 관리자 콘솔 바로가기를 노출합니다. */}
            {isAdmin && (
              <NavLink to="/admin" className="hidden sm:block">
                <Button size="sm" className="bg-admin text-white hover:bg-admin/90">
                  <ShieldCheck className="size-4" aria-hidden="true" />
                  관리자 콘솔
                </Button>
              </NavLink>
            )}

            <div className="hidden items-center gap-2 rounded-full border border-border bg-secondary px-3 py-1.5 sm:flex">
              <Coins className="size-4 text-primary" aria-hidden="true" />
              <span className="text-sm font-semibold text-foreground tabular-nums">
                {formatNum(usageSummary.tokenBalance)}
              </span>
              <span className="text-sm text-muted-foreground">토큰</span>
            </div>

            <Menu
              align="end"
              header={
                <div className="flex flex-col gap-0.5">
                  <span className="text-sm font-bold text-foreground">{user?.name ?? '사용자'} 님</span>
                  <span className="text-xs text-muted-foreground">{user?.org ?? ''}</span>
                  <Badge tone={isAdmin ? 'info' : 'primary'} withIcon={false} className="mt-1.5 w-fit">
                    {user?.roleLabel ?? '사용자'}
                  </Badge>
                </div>
              }
              items={menuItems}
              trigger={({ toggle, ref, open }) => (
                <button
                  ref={ref}
                  onClick={toggle}
                  aria-haspopup="menu"
                  aria-expanded={open}
                  className="flex items-center gap-2 rounded-full border border-border bg-card py-1 pl-1 pr-2.5 transition-colors hover:bg-secondary focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring"
                >
                  <span className="flex size-8 items-center justify-center rounded-full bg-accent text-accent-foreground">
                    <UserCircle2 className="size-5" aria-hidden="true" />
                  </span>
                  <span className="hidden text-sm font-semibold text-foreground sm:inline">
                    {user?.name ?? '사용자'}
                  </span>
                </button>
              )}
            />

            <button
              type="button"
              aria-label={mobileOpen ? '메뉴 닫기' : '메뉴 열기'}
              aria-expanded={mobileOpen}
              onClick={() => setMobileOpen((o) => !o)}
              className="flex size-10 items-center justify-center rounded-[10px] border border-border text-foreground hover:bg-secondary focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring lg:hidden"
            >
              {mobileOpen ? <X className="size-5" /> : <MenuIcon className="size-5" />}
            </button>
          </div>
        </div>

        {mobileOpen && (
          <nav
            aria-label="주요 메뉴 (모바일)"
            className="border-t border-border bg-card px-4 py-3 lg:hidden"
          >
            <div className="flex flex-col gap-1">
              {nav.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.end}
                  onClick={() => setMobileOpen(false)}
                  className={linkClass}
                >
                  <item.icon className="size-4" aria-hidden="true" />
                  {item.label}
                </NavLink>
              ))}
              {isAdmin && (
                <NavLink
                  to="/admin"
                  onClick={() => setMobileOpen(false)}
                  className={linkClass}
                >
                  <ShieldCheck className="size-4" aria-hidden="true" />
                  관리자 콘솔
                </NavLink>
              )}
            </div>
          </nav>
        )}
      </header>

      <main id="main" className="mx-auto w-full max-w-7xl flex-1 px-4 py-8 sm:px-6">
        {children}
      </main>

      <footer className="border-t border-border bg-card">
        <div className="mx-auto flex w-full max-w-7xl flex-col gap-1 px-4 py-6 text-sm text-muted-foreground sm:px-6">
          <p className="font-medium text-foreground">Easy-Read AI</p>
          <p>
            프로토타입 · 실제 문서가 저장되거나 외부로 전송되지 않습니다. 모든
            데이터는 화면 시연용입니다.
          </p>
        </div>
      </footer>
    </div>
  )
}
