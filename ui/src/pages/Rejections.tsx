import { usePoll } from '../lib/usePoll'
import { count, istTime } from '../lib/format'
import type { Rejections as RejectionsData } from '../lib/api'

/**
 * Why candidates did not become trades.
 *
 * The most useful screen in the application on a day when nothing traded, because it separates "no
 * setups appeared" from "a limit rejected every one of them". A system that records only what it
 * traded cannot tell a gate that filters noise from one that blocks its best candidates — and that
 * distinction has been measured going the wrong way in a sibling engine.
 */
export function Rejections() {
  const { data, error } = usePoll<RejectionsData>('/api/rejections?samples=30', 5000)
  const conditions = Object.entries(data?.byCondition ?? {})
  const stages = Object.entries(data?.byStage ?? {})

  return (
    <>
      {error && <div className="card bad"><div className="error">{error}</div></div>}

      <div className="grid three" style={{ marginBottom: '1rem' }}>
        <div className="stat">
          <div className="label">Intents raised</div>
          <div className="value">{count(data?.intents)}</div>
        </div>
        <div className="stat">
          <div className="label">Shadow entries</div>
          <div className="value">{count(data?.shadowIntents)}</div>
        </div>
        <div className="stat">
          <div className="label">Rejections</div>
          <div className="value">{count(stages.reduce((sum, [, n]) => sum + n, 0))}</div>
        </div>
      </div>

      <section className="card">
        <h3>By condition</h3>
        <p className="hint">
          Evaluation runs whether or not anyone is armed, so these fill up in a shadow session too.
          A shadow entry is a setup that triggered while the account was disarmed.
        </p>
        {conditions.length === 0 ? (
          <p className="muted">
            Nothing recorded yet. Early in a session this usually means the strategy is still
            waiting for 20 completed minutes per symbol.
          </p>
        ) : (
          <div className="scroll">
            <table>
              <thead><tr><th>Condition</th><th className="num">Count</th></tr></thead>
              <tbody>
                {conditions.map(([name, n]) => (
                  <tr key={name}><td>{name}</td><td className="num">{count(n)}</td></tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="card">
        <h3>Recent</h3>
        <p className="hint">The last few, so a surprising count can be inspected without waiting.</p>
        <div className="scroll">
          <table>
            <thead>
              <tr><th>Time</th><th>Symbol</th><th>Stage</th><th>Condition</th><th>Detail</th></tr>
            </thead>
            <tbody>
              {(data?.recent ?? []).map((r, i) => (
                <tr key={i}>
                  <td className="muted">{istTime(r.at)}</td>
                  <td>{r.symbol}</td>
                  <td className="muted">{r.stage}</td>
                  <td>{r.condition}</td>
                  <td className="muted">{r.detail}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </>
  )
}
