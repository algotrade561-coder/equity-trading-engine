import { useEffect, useState } from 'react'
import { api, type BrokerConfig, type ExitPolicy, type RiskLimits } from '../lib/api'
import { StrategySection } from './StrategySection'

/**
 * Per-user configuration: broker credentials, risk limits, exit policy.
 *
 * Everything here belongs to the signed-in account and no request carries a user id — the engine
 * reads identity from the session, so there is no form field that could be edited to configure
 * somebody else's broker account.
 */
export function Settings() {
  return (
    <>
      <BrokerSection />
      <RiskSection />
      <StrategySection />
      <ExitPolicySection />
    </>
  )
}

// ── Broker ─────────────────────────────────────────────────────────────────

function BrokerSection() {
  const [config, setConfig] = useState<BrokerConfig | null>(null)
  const [apiKey, setApiKey] = useState('')
  const [apiSecret, setApiSecret] = useState('')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ text: string; bad: boolean } | null>(null)

  async function load() {
    try { setConfig(await api.get<BrokerConfig>('/api/settings/broker')) }
    catch (e) { setMessage({ text: String(e), bad: true }) }
  }
  useEffect(() => { load() }, [])

  async function save() {
    setBusy(true); setMessage(null)
    try {
      setConfig(await api.post<BrokerConfig>('/api/settings/broker', { apiKey, apiSecret }))
      setApiKey(''); setApiSecret('')
      setMessage({ text: 'Saved. Credentials are encrypted before they are stored.', bad: false })
    } catch (e) {
      setMessage({ text: e instanceof Error ? e.message : String(e), bad: true })
    } finally { setBusy(false) }
  }

  async function clear() {
    if (!confirm('Remove the stored Kite credentials for this account?')) return
    setBusy(true)
    try {
      setConfig(await api.del<BrokerConfig>('/api/settings/broker'))
      setMessage({ text: 'Cleared.', bad: false })
    } catch (e) {
      setMessage({ text: String(e), bad: true })
    } finally { setBusy(false) }
  }

  const noEncryption = config && !config.encryptionAvailable

  return (
    <section className={`card${noEncryption ? ' warn' : ''}`}>
      <h3>Zerodha Kite</h3>
      <p className="hint">
        Your own Kite Connect application. If you do not set one, the engine falls back to the
        application-wide credentials from its configuration.
      </p>

      {noEncryption && (
        <p className="note" style={{ color: 'var(--warn)' }}>
          No encryption key is configured (<code>EQUITY_SECRET_KEY</code>), so credentials cannot be
          saved. They will not be written in clear text — losing the ability to store one is
          recoverable, discovering later that everything was plaintext is not.
        </p>
      )}

      <dl className="facts" style={{ marginBottom: '1rem' }}>
        <dt>API key</dt>
        <dd>{config?.apiKeySet ? `set ${config.apiKeyHint ?? ''}` : <span className="muted">not set</span>}</dd>
        <dt>API secret</dt>
        <dd>{config?.apiSecretSet ? 'set' : <span className="muted">not set</span>}</dd>
        <dt>Kite client</dt>
        <dd>{config?.brokerClientId ?? <span className="muted">—</span>}</dd>
        <dt>Token for</dt>
        <dd>{config?.tokenTradingDate ?? <span className="muted">no active session</span>}</dd>
      </dl>

      <div className="grid two">
        <div>
          <label htmlFor="apiKey">API key</label>
          <input id="apiKey" type="text" value={apiKey} spellCheck={false}
                 placeholder={config?.apiKeySet ? 'leave blank to keep the stored key' : 'from developers.kite.trade'}
                 onChange={e => setApiKey(e.target.value)} />
        </div>
        <div>
          <label htmlFor="apiSecret">API secret</label>
          <input id="apiSecret" type="password" value={apiSecret} spellCheck={false}
                 placeholder={config?.apiSecretSet ? 'leave blank to keep the stored secret' : ''}
                 onChange={e => setApiSecret(e.target.value)} />
        </div>
      </div>

      <div className="row" style={{ marginTop: '.9rem' }}>
        <button className="primary" disabled={busy || noEncryption || (!apiKey && !apiSecret)}
                onClick={save}>Save credentials</button>
        <button className="danger" disabled={busy || !config?.apiKeySet} onClick={clear}>
          Remove
        </button>
      </div>

      {message && <p className={message.bad ? 'error' : 'ok'} style={{ marginTop: '.6rem' }}>{message.text}</p>}

      <p className="note">
        A blank field keeps whatever is stored, so the form can be submitted without retyping the
        secret. Nothing here is ever sent back to the browser — the page is told whether a key is
        present and the last four characters, never the value.
      </p>
    </section>
  )
}

// ── Risk ───────────────────────────────────────────────────────────────────

const RISK_FIELDS: { key: keyof RiskLimits; label: string; hint: string; step?: number }[] = [
  { key: 'riskPerTradeRupees', label: 'Risk per trade (₹)', hint: 'Divided by the stop distance to size every position' },
  { key: 'maxDailyLossRupees', label: 'Daily loss limit (₹)', hint: 'Realised plus open. Latches for the session and survives a restart' },
  { key: 'maxOpenPositions', label: 'Max open positions', hint: 'Concurrent positions' },
  { key: 'maxDailyAttempts', label: 'Max attempts/day', hint: 'Entry submissions, filled or not' },
  { key: 'maxPendingOrders', label: 'Max pending entries', hint: 'Stops a stalled fill queueing the same idea' },
  { key: 'maxPositionValue', label: 'Max position value (₹)', hint: 'So one tight stop cannot buy the account' },
  { key: 'minStopPercent', label: 'Min stop %', hint: 'Below this the stop is inside the spread', step: 0.05 },
  { key: 'maxStopPercent', label: 'Max stop %', hint: 'Above this it is not the setup that was detected', step: 0.05 },
  { key: 'maxSpreadPercent', label: 'Max spread %', hint: 'Refuse to cross a wider book', step: 0.01 },
  { key: 'cooldownSeconds', label: 'Symbol cooldown (s)', hint: 'Before re-entering a symbol just exited' },
  { key: 'maxStalenessSeconds', label: 'Max price age (s)', hint: 'How old the last tick may be at entry' },
]

function RiskSection() {
  const [limits, setLimits] = useState<RiskLimits | null>(null)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ text: string; bad: boolean } | null>(null)

  useEffect(() => { api.get<RiskLimits>('/api/settings/risk').then(setLimits).catch(() => {}) }, [])

  async function save() {
    if (!limits) return
    setBusy(true); setMessage(null)
    try {
      setLimits(await api.post<RiskLimits>('/api/settings/risk', limits))
      setMessage({ text: 'Saved, and applied to the running engine immediately.', bad: false })
    } catch (e) {
      setMessage({ text: e instanceof Error ? e.message : String(e), bad: true })
    } finally { setBusy(false) }
  }

  if (!limits) return <section className="card"><h3>Risk</h3><p className="muted">Loading…</p></section>

  return (
    <section className="card">
      <h3>Risk</h3>
      <p className="hint">
        Applied to the live engine as soon as it is saved, and persisted. Sizing is the adaptive
        part: every trade risks the same rupees whatever the stock, so a tight stop buys a large
        position and a wide one a small position.
      </p>

      <div className="grid three">
        {RISK_FIELDS.map(f => (
          <div key={String(f.key)}>
            <label htmlFor={String(f.key)} title={f.hint}>{f.label}</label>
            <input id={String(f.key)} type="number" step={f.step ?? 1}
                   value={limits[f.key]}
                   onChange={e => setLimits({ ...limits, [f.key]: Number(e.target.value) })} />
          </div>
        ))}
      </div>

      <div className="row" style={{ marginTop: '.9rem' }}>
        <button className="primary" disabled={busy} onClick={save}>Save risk limits</button>
      </div>
      {message && <p className={message.bad ? 'error' : 'ok'} style={{ marginTop: '.6rem' }}>{message.text}</p>}
    </section>
  )
}

// ── Exit policy ────────────────────────────────────────────────────────────

function ExitPolicySection() {
  const [policy, setPolicy] = useState<ExitPolicy | null>(null)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ text: string; bad: boolean } | null>(null)

  useEffect(() => { api.get<ExitPolicy>('/api/settings/exit-policy').then(setPolicy).catch(() => {}) }, [])

  async function save() {
    if (!policy) return
    setBusy(true); setMessage(null)
    try {
      setPolicy(await api.post<ExitPolicy>('/api/settings/exit-policy', policy))
      setMessage({ text: 'Saved.', bad: false })
    } catch (e) {
      setMessage({ text: e instanceof Error ? e.message : String(e), bad: true })
    } finally { setBusy(false) }
  }

  if (!policy) return <section className="card"><h3>Exits</h3><p className="muted">Loading…</p></section>

  return (
    <section className="card">
      <h3>Exits</h3>
      <p className="hint">
        What happens after a position is filled. All three ship off — not a recommendation, an
        admission: none has been measured on this market's tape. They are per account so one can be
        run against another taking the same signals, and the difference is attributable to the exit.
      </p>

      <div className="grid two">
        <Toggle label="Breakeven stop"
                hint="Move the stop to the fill once the trade is far enough ahead"
                checked={policy.breakevenEnabled}
                onChange={v => setPolicy({ ...policy, breakevenEnabled: v })} />
        <NumberField label="Arm at (R)" value={policy.breakevenArmAtR} step={0.25}
                onChange={v => setPolicy({ ...policy, breakevenArmAtR: v })} />

        <Toggle label="Trailing stop"
                hint="Follow the high-water mark at a multiple of ATR"
                checked={policy.trailingEnabled}
                onChange={v => setPolicy({ ...policy, trailingEnabled: v })} />
        <NumberField label="Arm at (R)" value={policy.trailingArmAtR} step={0.25}
                onChange={v => setPolicy({ ...policy, trailingArmAtR: v })} />

        <NumberField label="Trail distance (× ATR)" value={policy.trailingAtrMultiple} step={0.25}
                onChange={v => setPolicy({ ...policy, trailingAtrMultiple: v })} />
        <Toggle label="Structure exit"
                hint="Close on a completed 1m bar below VWAP. The exit most likely to clip a winner"
                checked={policy.structureExitEnabled}
                onChange={v => setPolicy({ ...policy, structureExitEnabled: v })} />
      </div>

      <div className="row" style={{ marginTop: '.9rem' }}>
        <button className="primary" disabled={busy} onClick={save}>Save exit policy</button>
      </div>
      {message && <p className={message.bad ? 'error' : 'ok'} style={{ marginTop: '.6rem' }}>{message.text}</p>}

      <p className="note">
        A stop only ever moves in the favourable direction, so switching trailing on mid-position
        cannot loosen a stop already in force.
      </p>
    </section>
  )
}

function Toggle({ label, hint, checked, onChange }: {
  label: string; hint: string; checked: boolean; onChange: (v: boolean) => void
}) {
  return (
    <div>
      <label title={hint}>{label}</label>
      <button style={{ width: '100%' }}
              className={checked ? 'primary' : ''}
              onClick={() => onChange(!checked)}>
        {checked ? 'ON' : 'off'}
      </button>
    </div>
  )
}

function NumberField({ label, value, step, onChange }: {
  label: string; value: number; step: number; onChange: (v: number) => void
}) {
  return (
    <div>
      <label>{label}</label>
      <input type="number" step={step} value={value} onChange={e => onChange(Number(e.target.value))} />
    </div>
  )
}
