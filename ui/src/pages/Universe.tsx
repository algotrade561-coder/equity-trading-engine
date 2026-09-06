import { usePoll } from '../lib/usePoll'
import { count } from '../lib/format'
import type { Universe as UniverseData } from '../lib/api'

/** What the engine is watching, and anything it was asked to watch but could not find. */
export function Universe() {
  const { data, error } = usePoll<UniverseData>('/api/universe', 8000)

  return (
    <>
      {error && <div className="card bad"><div className="error">{error}</div></div>}

      <div className="grid three" style={{ marginBottom: '1rem' }}>
        <div className="stat">
          <div className="label">Subscribed</div>
          <div className="value">{count(data?.subscribed)}</div>
        </div>
        <div className="stat">
          <div className="label">Evaluated</div>
          <div className="value">{count(data?.evaluated)}</div>
        </div>
        <div className="stat">
          <div className="label">Ticks</div>
          <div className="value">{count(data?.ticksSeen)}</div>
        </div>
      </div>

      {(data?.unresolved?.length ?? 0) > 0 && (
        <section className="card warn">
          <h3>Unresolved symbols</h3>
          <p className="hint">
            Configured but with no instrument token, so they will never tick — usually an index
            constituent that has been renamed or delisted. Without this list they would look like
            stocks that simply never trade.
          </p>
          <p className="down" style={{ fontFamily: 'var(--mono)' }}>
            {data!.unresolved.join(', ')}
          </p>
        </section>
      )}

      <section className="card">
        <h3>Watching</h3>
        <p className="hint">
          {data?.evaluatingAll
            ? 'Every subscribed stock is evaluated — eligibility is decided by the strategy conditions, not by rank.'
            : 'Only the ranked discovery set is evaluated.'}
        </p>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '.35rem' }}>
          {(data?.discoverySet ?? []).map(s => (
            <span key={s} className="pill" style={{ fontFamily: 'var(--mono)' }}>{s}</span>
          ))}
        </div>
      </section>
    </>
  )
}
