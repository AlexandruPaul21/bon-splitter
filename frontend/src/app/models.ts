export interface AuthState {
  authenticated: boolean;
  setupRequired: boolean;
}

export interface Member {
  id: string;
  name: string;
}

export interface Group {
  id: string;
  name: string;
  currency: string;
  members: Member[];
}

export type ShareMode = 'AMOUNT' | 'PERCENT';
export type DiscountMode = 'PRICE' | 'FINAL';

export interface Share {
  memberId: string;
  /** Cents for AMOUNT, basis points for PERCENT. */
  value: number;
}

export interface ReceiptItem {
  description: string;
  amount: number;
  mode: ShareMode;
  shares: Share[];
  /** Marked savings on this item, in cents. */
  discount: number;
  /** PRICE keeps the discount on the item; FINAL shares it with everyone proportionally. */
  discountMode: DiscountMode;
}

export interface Receipt {
  id: string;
  groupId: string;
  status: 'DRAFT' | 'CONFIRMED' | 'PAID';
  store: string;
  date: string | null;
  subtotal: number;
  discount: number;
  tax: number;
  tip: number;
  total: number;
  payerId: string | null;
  items: ReceiptItem[];
  extractionNote: string | null;
  fileName: string | null;
}

export type ReceiptUpdate = Pick<Receipt,
  'store' | 'date' | 'subtotal' | 'discount' | 'tax' | 'tip' | 'total' | 'payerId' | 'items'>;

export interface MemberBalance {
  memberId: string;
  name: string;
  paid: number;
  owed: number;
  balance: number;
}

export interface Settlement {
  fromId: string;
  toId: string;
  amount: number;
}

export interface Balances {
  members: MemberBalance[];
  settlements: Settlement[];
}
