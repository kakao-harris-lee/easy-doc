'use client'

import {
  FluentProvider,
  createLightTheme,
  webLightTheme,
  type BrandVariants,
} from '@fluentui/react-components'
import type { ReactNode } from 'react'

/**
 * Fluent 브랜드 램프. 정본은 `index.css` 의 `--primary`/`--primary-hover`/`--accent` 이고
 * 이 16단은 거기서 파생된 값이다.
 *
 * Fluent light 테마는 `brand[80]` 을 `colorBrandBackground`, `brand[70]` 을 hover,
 * `brand[160]` 을 `colorBrandBackground2`(옅은 브랜드 면)로 쓴다. 그래서 그 세 단을
 * DESIGN.md §8.1 의 `--primary`(#5b4bc4), `--primary-hover`(#493ba5),
 * `--accent`(#efedff)에 정확히 고정했다. 이렇게 하지 않으면 Fluent 버튼과 Tailwind
 * `bg-primary` 버튼이 같은 화면에서 다른 보라로 갈린다.
 *
 * 나머지 13단은 Fluent 기본 파랑 램프(brandWeb)의 OKLab 명도 곡선을 위 세 앵커에
 * 맞춰 다시 사상하고, 색상각은 #5b4bc4 의 것으로 고정해 만들었다. 80 보다 밝은 쪽은
 * 채도를 명도에 따라 선형으로 낮춘다 — 보라는 밝은 영역에서 sRGB 를 쉽게 벗어나
 * 그대로 두면 형광빛으로 튀기 때문이다(DESIGN.md §8: 「채도를 낮춘다」).
 */
const easyReadPurple: BrandVariants = {
  10: '#0f0e23',
  20: '#191637',
  30: '#221e49',
  40: '#2c275e',
  50: '#362e75',
  60: '#41368c',
  70: '#493ba5',
  80: '#5b4bc4',
  90: '#736dd2',
  100: '#8b89dd',
  110: '#9897e2',
  120: '#a4a4e7',
  130: '#b6b7ee',
  140: '#c8caf5',
  150: '#dadbfa',
  160: '#efedff',
}

const theme = {
  ...webLightTheme,
  ...createLightTheme(easyReadPurple),
  // 중성 색은 `index.css` 의 :root 값과 같은 면을 가리켜야 Fluent 컴포넌트가
  // Tailwind 로 그린 주변 면 위에서 떠 보이지 않는다.
  colorNeutralBackground1: '#ffffff', // --card
  colorNeutralBackground2: '#f7f7fb', // --background
  colorNeutralBackground3: '#f0f0f6', // --muted
  colorNeutralForeground1: '#202230', // --foreground
  colorNeutralForeground2: '#626577', // --muted-foreground
  colorNeutralStroke1: '#dedee8', // --border
  // DESIGN.md §8.3: 입력·버튼 10px, 큰 카드 16px.
  borderRadiusMedium: '10px',
  borderRadiusLarge: '16px',
}

export function FluentThemeProvider({ children }: { children: ReactNode }) {
  return (
    <FluentProvider theme={theme} className="min-h-dvh bg-background font-sans text-foreground">
      {children}
    </FluentProvider>
  )
}
