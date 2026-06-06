import { useEffect, useState } from 'react'
import { AlertTriangle } from 'lucide-react'

/**
 * Phase 8d &mdash; ambient banner that listens for the
 * {@code myrmec:quota-exceeded} window event the API client dispatches
 * whenever the engine returns 429 with {@code QUOTA_EXCEEDED}.
 *
 * <p>The banner auto-dismisses once the {@code Retry-After} window
 * elapses. This is the 100% red band &mdash; the 80% orange variant
 * is rendered as a card on the quota admin page where we know the
 * scope; out here we only have what 429 carried in its body.
 */
export interface QuotaExceededDetail {
  retryAfter: number | null
  error: {
    errorCode: string
    message: string
    details?: unknown
  }
}

export function QuotaBanner() {
  const [state, setState] = useState<{
    visible: boolean
    message: string
    retryAfter: number | null
  }>({ visible: false, message: '', retryAfter: null })

  useEffect(() => {
    function onQuotaExceeded(ev: Event) {
      const ce = ev as CustomEvent<QuotaExceededDetail>
      const d = ce.detail
      setState({
        visible: true,
        message: d.error.message ?? 'Quota exceeded',
        retryAfter: d.retryAfter,
      })
    }
    window.addEventListener('myrmec:quota-exceeded', onQuotaExceeded)
    return () => {
      window.removeEventListener('myrmec:quota-exceeded', onQuotaExceeded)
    }
  }, [])

  useEffect(() => {
    if (!state.visible) return
    const ms = state.retryAfter != null ? state.retryAfter * 1000 : 30_000
    const t = window.setTimeout(() => {
      setState((s) => ({ ...s, visible: false }))
    }, ms)
    return () => window.clearTimeout(t)
  }, [state.visible, state.retryAfter])

  if (!state.visible) return null

  return (
    <div
      role="alert"
      data-testid="quota-exceeded-banner"
      className="fixed top-0 inset-x-0 z-50 bg-destructive text-destructive-foreground border-b border-destructive shadow"
    >
      <div className="max-w-5xl mx-auto px-4 py-2 flex items-center gap-3">
        <AlertTriangle className="h-5 w-5 shrink-0" />
        <div className="flex-1">
          <div className="font-semibold">Quota exceeded</div>
          <div className="text-sm opacity-90">
            {state.message}
            {state.retryAfter != null && (
              <span className="ml-2 opacity-75">
                Retry in {state.retryAfter}s
              </span>
            )}
          </div>
        </div>
        <button
          type="button"
          className="text-sm underline opacity-80 hover:opacity-100"
          onClick={() => setState((s) => ({ ...s, visible: false }))}
        >
          Dismiss
        </button>
      </div>
    </div>
  )
}
