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
/** How a discount that applies to the whole receipt is split among people. */
export type ReceiptDiscountMode = 'MANUAL' | 'PRODUCTS';

export interface Share {
  memberId: string;
  /** Cents for AMOUNT, basis points for PERCENT. */
  value: number;
}

export interface ReceiptItem {
  description: string;
  /** Initial (printed) price, always visible; shares add up to the price actually split:
   * amount minus discount, unless the discount is split at the end (then the full amount). */
  amount: number;
  mode: ShareMode;
  shares: Share[];
  /** Marked savings on this item, in cents; never above amount. The actual price is amount minus discount. */
  discount: number;
  /** PRICE folds the saving into the actual price for this item's people; FINAL charges the full initial price and splits the saving equally among everyone on the receipt. */
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
  /** Saving that applies to the whole receipt (e.g. a Kaufland Card XTRA promo), not one product. */
  wholeDiscount: number;
  /** MANUAL uses discountShares; PRODUCTS prorates the saving across the items. */
  discountMode: ReceiptDiscountMode;
  discountShares: Share[];
  tax: number;
  tip: number;
  total: number;
  payerId: string | null;
  items: ReceiptItem[];
  extractionNote: string | null;
  fileName: string | null;
}

export type ReceiptUpdate = Pick<Receipt,
  'store' | 'date' | 'subtotal' | 'discount' | 'wholeDiscount' | 'discountMode' | 'discountShares'
  | 'tax' | 'tip' | 'total' | 'payerId' | 'items'>;

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
