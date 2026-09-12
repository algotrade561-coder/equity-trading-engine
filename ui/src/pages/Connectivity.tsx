import { useEffect, useState } from 'react'
import { api } from '../lib/api'
import { Pill } from '../components/Pill'

/**
 * Broker connectivity test — temporary.
 *
 * Places one after-market order at a price that cannot fill and cancels it in the same request.
 * That round trip is the only thing that proves a user's key, session and source address work
 * together, because the order path is the only one the broker checks against the registered IP.
 * Everything else can pass while an order would be refused.
 *
 * The one result that matters is "order still live". If it ever shows, the cancel failed and an
 * after-market order is sitting at the broker that will act at Monday's open. Cancel it in Kite.
 */

interface Preview {
  symbol: string
  lastPrice: number | null
  suggestedLimit: number | null
  sourceIp: string | null
  authenticated: boolean
  plan: string
}

interface Step { name: string; ok: boolean; detail: string }

interface Result {
  connected: boolean
  orderStillLive: boolean
  brokerOrderId: string | null
  sourceIp: string
  steps: Step[]
}

export function Connectivity() {
  const [symbol, setSymbol] = useState('ITC')
  const [preview, setPreview] = useState<Preview | null>(null)
  const [limit, setLimit] = useState('')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<Result | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function loadPreview(sym: string) {
    setError(null)
    try {
      const p = await api.get<Preview>(`/api/broker/kite/connectivity/preview?symbol=${encodeURIComponent(sym)}`)
      setPreview(p)
      if (p.suggestedLimit) setLimit(String(p.suggestedLimit))
    } catch (e) { setError(e instanceof Error ? e.message : String(e)) }
  }
  useEffect(() => { loadPreview(symbol) }, [])   // eslint-disable-line react-hooks/exhaustive-deps

  async function run() {
    if (!confirm(`Place AMO LIMIT BUY 1 × ${preview?.symbol ?? symbol} @ ${limit} (CNC) and cancel it immediately?\n\nThis sends a real order to Kite.`)) return
    setBusy(true); setError(null); setResult(null)
    try {
      setResult(await api.post<Result>('/api/broker/kite/connectivity/run', { symbol, limitPrice: Number(limit) }))
    } catch (e) { setError(e instanceof Error ? e.message : String(e)) }
    finally { setBusy(false) }
  }

  return (
    <>
      <div className="card">
        <h3>Broker connectivity test</h3>
        <p className="hint">
          Places one after-market limit order well below the market and cancels it in the same request.
          The order path is the only one Kite checks against the registered IP, so this is the only test
          that proves an order from this box, from your address, would be accepted.
        </p>

        <dl className="facts" style={{ marginBottom: '1rem' }}>
          <dt>Kite session</dt>
          <dd>{preview ? (preview.authenticated ? <Pill tone="good">logged in</Pill> : <Pill tone="bad">no session today — log in first</Pill>) : '…'}</dd>
          <dt>Sends from</dt>
          <dd>{preview ? (preview.sourceIp ? <code>{preview.sourceIp}</code> : <span className="muted">default interface (no source IP set)</span>) : '…'}</dd>
          <dt>Last price</dt>
          <dd>{preview?.lastPrice ? preview.lastPrice.toFixed(2) : <span className="muted">not in the feed — enter a price you know is well below market</span>}</dd>
        </dl>

        <div className="grid two">
          <div>
            <label htmlFor="ctSymbol">Symbol</label>
            <input id="ctSymbol" type="text" value={symbol} spellCheck={false}
                   onChange={e => setSymbol(e.target.value.toUpperCase())}
                   onBlur={() => loadPreview(symbol)} />
          </div>
          <div>
            <label htmlFor="ctLimit">Limit price (must be well below market)</label>
            <input id="ctLimit" type="number" step="0.05" value={limit}
                   onChange={e => setLimit(e.target.value)} />
          </div>
        </div>

        <div className="row" style={{ marginTop: '.9rem' }}>
          <button className="primary" disabled={busy || !preview?.authenticated || !limit} onClick={run}>
            {busy ? 'Running…' : 'Place and cancel a test order'}
          </button>
          <span className="muted" style={{ fontSize: '.8em' }}>{preview?.plan}</span>
        </div>
        {error && <div className="note error" style={{ marginTop: '.6rem' }}>{error}</div>}
      </div>

      {result && (
        <div className={`card ${result.orderStillLive ? 'bad' : ''}`}>
          <h3>
            {result.orderStillLive
              ? <Pill tone="bad">ORDER STILL LIVE — cancel {result.brokerOrderId} in Kite now</Pill>
              : result.connected
                ? <Pill tone="good">connected — order accepted and cancelled</Pill>
                : <Pill tone="bad">not connected — order refused</Pill>}
          </h3>
          <dl className="facts">
            <dt>Sent from</dt><dd><code>{result.sourceIp}</code></dd>
            {result.brokerOrderId && <><dt>Order id</dt><dd><code>{result.brokerOrderId}</code></dd></>}
          </dl>
          <table style={{ marginTop: '.6rem' }}>
            <tbody>
              {result.steps.map(s => (
                <tr key={s.name}>
                  <td style={{ width: '7rem' }}>{s.ok ? '✓' : '✗'} {s.name}</td>
                  <td>{s.detail}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {result.orderStillLive && (
            <p className="note error" style={{ marginTop: '.6rem' }}>
              The cancel did not take. An after-market order exists at the broker and will act at the
              next market open. Open Kite → Orders → AMO and cancel order {result.brokerOrderId}.
            </p>
          )}
          {!result.connected && (
            <p className="note" style={{ marginTop: '.6rem' }}>
              A refusal here is the answer this page exists to get. Read the place step: an IP message
              means the public IP is not registered against this key; a token message means the session
              has expired; a margin or product message means the order itself was wrong, not the link.
            </p>
          )}
        </div>
      )}
    </>
  )
}
