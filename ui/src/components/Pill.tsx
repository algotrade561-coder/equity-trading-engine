type Tone = 'good' | 'warn' | 'bad' | 'neutral'

/**
 * A small status badge.
 *
 * `tone` is passed rather than derived from the text: what counts as good depends entirely on
 * context. TRADING ARMED is red here — the engine being able to spend money unattended is the state
 * an operator most needs to notice, not a success.
 */
export function Pill({ tone = 'neutral', children }: { tone?: Tone; children: React.ReactNode }) {
  return (
    <span className={`pill ${tone === 'neutral' ? '' : tone}`}>
      <span className="dot" />
      {children}
    </span>
  )
}
