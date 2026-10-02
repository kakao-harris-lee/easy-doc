/**
 * `dist/index.html`(셸)을 템플릿으로 `dist/landing.html`과 `dist/sitemap.xml`을 만든다.
 *
 * 브라우저를 쓰지 않는다 — `vite build --ssr`가 만든 Node 번들을 import해
 * `node:22-alpine` 빌드 스테이지에서 그대로 돈다. 렌더·조립·검사는 모두
 * `src/prerender/**`(Vitest로 고정)에 있고 이 파일은 파일 입출력만 한다.
 *
 * 선행: `vite build` → `vite build --ssr src/prerender/landing-entry.tsx --outDir dist-ssr`.
 */
import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

import { buildLandingArtifacts } from '../dist-ssr/landing-entry.js'

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const distDir = path.join(projectRoot, 'dist')

const template = await readFile(path.join(distDir, 'index.html'), 'utf8')

const { landingHtml, sitemapXml } = buildLandingArtifacts({ template, now: new Date() })

await writeFile(path.join(distDir, 'landing.html'), landingHtml, 'utf8')
await writeFile(path.join(distDir, 'sitemap.xml'), sitemapXml, 'utf8')

process.stdout.write('prerender: dist/landing.html, dist/sitemap.xml 생성\n')
