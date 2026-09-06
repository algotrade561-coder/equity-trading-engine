import { Pill } from './Pill'
import type { Status } from '../lib/api'

/**
 * The three facts that decide whether this engine can spend money, shown on every screen.
 *
 * All three must line up before an order can be sent — REPLAY or the master switch off or nobody
 * armed each make it inert — so showing them together is showing the answer rather than three
 * numbers the reader has to combine.
 */
export function StatusPills({ status }: { status: Status | null }) {
  if (!status) return null

  const live = status.mode === 'LIVE'
  const canTrade = live && status.tradingEnabled && status.armedUsers > 0

  return (
    <>
      <Pill tone={live ? 'warn' : 'neutral'}>{status.mode}</Pill>
      <Pill tone={status.tradingEnabled ? 'warn' : 'good'}>
        {status.tradingEnabled ? 'TRADING ON' : 'TRADING OFF'}
      </Pill>
      <Pill tone={canTrade ? 'bad' : 'neutral'}>
        {canTrade ? 'CAN SEND ORDERS' : 'INERT'}
      </Pill>
      <Pill tone={status.marketOpen ? 'good' : 'neutral'}>
        {status.marketOpen ? 'MARKET OPEN' : 'MARKET CLOSED'}
      </Pill>
    </>
  )
}
