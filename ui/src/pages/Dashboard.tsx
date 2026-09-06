import { useState } from 'react'
import { api, type Account, type BrokerSession, type Status } from '../lib/api'
import { usePoll } from '../lib/usePoll'
import { money, count, pnlClass } from '../lib/format'
import { Pill } from '../components/Pill'

/**
 * The screen an operator watches during a session.
 *
 * Ordered by what has to be noticed fastest: the broker connection (nothing works without it), then
 * arming, then money, then the subsystem checklist. The controls sit next to the state they change
 * rather than in a separate panel — halting is not something to go looking for.
 */
export function Dashboard({ status }: { status: Status | null }) {
  const account = usePoll<Account>('/api/users/me', 4000)
  const broker = usePoll<BrokerSession>('/api/broker/kite/session', 4000)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  async function act(what: string, path: string, confirmText?: string) {
    if (confirmText && !confirm(confirmText)) return
    setBusy(true); setMessage(null)
    try {
      await api.post(path)
      account.reload()
      setMessage(`${what} — done.`)
    } catch (e) {
      setMessage(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const a = account.data
  const b = broker.data

  return (
    <>
      <section className="card">
        <h3>Broker</h3>
        <p className="hint">
          Kite invalidates access tokens every morning, so this is a once-per-trading-day sign-in.
        </p>
        <div className="row" style={{ marginBottom: '1rem' }}>
          <Pill tone={b?.connected ? 'good' : 'bad'}>
            {b?.connected ? `CONNECTED${b.kiteUserId ? ` · ${b.kiteUserId}` : ''}` : 'NOT CONNECTED'}
          </Pill>
          <Pill tone={b?.feedConnected ? 'good' : 'neutral'}>
            {b?.feedConnected ? 'FEED STREAMING' : 'FEED DOWN'}
          </Pill>
          <span className="spacer" style={{ flex: 1 }} />
          {!b?.connected && (
            <a href="/api/broker/kite/login-url" onClick={async e => {
              e.preventDefault()
              const { loginUrl } = await api.get<{ loginUrl: string }>('/api/broker/kite/login-url')
              location.href = loginUrl
            }}>
              <button className="primary">Connect to Zerodha</button>
            </a>
          )}
        </div>
        <dl className="facts">
          <dt>Instruments</dt><dd>{b ? count(b.instrumentsLoaded) : '—'}</dd>
          <dt>Session date</dt><dd>{b?.tradingDate ?? '—'}</dd>
        </dl>
      </section>

      <section className={`card${a?.entriesEnabled ? ' bad' : ''}`}>
        <h3>Trading</h3>
        <p className="hint">
          Arming lets this account open positions. It never affects exits — a halted or disarmed
          account still has its positions managed and closed.
        </p>
        <div className="row">
          <button
            className="primary"
            disabled={busy || a?.entriesEnabled || (a?.registered !== false && a?.status !== 'ACTIVE')}
            onClick={() => act('Armed', '/api/users/me/entries?enabled=true')}
          >Arm entries</button>
          <button
            disabled={busy || !a?.entriesEnabled}
            onClick={() => act('Disarmed', '/api/users/me/entries?enabled=false')}
          >Disarm</button>
          <button
            disabled={busy}
            onClick={() => act('Flatten requested', '/api/users/me/flatten',
              'Close every open position for this account?')}
          >Flatten all</button>
          <button
            className="danger"
            disabled={busy}
            onClick={() => act('Halted', '/api/users/me/halt',
              'HALT: block new entries, disown in-flight orders, and close everything open?')}
          >HALT</button>
          {a?.status === 'HALTED' && (
            <button disabled={busy} onClick={() => act('Halt cleared', '/api/users/me/resume')}>
              Clear halt
            </button>
          )}
        </div>

        {message && <p className="error" style={{ marginTop: '.75rem' }}>{message}</p>}

        {a?.lossLatched && (
          <p className="note" style={{ color: 'var(--bad)' }}>
            Daily loss limit latched: {a.lossLatchReason}. It stays latched for the rest of the
            session, and it survives a restart.
          </p>
        )}

        {/*
          Reconciliation disagreements are the one class of problem the engine cannot fix for you.
          An orphan is an intraday position it will not square off, so it has to be visible without
          anyone thinking to open a log file.
        */}
        {!!a?.reconciliation?.orphans?.length && (
          <p className="note" style={{ color: 'var(--bad)' }}>
            <strong>Orphan position(s) at the broker: {a.reconciliation.orphans.join(', ')}.</strong>{' '}
            The engine did not open these and will not square them off. Close them in Kite, or the
            broker will auto-square them at whatever price it gets.
          </p>
        )}

        {!!a?.unconfirmedEntries && (
          <p className="note" style={{ color: 'var(--bad)' }}>
            {a.unconfirmedEntries} entry order sent whose outcome is unknown. Reconciliation is
            looking for it; if it filled, it will be adopted with its original stop.
          </p>
        )}

        {a?.reconciliation?.ran && a.reconciliation.detail && (
          <p className="note" style={{ color: 'var(--warn)' }}>
            Reconciliation could not run: {a.reconciliation.detail}. Until it does, open positions
            shown here are what the engine believes, not what the broker confirms.
          </p>
        )}
      </section>

      <div className="grid three" style={{ marginBottom: '1rem' }}>
        <Stat label="Realised" value={money(a?.realisedPnl ?? 0)} tone={pnlClass(a?.realisedPnl ?? 0)} />
        <Stat label="Open P&L" value={money(a?.unrealisedPnl ?? 0)} tone={pnlClass(a?.unrealisedPnl ?? 0)} />
        <Stat label="Open positions" value={`${a?.openPositions ?? 0} / ${a?.maxOpenPositions ?? '—'}`} />
        <Stat label="Attempts today" value={count(a?.attemptsToday)} />
        <Stat label="Margin" value={money(a?.availableMargin)} />
        <Stat label="Ticks seen" value={count(status?.ticksSeen)} />
        <Stat
          label="Broker agrees"
          value={!a?.reconciliation?.ran ? '—' : a.reconciliation.agreed ? 'yes' : 'NO'}
          tone={!a?.reconciliation?.ran ? undefined : a.reconciliation.agreed ? 'up' : 'down'}
        />
      </div>

      <section className="card">
        <h3>Subsystems</h3>
        <p className="hint">
          <code>RUNNING</code> is live in the tick path. <code>IDLE_NO_DATA</code> means wired but no
          market data has reached it. <code>NO_ARMED_USER</code> means wired but nobody may trade.
        </p>
        <div className="scroll">
          <table>
            <tbody>
              {Object.entries(status?.components ?? {}).sort().map(([name, state]) => (
                <tr key={name}>
                  <td style={{ fontFamily: 'var(--sans)' }}>{name}</td>
                  <td className={state === 'RUNNING' || state === 'CONNECTED' ? 'up' : 'muted'}>{state}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </>
  )
}

function Stat({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <div className="stat">
      <div className="label">{label}</div>
      <div className={`value ${tone ?? ''}`}>{value}</div>
    </div>
  )
}
