/// <reference types="node" />

import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

const css = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), 'index.css'), 'utf8')

type Tokens = Record<string, string>

function readBlock(selector: string): Tokens {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const block = css.match(new RegExp(`${escapedSelector}\\s*\\{([\\s\\S]*?)\\n\\}`))?.[1]
  if (block === undefined) {
    throw new Error(`CSS token block not found: ${selector}`)
  }

  return Object.fromEntries(
    [...block.matchAll(/--([\w-]+)\s*:\s*(#[0-9a-fA-F]{6})\s*;/g)].map(([, name, value]) => [
      name,
      value,
    ]),
  )
}

function luminance(hex: string): number {
  const channels = [0, 2, 4].map((offset) => parseInt(hex.slice(offset + 1, offset + 3), 16) / 255)
  const linear = channels.map((channel) =>
    channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4,
  )
  return 0.2126 * (linear[0] ?? 0) + 0.7152 * (linear[1] ?? 0) + 0.0722 * (linear[2] ?? 0)
}

function contrast(first: string, second: string): number {
  const firstLuminance = luminance(first)
  const secondLuminance = luminance(second)
  return (
    (Math.max(firstLuminance, secondLuminance) + 0.05) /
    (Math.min(firstLuminance, secondLuminance) + 0.05)
  )
}

function value(tokens: Tokens, name: string): string {
  const result = tokens[name]
  if (result === undefined) {
    throw new Error(`Missing CSS token: --${name}`)
  }
  return result
}

describe('theme color tokens', () => {
  const themes = [
    { name: 'light', tokens: readBlock(':root') },
    { name: 'dark', tokens: readBlock("html[data-theme='dark']") },
  ] as const

  it('keeps body, surface, selection, and control text readable', () => {
    for (const { name, tokens } of themes) {
      const foreground = value(tokens, 'foreground')
      const card = value(tokens, 'card')
      const background = value(tokens, 'background')

      expect(contrast(foreground, card), `${name} body text on cards`).toBeGreaterThanOrEqual(4.5)
      expect(
        contrast(value(tokens, 'muted-foreground'), card),
        `${name} muted text on cards`,
      ).toBeGreaterThanOrEqual(4.5)
      expect(
        contrast(value(tokens, 'muted-foreground'), background),
        `${name} muted text on background`,
      ).toBeGreaterThanOrEqual(4.5)
      expect(
        contrast(value(tokens, 'secondary-foreground'), value(tokens, 'secondary')),
        `${name} secondary control text`,
      ).toBeGreaterThanOrEqual(4.5)
      expect(
        contrast(value(tokens, 'accent-foreground'), value(tokens, 'accent')),
        `${name} selected state text`,
      ).toBeGreaterThanOrEqual(4.5)
      expect(
        contrast(value(tokens, 'brand-foreground'), value(tokens, 'brand-surface')),
        `${name} brand highlight text`,
      ).toBeGreaterThanOrEqual(4.5)
      expect(
        contrast(value(tokens, 'primary-foreground'), value(tokens, 'primary')),
        `${name} primary button text`,
      ).toBeGreaterThanOrEqual(4.5)
    }
  })

  it('keeps status text readable on status surfaces and solid status fills', () => {
    const statuses = ['success', 'warning', 'danger', 'info', 'admin'] as const

    for (const { name, tokens } of themes) {
      for (const status of statuses) {
        expect(
          contrast(value(tokens, status), value(tokens, `${status}-surface`)),
          `${name} ${status} text on surface`,
        ).toBeGreaterThanOrEqual(4.5)
        expect(
          contrast(value(tokens, `${status}-foreground`), value(tokens, status)),
          `${name} ${status} text on solid fill`,
        ).toBeGreaterThanOrEqual(4.5)
      }
    }
  })

  it('keeps input borders visible against both page surfaces', () => {
    for (const { name, tokens } of themes) {
      const input = value(tokens, 'input')
      expect(
        contrast(input, value(tokens, 'card')),
        `${name} input on card`,
      ).toBeGreaterThanOrEqual(3)
      expect(
        contrast(input, value(tokens, 'background')),
        `${name} input on background`,
      ).toBeGreaterThanOrEqual(3)
    }
  })
})
