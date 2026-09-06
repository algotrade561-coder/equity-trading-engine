import { useEffect, useState } from 'react'
import { api } from './api'

/**
 * Polls an endpoint and returns the latest value.
 *
 * On failure it keeps the last good value rather than blanking the screen. A trading console that
 * empties itself on one dropped request is worse than one showing data a few seconds stale — the
 * operator cannot tell "nothing is happening" from "the request failed".
 */
export function usePoll<T>(path: string, intervalMs = 5000): { data: T | null; error: string | null; reload: () => void } {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [nonce, setNonce] = useState(0)

  useEffect(() => {
    let cancelled = false

    async function tick() {
      try {
        const next = await api.get<T>(path)
        if (!cancelled) { setData(next); setError(null) }
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e))
      }
    }

    tick()
    if (intervalMs <= 0) return () => { cancelled = true }
    const timer = setInterval(tick, intervalMs)
    return () => { cancelled = true; clearInterval(timer) }
  }, [path, intervalMs, nonce])

  return { data, error, reload: () => setNonce(n => n + 1) }
}
