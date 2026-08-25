'use client'

import dynamic from 'next/dynamic'

const AppRouter = dynamic(
  () => import('@/components/easyread/app-router').then((m) => m.AppRouter),
  { ssr: false },
)

export default function Page() {
  return <AppRouter />
}
