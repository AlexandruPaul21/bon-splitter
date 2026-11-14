import { Member, ReceiptItem, ShareMode } from './models';

/** Never use binary floating-point arithmetic for stored money. */
export function parseMoney(value: string): number | null {
  const normalized = value.trim().replace(/^\$/, '');
  if (!/^\d+(?:\.\d{1,2})?$/.test(normalized)) return null;
  const [whole, fraction = ''] = normalized.split('.');
  const cents = Number(whole) * 100 + Number(fraction.padEnd(2, '0'));
  return Number.isSafeInteger(cents) ? cents : null;
}

export function moneyInput(cents: number | null | undefined): string {
  return ((cents ?? 0) / 100).toFixed(2);
}

export function formatMoney(cents: number, currency: string): string {
  try {
    return new Intl.NumberFormat(undefined, { style: 'currency', currency, minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(cents / 100);
  } catch {
    return `${moneyInput(cents)} ${currency}`;
  }
}

/** Enter percentages with up to two decimal places; the API takes integer basis points. */
export function parsePercent(value: string): number | null {
  const normalized = value.trim().replace(/%$/, '').trim();
  if (!/^\d+(?:\.\d{1,2})?$/.test(normalized)) return null;
  const [whole, fraction = ''] = normalized.split('.');
  const basisPoints = Number(whole) * 100 + Number(fraction.padEnd(2, '0'));
  return Number.isInteger(basisPoints) && basisPoints <= 10000 ? basisPoints : null;
}

export function percentInput(basisPoints: number): string {
  return (basisPoints / 100).toFixed(2);
}

/** Allocate remainders deterministically so the default always adds up exactly. */
export function equalShares(memberIds: string[], amount: number, mode: ShareMode): ReceiptItem['shares'] {
  if (memberIds.length === 0) return [];
  const total = mode === 'PERCENT' ? 10000 : amount;
  const each = Math.floor(total / memberIds.length);
  const extra = total % memberIds.length;
  return memberIds.map((memberId, index) => ({ memberId, value: each + (index < extra ? 1 : 0) }));
}

export function memberName(members: Member[], id: string | null | undefined): string {
  return members.find(member => member.id === id)?.name ?? 'Unknown member';
}
