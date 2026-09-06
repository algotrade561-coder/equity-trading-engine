/**
 * The one place that talks to the engine.
 *
 * Two things every call needs and none should have to remember: `credentials: 'include'` so the
 * session cookie travels, and a 401 meaning "signed out" rather than an opaque failure. Scattering
 * either across components is how a page ends up silently showing stale data to a logged-out user.
 */

export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message)
  }
  /** True when the right response is to show the sign-in screen, not an error. */
  get isUnauthenticated() { return this.status === 401 || this.status === 403 }
}

/**
 * The CSRF token the server issued, read back out of its cookie.
 *
 * Session cookies travel on any request a browser makes, including one triggered by another site, so
 * the API cannot rely on the cookie alone to prove intent. Echoing a value that only same-origin
 * JavaScript can read is what closes that. Without it every POST comes back 403 Forbidden.
 */
function csrfToken(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/)
  return match ? decodeURIComponent(match[1]) : null
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const method = (init?.method ?? 'GET').toUpperCase()
  const mutating = method !== 'GET' && method !== 'HEAD'
  const token = mutating ? csrfToken() : null

  const response = await fetch(path, {
    ...init,
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { 'X-XSRF-TOKEN': token } : {}),
      ...init?.headers,
    },
  })

  if (!response.ok) {
    // Prefer the server's own words. "HTTP 400" tells an operator nothing; the engine's message
    // usually says exactly which limit was refused and why.
    // A 403 on a write is almost never a permissions problem — it is a missing or stale CSRF
    // token. Spring answers with a bare "Forbidden" and no body, which sends people looking at
    // roles and accounts instead of at the token.
    if (response.status === 403 && mutating) {
      throw new ApiError(403, token
        ? 'Rejected as Forbidden. The session security token was stale — reload and retry.'
        : 'Rejected as Forbidden: no session security token was sent. Reload and retry.')
    }

    let detail = `HTTP ${response.status}`
    try {
      const body = await response.json()
      if (body?.message) detail = body.message
      else if (body?.error) detail = body.error
    } catch { /* not JSON — keep the status */ }
    throw new ApiError(response.status, detail)
  }

  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export const api = {
  get:  <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) }),
  del:  <T>(path: string) => request<T>(path, { method: 'DELETE' }),
}

// ── Shapes the engine actually returns ────────────────────────────────────

export interface Me {
  authEnabled: boolean
  authenticated: boolean
  loginUrl?: string
  tradingUserId?: string
  email?: string
  name?: string
  role?: string
  admin?: boolean
}

export interface Status {
  service: string
  version: string
  mode: 'LIVE' | 'REPLAY'
  tradingEnabled: boolean
  marketOpen: boolean
  serverTimeIst: string
  tradingDate: string
  armedUsers: number
  totalUsers: number
  subscribedSymbols: number
  discoverySetSize: number
  ticksSeen: number
  openPositions: number
  components: Record<string, string>
  componentStates: Record<string, string>
}

/** What the last reconciliation pass found. See the Reconciler on the engine side. */
export interface Reconciliation {
  ran: boolean
  agreed: boolean
  at?: string
  orphans?: string[]
  externalCloses?: number
  adoptedEntries?: number
  resolvedFills?: number
  abandoned?: number
  detail?: string | null
}

export interface Account {
  userId: string
  registered?: boolean
  status: string
  entriesEnabled: boolean
  mayOpen: boolean
  epoch: number
  openPositions: number
  pendingEntries: number
  realisedPnl: number
  unrealisedPnl: number
  attemptsToday: number
  lossLatched: boolean
  lossLatchReason: string | null
  availableMargin: number
  maxDailyLoss: number
  riskPerTrade: number
  maxOpenPositions: number
  unconfirmedEntries: number
  reconciliation: Reconciliation
}

export interface PositionRow {
  id: string
  symbol: string
  status: string
  quantity: number
  entryPrice: number
  stopPrice: number
  targetPrice: number
  highWaterMark: number
  reachedR: number
  lastPrice: number
  unrealisedPnl: number
  realisedPnl: number
  exitReason: string | null
  pattern: string | null
  openedAt: string | null
}

export interface BrokerSession {
  userId: string
  connected: boolean
  kiteUserId?: string
  tradingDate?: string
  feedConnected: boolean
  instrumentsLoaded: number
}

export interface BrokerConfig {
  apiKeySet: boolean
  apiSecretSet: boolean
  apiKeyHint: string | null
  brokerClientId: string | null
  tokenTradingDate: string | null
  encryptionAvailable: boolean
}

export interface RiskLimits {
  riskPerTradeRupees: number
  maxDailyLossRupees: number
  maxOpenPositions: number
  maxDailyAttempts: number
  maxPendingOrders: number
  maxPositionValue: number
  minStopPercent: number
  maxStopPercent: number
  maxSpreadPercent: number
  cooldownSeconds: number
  maxStalenessSeconds: number
}

export interface StrategyThresholds {
  minDayChangePercent: number
  maxDayChangePercent: number
  sanityBandPercent: number
  maxDistanceFromHighPct: number
  minImpulseReturn5m: number
  minRelativeVolume: number
  requireAboveVwap: boolean
  requireEmaStack: boolean
  requireOutperformIndex: boolean
  minPullbackPercent: number
  maxPullbackPercent: number
  maxConsolidationRangePct: number
  minConsolidationBars: number
  triggerBufferPercent: number
  stopAtrMultiple: number
  targetRMultiple: number
  timeStopMinutes: number
  entryWindowStart: string
  entryWindowEnd: string
  squareOffTime: string
}

export interface ExitPolicy {
  breakevenEnabled: boolean
  breakevenArmAtR: number
  trailingEnabled: boolean
  trailingArmAtR: number
  trailingAtrMultiple: number
  structureExitEnabled: boolean
}

export interface Rejections {
  intents: number
  shadowIntents: number
  byStage: Record<string, number>
  byCondition: Record<string, number>
  recent: { at: string; symbol: string; stage: string; condition: string; detail: string }[]
}

export interface Universe {
  subscribed: number
  evaluatingAll: boolean
  evaluated: number
  unresolved: string[]
  discoverySet: string[]
  fullModeCount: number
  ticksSeen: number
  ticksDropped: number
}
