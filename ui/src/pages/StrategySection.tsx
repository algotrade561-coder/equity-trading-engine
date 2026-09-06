import { useEffect, useState } from 'react'
import { api, type StrategyThresholds } from '../lib/api'

/**
 * The conditions that decide what gets traded.
 *
 * Grouped the way the strategy evaluates them — mandatory, then the setup, then the trade — rather
 * than alphabetically, so the order on screen matches the order a candidate is rejected in. That is
 * what makes the rejection counts on the other page readable against this one.
 */

type Field = { key: keyof StrategyThresholds; label: string; hint: string; step?: number }

const GROUPS: { title: string; hint: string; fields: Field[] }[] = [
  {
    title: 'Mandatory',
    hint: 'Must hold at every moment. A setup cannot survive on structure that has already broken.',
    fields: [
      { key: 'minDayChangePercent', label: 'Min day change %', step: 0.1,
        hint: 'A stock that is not up is not a momentum candidate' },
      { key: 'maxDayChangePercent', label: 'Max day change %', step: 0.5,
        hint: 'Already extended — the move has largely happened' },
      { key: 'sanityBandPercent', label: 'Corporate-action band %', step: 1,
        hint: 'Beyond this, assume an unadjusted split rather than a real move' },
      { key: 'maxDistanceFromHighPct', label: 'Max below day high %', step: 0.1,
        hint: 'How far off the high a candidate may sit' },
    ],
  },
  {
    title: 'Setup',
    hint: 'The impulse, the pause, and what arms the trade. Advanced only by completed 1m candles.',
    fields: [
      { key: 'minImpulseReturn5m', label: 'Min 5m thrust %', step: 0.1,
        hint: 'The move that defines an impulse leg' },
      { key: 'minRelativeVolume', label: 'Min relative volume', step: 0.1,
        hint: 'The thrust must carry volume, not drift' },
      { key: 'minPullbackPercent', label: 'Min pullback %', step: 0.1,
        hint: 'Shallower than this and it has not actually paused' },
      { key: 'maxPullbackPercent', label: 'Max pullback %', step: 0.1,
        hint: 'Deeper and it is a reversal, not a pullback' },
      { key: 'maxConsolidationRangePct', label: 'Max coil range %', step: 0.1,
        hint: 'Wider than this is not a consolidation' },
      { key: 'minConsolidationBars', label: 'Min pause bars', step: 1,
        hint: 'How many 1m bars the pause must hold' },
      { key: 'triggerBufferPercent', label: 'Trigger buffer %', step: 0.01,
        hint: 'How far above the pause high price must trade' },
    ],
  },
  {
    title: 'The trade',
    hint: 'Stop, target, and how long a position may stay unresolved.',
    fields: [
      { key: 'stopAtrMultiple', label: 'Stop floor (× ATR)', step: 0.1,
        hint: 'The stop is the wider of the pause low and this — it can only widen, never tighten' },
      { key: 'targetRMultiple', label: 'Target (R)', step: 0.25,
        hint: 'A multiple of the risk actually taken, so a widened stop moves the target out with it' },
      { key: 'timeStopMinutes', label: 'Time stop (min)', step: 5,
        hint: 'Close a position that has resolved neither way' },
    ],
  },
]

const FLAGS: Field[] = [
  { key: 'requireAboveVwap', label: 'Require above VWAP', hint: "The session's fair value" },
  { key: 'requireEmaStack', label: 'Require EMA stack', hint: 'ema9 above ema20' },
  { key: 'requireOutperformIndex', label: 'Require index outperformance',
    hint: 'Relative strength against NIFTY must be positive' },
]

const TIMES: Field[] = [
  { key: 'entryWindowStart', label: 'Entry window opens', hint: 'The open is not this strategy’s edge' },
  { key: 'entryWindowEnd', label: 'Entry window closes', hint: 'No new entries after this' },
  { key: 'squareOffTime', label: 'Square off',
    hint: "Everything flat by here, ahead of the broker's own square-off" },
]

export function StrategySection() {
  const [t, setT] = useState<StrategyThresholds | null>(null)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ text: string; bad: boolean } | null>(null)

  useEffect(() => {
    api.get<StrategyThresholds>('/api/settings/thresholds').then(setT).catch(() => {})
  }, [])

  async function save() {
    if (!t) return
    setBusy(true); setMessage(null)
    try {
      setT(await api.post<StrategyThresholds>('/api/settings/thresholds', t))
      setMessage({ text: 'Saved, and applied to the running engine immediately.', bad: false })
    } catch (e) {
      setMessage({ text: e instanceof Error ? e.message : String(e), bad: true })
    } finally {
      setBusy(false)
    }
  }

  if (!t) {
    return <section className="card"><h3>Entry conditions</h3><p className="muted">Loading…</p></section>
  }

  return (
    <section className="card">
      <h3>Entry conditions</h3>
      <p className="hint">
        Every one is a hard threshold on a named condition — there is no weighted score, so a strong
        reading on one axis cannot buy a failing reading on another. A candidate that fails one is
        rejected at that named stage, which is what makes the rejection counts readable.
      </p>

      {GROUPS.map(group => (
        <div key={group.title} style={{ marginBottom: '1.1rem' }}>
          <label style={{ color: 'var(--ink-dim)', fontSize: '.8rem', marginBottom: '.1rem' }}>
            {group.title}
          </label>
          <p className="hint" style={{ marginBottom: '.6rem' }}>{group.hint}</p>
          <div className="grid three">
            {group.fields.map(f => (
              <div key={String(f.key)}>
                <label htmlFor={String(f.key)} title={f.hint}>{f.label}</label>
                <input
                  id={String(f.key)}
                  type="number"
                  step={f.step ?? 1}
                  value={t[f.key] as number}
                  onChange={e => setT({ ...t, [f.key]: Number(e.target.value) })}
                />
              </div>
            ))}
          </div>
        </div>
      ))}

      <div className="grid three" style={{ marginBottom: '1.1rem' }}>
        {FLAGS.map(f => (
          <div key={String(f.key)}>
            <label title={f.hint}>{f.label}</label>
            <button
              style={{ width: '100%' }}
              className={t[f.key] ? 'primary' : ''}
              onClick={() => setT({ ...t, [f.key]: !t[f.key] })}
            >
              {t[f.key] ? 'REQUIRED' : 'off'}
            </button>
          </div>
        ))}
      </div>

      <div className="grid three">
        {TIMES.map(f => (
          <div key={String(f.key)}>
            <label htmlFor={String(f.key)} title={f.hint}>{f.label}</label>
            <input
              id={String(f.key)}
              type="time"
              step={1}
              value={String(t[f.key])}
              onChange={e => setT({ ...t, [f.key]: e.target.value })}
            />
          </div>
        ))}
      </div>

      <div className="row" style={{ marginTop: '.9rem' }}>
        <button className="primary" disabled={busy} onClick={save}>Save entry conditions</button>
      </div>
      {message && (
        <p className={message.bad ? 'error' : 'ok'} style={{ marginTop: '.6rem' }}>{message.text}</p>
      )}

      <p className="note">
        Square-off must be after the entry window closes, or a position could be opened after the
        time it would be closed. The engine refuses that combination rather than accepting it quietly.
      </p>
    </section>
  )
}
