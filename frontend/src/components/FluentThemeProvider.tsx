import {
  FluentProvider,
  createDarkTheme,
  createLightTheme,
  type BrandVariants,
  type Theme,
} from '@fluentui/react-components'
import type { ReactNode } from 'react'

import { ThemeProvider, useTheme } from '../theme'

const easyDocBlue: BrandVariants = {
  10: '#071A2D',
  20: '#0A2947',
  30: '#0D385F',
  40: '#104777',
  50: '#12558E',
  60: '#1563A1',
  70: '#0F4E91',
  80: '#1764B5',
  90: '#3B82C6',
  100: '#5B9DD2',
  110: '#75BFFF',
  120: '#94CCFF',
  130: '#B0DAFF',
  140: '#C9E5FF',
  150: '#DCEBFF',
  160: '#E8F3FF',
}

const lightTheme: Theme = {
  ...createLightTheme(easyDocBlue),
  colorBrandBackground: 'var(--primary)',
  colorBrandBackgroundHover: 'var(--primary-hover)',
  colorBrandBackgroundPressed: 'var(--primary-hover)',
  colorBrandBackgroundSelected: 'var(--primary-hover)',
  colorBrandForeground1: 'var(--primary)',
  colorBrandForeground2: 'var(--primary-hover)',
  colorNeutralForegroundOnBrand: 'var(--primary-foreground)',
  colorNeutralBackground1: 'var(--card)',
  colorNeutralBackground2: 'var(--background)',
  colorNeutralBackground3: 'var(--muted)',
  colorNeutralBackground4: 'var(--secondary)',
  colorNeutralForeground1: 'var(--foreground)',
  colorNeutralForeground2: 'var(--muted-foreground)',
  colorNeutralForeground3: 'var(--muted-foreground)',
  colorNeutralForeground4: 'var(--muted-foreground)',
  colorNeutralStroke1: 'var(--border)',
  colorNeutralStroke2: 'var(--secondary)',
  colorNeutralStrokeAccessible: 'var(--input)',
  colorNeutralForegroundDisabled: 'var(--muted-foreground)',
  colorNeutralStrokeDisabled: 'var(--border)',
  borderRadiusMedium: '10px',
  borderRadiusLarge: '16px',
}

const darkTheme: Theme = {
  ...createDarkTheme(easyDocBlue),
  colorBrandBackground: 'var(--primary)',
  colorBrandBackgroundHover: 'var(--primary-hover)',
  colorBrandBackgroundPressed: 'var(--primary-hover)',
  colorBrandBackgroundSelected: 'var(--primary-hover)',
  colorBrandForeground1: 'var(--primary)',
  colorBrandForeground2: 'var(--primary-hover)',
  colorNeutralForegroundOnBrand: 'var(--primary-foreground)',
  colorNeutralBackground1: 'var(--card)',
  colorNeutralBackground2: 'var(--background)',
  colorNeutralBackground3: 'var(--muted)',
  colorNeutralBackground4: 'var(--secondary)',
  colorNeutralForeground1: 'var(--foreground)',
  colorNeutralForeground2: 'var(--muted-foreground)',
  colorNeutralForeground3: 'var(--muted-foreground)',
  colorNeutralForeground4: 'var(--muted-foreground)',
  colorNeutralStroke1: 'var(--border)',
  colorNeutralStroke2: 'var(--secondary)',
  colorNeutralStrokeAccessible: 'var(--input)',
  colorNeutralForegroundDisabled: 'var(--muted-foreground)',
  colorNeutralStrokeDisabled: 'var(--border)',
  borderRadiusMedium: '10px',
  borderRadiusLarge: '16px',
}

function ThemedFluentProvider({ children }: { children: ReactNode }) {
  const { resolvedTheme } = useTheme()
  return (
    <FluentProvider
      theme={resolvedTheme === 'dark' ? darkTheme : lightTheme}
      className="min-h-dvh bg-background font-sans text-foreground"
      style={{ backgroundColor: 'var(--background)', color: 'var(--foreground)' }}
    >
      {children}
    </FluentProvider>
  )
}

export function FluentThemeProvider({ children }: { children: ReactNode }) {
  return (
    <ThemeProvider>
      <ThemedFluentProvider>{children}</ThemedFluentProvider>
    </ThemeProvider>
  )
}
