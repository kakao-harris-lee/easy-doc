/**
 * SPA 셸을 템플릿으로 홈페이지·가이드 HTML과 사이트맵을 만든다.
 *
 * 브라우저를 쓰지 않는다 — `vite build --ssr`가 만든 Node 번들을 import해
 * `node:22-alpine` 빌드 스테이지에서 그대로 돈다. 렌더·조립·검사는 모두
 * `src/prerender/**`(Vitest로 고정)에 있고 이 파일은 파일 입출력만 한다.
 *
 * 선행: `vite build` → `vite build --ssr src/prerender/landing-entry.tsx --outDir dist-ssr`.
 */
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

import { buildLandingArtifacts } from '../dist-ssr/landing-entry.js'

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const distDir = path.join(projectRoot, 'dist')

const template = await readFile(path.join(distDir, 'index.html'), 'utf8')

const { landingHtml, guideHtml, sitemapXml, examplePages, exampleFiles } = buildLandingArtifacts({
  template,
  now: new Date(),
})

await writeFile(path.join(distDir, 'landing.html'), landingHtml, 'utf8')
await writeFile(path.join(distDir, 'guide.html'), guideHtml, 'utf8')
await writeFile(path.join(distDir, 'sitemap.xml'), sitemapXml, 'utf8')
for (const page of examplePages) {
  await mkdir(path.dirname(path.join(distDir, page.filename)), { recursive: true })
  await writeFile(path.join(distDir, page.filename), page.html, 'utf8')
}
for (const file of exampleFiles) {
  await mkdir(path.dirname(path.join(distDir, file.filename)), { recursive: true })
  await writeFile(path.join(distDir, file.filename), file.text, 'utf8')
}

process.stdout.write('prerender: 홈페이지, 가이드, 예시 4개 페이지, 원문 TXT 3종, 사이트맵 생성\n')
