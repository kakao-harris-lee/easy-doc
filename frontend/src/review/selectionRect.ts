/** textarea는 Range 좌표를 제공하지 않아 같은 글꼴·폭의 숨긴 요소에서 선택 시작점을 잰다. */
export function textareaSelectionRect(target: HTMLTextAreaElement, start: number): DOMRect {
  const bounds = target.getBoundingClientRect()
  const computed = window.getComputedStyle(target)
  const mirror = document.createElement('div')
  for (const property of [
    'font-family',
    'font-size',
    'font-weight',
    'font-style',
    'line-height',
    'letter-spacing',
    'word-spacing',
    'text-indent',
    'text-align',
    'text-transform',
    'padding',
    'border-width',
    'border-style',
    'tab-size',
  ]) {
    mirror.style.setProperty(property, computed.getPropertyValue(property))
  }
  Object.assign(mirror.style, {
    position: 'fixed',
    visibility: 'hidden',
    pointerEvents: 'none',
    boxSizing: 'border-box',
    left: `${bounds.left}px`,
    top: `${bounds.top}px`,
    width: `${target.clientWidth + parseFloat(computed.borderLeftWidth || '0') + parseFloat(computed.borderRightWidth || '0')}px`,
    whiteSpace: target.wrap === 'off' ? 'pre' : 'pre-wrap',
    overflowWrap: 'break-word',
  })
  mirror.setAttribute('aria-hidden', 'true')
  mirror.textContent = target.value.slice(0, start)
  const marker = document.createElement('span')
  marker.textContent = target.value.slice(start, start + 1) || '\u200b'
  mirror.append(marker, document.createTextNode(target.value.slice(start + 1)))
  document.body.append(mirror)
  try {
    const rect = marker.getBoundingClientRect()
    const lineHeight = parseFloat(computed.lineHeight) || 24
    const left = Math.max(bounds.left, Math.min(rect.left - target.scrollLeft, bounds.right))
    const top = Math.max(
      bounds.top,
      Math.min(rect.top - target.scrollTop, bounds.bottom - lineHeight),
    )
    return new DOMRect(left, top, Math.max(rect.width, 1), lineHeight)
  } finally {
    mirror.remove()
  }
}
