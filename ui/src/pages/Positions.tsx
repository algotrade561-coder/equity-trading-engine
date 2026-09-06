import { usePoll } from '../lib/usePoll'
import { money, price, pnlClass, istTime } from '../lib/format'
import type { PositionRow } from '../lib/api'

/**
 * Every position for the signed-in account, live and closed.
 *
 * Stop and target sit next to the last price on purpose: the question being asked of this table is
 * "how close is this to coming out", and that is a comparison, not three separate numbers.
 */
export function Positions() {
  const { data, error } = usePoll<PositionRow[]>('/api/positions', 3000)
  const rows = data ?? []
  const live = rows.filter(r => r.status !== 'CLOSED')
  const closed = rows.filter(r => r.status === 'CLOSED')

  return (
    <>
      {error && <div className="card bad"><div className="error">{error}</div></div>}

      <section className="card">
        <h3>Open</h3>
        <p className="hint">
          {live.length === 0
            ? 'Nothing open. Positions restored from a previous run would appear here too.'
            : `${live.length} live.`}
        </p>
        {live.length > 0 && <Table rows={live} showR />}
      </section>

      <section className="card">
        <h3>Closed today</h3>
        <p className="hint">
          {closed.length === 0 ? 'Nothing closed yet today.' : `${closed.length} closed.`}
        </p>
        {closed.length > 0 && <Table rows={closed} />}
      </section>
    </>
  )
}

function Table({ rows, showR }: { rows: PositionRow[]; showR?: boolean }) {
  return (
    <div className="scroll">
      <table>
        <thead>
          <tr>
            <th>Symbol</th><th>Status</th><th className="num">Qty</th>
            <th className="num">Entry</th><th className="num">Last</th>
            <th className="num">Stop</th><th className="num">Target</th>
            {showR && <th className="num">Best</th>}
            <th className="num">P&amp;L</th><th>Exit</th><th>Opened</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(r => {
            const pnl = r.status === 'CLOSED' ? r.realisedPnl : r.unrealisedPnl
            return (
              <tr key={r.id}>
                <td>{r.symbol}</td>
                <td className="muted">{r.status}</td>
                <td className="num">{r.quantity}</td>
                <td className="num">{price(r.entryPrice)}</td>
                <td className="num">{price(r.lastPrice)}</td>
                <td className="num">{price(r.stopPrice)}</td>
                <td className="num">{price(r.targetPrice)}</td>
                {showR && <td className="num muted">{r.reachedR ? `${r.reachedR.toFixed(2)}R` : '—'}</td>}
                <td className={`num ${pnlClass(pnl)}`}>{money(pnl)}</td>
                <td className="muted">{r.exitReason ?? '—'}</td>
                <td className="muted">{istTime(r.openedAt)}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
