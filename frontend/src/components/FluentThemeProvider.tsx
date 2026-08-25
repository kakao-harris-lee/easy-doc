'use client'

import {
  FluentProvider,
  createLightTheme,
  webLightTheme,
  type BrandVariants,
} from '@fluentui/react-components'
import type { ReactNode } from 'react'

const easyReadBlue: BrandVariants = {
  10: '#001322',
  20: '#00243d',
  30: '#00355a',
  40: '#004678',
  50: '#005a9e',
  60: '#106ebe',
  70: '#2589d8',
  80: '#479ef5',
  90: '#62abf5',
  100: '#77b7f7',
  110: '#96c6fa',
  120: '#b4d6fa',
  130: '#cfe4fa',
  140: '#e5f1fb',
  150: '#f0f7fd',
  160: '#f8fbfe',
}

const theme = {
  ...webLightTheme,
  ...createLightTheme(easyReadBlue),
  colorNeutralBackground1: '#ffffff',
  colorNeutralBackground2: '#f5f5f5',
  colorNeutralBackground3: '#fafafa',
  colorNeutralForeground1: '#242424',
  colorNeutralForeground2: '#616161',
  colorNeutralStroke1: '#d1d1d1',
  borderRadiusMedium: '6px',
  borderRadiusLarge: '8px',
}

export function FluentThemeProvider({ children }: { children: ReactNode }) {
  return (
    <FluentProvider theme={theme} className="min-h-dvh bg-background font-sans text-foreground">
      {children}
    </FluentProvider>
  )
}
