/** Number and time formatting, in one place so the whole console reads consistently. */

/** Rupees, no decimals. Sign carried by a leading minus rather than parentheses. */
export function money(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  const sign = value < 0 ? '-' : ''
  return `${sign}\u20b9${Math.abs(value).toLocaleString('en-IN', { maximumFractionDigits: 0 })}`
}

export function price(value: number | null | undefined): string {
  if (!value || Number.isNaN(value)) return '—'
  return value.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

export function count(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : value.toLocaleString('en-IN')
}

/** The class that colours a P&L figure. Zero is neutral, not green. */
export function pnlClass(value: number): string {
  return value > 0 ? 'up' : value < 0 ? 'down' : 'muted'
}

/** An ISO instant as IST clock time — the only time zone this engine thinks in. */
export function istTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  try {
    return new Date(iso).toLocaleTimeString('en-IN', {
      timeZone: 'Asia/Kolkata', hour12: false,
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    })
  } catch {
    return iso
  }
}
