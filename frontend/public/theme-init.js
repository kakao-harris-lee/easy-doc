// Apply the saved theme before the app module and stylesheet load. Keeping this
// as an external same-origin script permits a strict self-only CSP.
;(function applyInitialTheme() {
  var key = 'easy-doc-theme'
  var preference = 'system'
  var root = document.documentElement

  try {
    var stored = window.localStorage.getItem(key)
    if (stored === 'light' || stored === 'dark' || stored === 'system') {
      preference = stored
    }
  } catch {
    // Continue with the system preference when storage is blocked.
  }

  var resolved = preference
  if (resolved === 'system') {
    try {
      resolved = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
    } catch {
      resolved = 'light'
    }
  }

  root.dataset.theme = resolved
  root.style.colorScheme = resolved

  var themeColor = document.querySelector('meta[name="theme-color"]')
  if (themeColor !== null) {
    themeColor.setAttribute('content', resolved === 'dark' ? '#111827' : '#F7F9FC')
  }
})()
