import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, EventEmitter, Input, OnChanges, OnDestroy, Output, SimpleChanges, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { TagModule } from 'primeng/tag';
import { finalize, of, switchMap } from 'rxjs';
import { ApiService } from './api.service';
import { Group, Receipt, ReceiptItem, ReceiptUpdate, ShareMode } from './models';
import { equalShares, formatMoney, moneyInput, parseMoney, parsePercent, percentInput } from './money';

interface EditableShare { memberId: string; value: string }
interface EditableItem { description: string; amount: string; mode: ShareMode; shares: EditableShare[] }

@Component({
  selector: 'app-receipt-editor',
  standalone: true,
  imports: [CommonModule, FormsModule, ButtonModule, DialogModule, InputTextModule, ProgressSpinnerModule, TagModule],
  templateUrl: './receipt-editor.component.html',
  styleUrls: ['./receipt-editor.component.css']
})
export class ReceiptEditorComponent implements OnChanges, OnDestroy {
  @Input({ required: true }) receipt!: Receipt;
  @Input({ required: true }) group!: Group;
  @Output() receiptChanged = new EventEmitter<Receipt>();
  @Output() close = new EventEmitter<void>();
  @Output() notice = new EventEmitter<{ severity: 'success' | 'error'; detail: string }>();

  private readonly api = inject(ApiService);
  private readonly sanitizer = inject(DomSanitizer);
  store = '';
  date = '';
  payerId = '';
  subtotal = '0.00';
  discount = '0.00';
  tax = '0.00';
  tip = '0.00';
  total = '0.00';
  items: EditableItem[] = [];
  attempted = false;
  busy = false;
  private pendingConfirm = false;
  error = '';
  previewOpen = false;
  previewLoading = false;
  previewError = '';
  previewUrl: string | null = null;
  previewPdf: SafeResourceUrl | null = null;
  previewType: 'image' | 'pdf' | 'other' = 'other';
  private previewRequest = 0;

  readonly parseMoney = parseMoney;
  readonly formatMoney = formatMoney;

  get isDraft(): boolean { return this.receipt.status === 'DRAFT'; }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['receipt'] || changes['group']) {
      if (!this.pendingConfirm) this.hydrate();
      this.closePreview();
    }
  }

  ngOnDestroy(): void { this.closePreview(); }

  private hydrate(): void {
    this.store = this.receipt.store ?? '';
    this.date = this.receipt.date?.slice(0, 10) ?? '';
    this.payerId = this.receipt.payerId ?? '';
    this.subtotal = moneyInput(this.receipt.subtotal);
    this.discount = moneyInput(this.receipt.discount);
    this.tax = moneyInput(this.receipt.tax);
    this.tip = moneyInput(this.receipt.tip);
    this.total = moneyInput(this.receipt.total);
    this.items = (this.receipt.items ?? []).map(item => ({
      description: item.description,
      amount: moneyInput(item.amount),
      mode: item.mode,
      shares: item.shares.map(share => ({
        memberId: share.memberId,
        value: item.mode === 'PERCENT' ? percentInput(share.value) : moneyInput(share.value)
      }))
    }));
    this.attempted = false;
    this.error = '';
  }

  get itemSum(): number {
    return this.items.reduce((sum, item) => sum + (parseMoney(item.amount) ?? 0), 0);
  }

  get summaryExpected(): number | null {
    const values = [this.subtotal, this.discount, this.tax, this.tip].map(parseMoney);
    if (values.some(value => value === null)) return null;
    return values[0]! - values[1]! + values[2]! + values[3]!;
  }

  private validDate(value: string): boolean {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
    const parsed = new Date(`${value}T00:00:00Z`);
    return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
  }

  get validationErrors(): string[] {
    const issues: string[] = [];
    if (!this.store.trim()) issues.push('Enter the store or a short receipt name.');
    if (!this.validDate(this.date)) issues.push('Choose a valid receipt date.');
    if (!this.group.members.some(member => member.id === this.payerId)) issues.push('Choose who paid.');
    if (this.items.length === 0) issues.push('Add at least one item.');
    for (const [index, item] of this.items.entries()) {
      const label = `Item ${index + 1}`;
      if (!item.description.trim()) issues.push(`${label} needs a description.`);
      const amount = parseMoney(item.amount);
      if (amount === null || amount <= 0) issues.push(`${label} needs an amount greater than zero.`);
      if (item.shares.length === 0) issues.push(`${label} needs at least one participant.`);
      const ids = item.shares.map(share => share.memberId);
      if (new Set(ids).size !== ids.length || ids.some(id => !this.group.members.some(member => member.id === id))) {
        issues.push(`${label} contains an invalid participant.`);
      }
      const values = item.shares.map(share => item.mode === 'PERCENT' ? parsePercent(share.value) : parseMoney(share.value));
      if (values.some(value => value === null) || values.reduce<number>((sum, value) => sum + (value ?? 0), 0) !== (item.mode === 'PERCENT' ? 10000 : amount)) {
        issues.push(`${label} shares must add up to ${item.mode === 'PERCENT' ? '100%' : formatMoney(amount ?? 0, this.group.currency)}.`);
      }
    }
    const subtotal = parseMoney(this.subtotal);
    const total = parseMoney(this.total);
    if (subtotal === null || total === null || [this.discount, this.tax, this.tip].some(value => parseMoney(value) === null)) {
      issues.push('Enter valid non-negative amounts (up to two decimal places) for all totals.');
    } else {
      if (subtotal !== this.itemSum || this.items.some(item => parseMoney(item.amount) === null)) {
        issues.push('Subtotal must match the sum of item amounts.');
      }
      if (parseMoney(this.discount)! > subtotal) issues.push('Discount cannot exceed the subtotal.');
      if (this.summaryExpected === null || this.summaryExpected < 0 || total !== this.summaryExpected) {
        issues.push('Total must equal subtotal − discount + tax + tip.');
      }
    }
    return issues;
  }

  addItem(): void {
    if (!this.isDraft || this.busy) return;
    const shares = equalShares(this.group.members.map(member => member.id), 0, 'AMOUNT');
    this.items.push({ description: '', amount: '0.00', mode: 'AMOUNT', shares: shares.map(share => ({
      memberId: share.memberId, value: moneyInput(share.value)
    })) });
  }

  removeItem(index: number): void {
    if (!this.isDraft || this.busy) return;
    this.items.splice(index, 1);
    this.recalculateTotals();
  }

  recalculateTotals(): void {
    if (!this.isDraft || this.busy) return;
    this.subtotal = moneyInput(this.itemSum);
    this.recalculateTotal();
  }

  recalculateTotal(): void {
    if (!this.isDraft || this.busy) return;
    const expected = this.summaryExpected;
    if (expected !== null && expected >= 0) this.total = moneyInput(expected);
  }

  isIncluded(item: EditableItem, memberId: string): boolean {
    return item.shares.some(share => share.memberId === memberId);
  }

  toggleMember(item: EditableItem, memberId: string): void {
    if (!this.isDraft || this.busy) return;
    const included = this.isIncluded(item, memberId);
    item.shares = included ? item.shares.filter(share => share.memberId !== memberId) : [...item.shares, { memberId, value: '0.00' }];
    this.splitEqually(item);
  }

  changeMode(item: EditableItem, mode: ShareMode): void {
    if (!this.isDraft || this.busy || item.mode === mode) return;
    item.mode = mode;
    this.splitEqually(item);
  }

  splitEqually(item: EditableItem): void {
    if (!this.isDraft || this.busy) return;
    const ids = this.group.members.filter(member => this.isIncluded(item, member.id)).map(member => member.id);
    item.shares = equalShares(ids, parseMoney(item.amount) ?? 0, item.mode).map(share => ({
      memberId: share.memberId,
      value: item.mode === 'PERCENT' ? percentInput(share.value) : moneyInput(share.value)
    }));
  }

  share(item: EditableItem, memberId: string): EditableShare | undefined {
    return item.shares.find(share => share.memberId === memberId);
  }

  shareTotal(item: EditableItem): string {
    const values = item.shares.map(share => item.mode === 'PERCENT' ? parsePercent(share.value) : parseMoney(share.value));
    if (values.some(value => value === null)) return 'Invalid';
    const sum = values.reduce<number>((acc, value) => acc + value!, 0);
    return item.mode === 'PERCENT' ? `${percentInput(sum)}%` : formatMoney(sum, this.group.currency);
  }

  save(confirm = false): void {
    if (this.busy || !this.isDraft) return;
    this.attempted = confirm;
    this.error = '';
    if (confirm && this.validationErrors.length) return;
    // Drafts may be unfinished, but all values included in a PUT must still be valid integers.
    const monetaryInputs = [this.subtotal, this.discount, this.tax, this.tip, this.total];
    if (monetaryInputs.some(value => parseMoney(value) === null)
      || this.items.some(item => parseMoney(item.amount) === null
        || item.shares.some(share => (item.mode === 'PERCENT' ? parsePercent(share.value) : parseMoney(share.value)) === null))) {
      this.error = 'Amounts must be non-negative numbers with at most two decimal places before saving.';
      return;
    }
    if (!confirm && this.date && !this.validDate(this.date)) {
      this.error = 'Choose a valid receipt date, or leave the date blank for now.';
      return;
    }
    if (!confirm && this.payerId && !this.group.members.some(member => member.id === this.payerId)) {
      this.error = 'The selected payer is no longer in this group.';
      return;
    }
    if (!confirm && this.items.some(item => {
      const ids = item.shares.map(share => share.memberId);
      return new Set(ids).size !== ids.length || ids.some(id => !this.group.members.some(member => member.id === id));
    })) {
      this.error = 'One of the assigned people is no longer in this group.';
      return;
    }
    const payload: ReceiptUpdate = {
      store: this.store.trim(), date: this.date || null,
      payerId: this.payerId || null,
      subtotal: parseMoney(this.subtotal)!, discount: parseMoney(this.discount)!,
      tax: parseMoney(this.tax)!, tip: parseMoney(this.tip)!, total: parseMoney(this.total)!,
      items: this.items.map((item): ReceiptItem => ({
        description: item.description.trim(), amount: parseMoney(item.amount)!, mode: item.mode,
        shares: item.shares.map(share => ({ memberId: share.memberId,
          value: item.mode === 'PERCENT' ? parsePercent(share.value)! : parseMoney(share.value)! }))
      }))
    };
    this.busy = true;
    this.pendingConfirm = confirm;
    let saved: Receipt | null = null;
    const operation = this.api.updateReceipt(this.receipt.id, payload).pipe(
      switchMap(receipt => {
        saved = receipt;
        return confirm ? this.api.confirmReceipt(receipt.id) : of(receipt);
      }),
      finalize(() => { this.busy = false; this.pendingConfirm = false; })
    );
    operation.subscribe({
      next: receipt => {
        this.receiptChanged.emit(receipt);
        this.notice.emit({ severity: 'success', detail: confirm ? 'Receipt confirmed and included in balances.' : 'Draft saved.' });
      },
      error: (error: HttpErrorResponse) => {
        if (saved) this.receiptChanged.emit(saved);
        const message = error.error?.message;
        this.error = typeof message === 'string' && message.length < 300
          ? message : (confirm && saved ? 'Draft saved, but confirmation failed. Please review and retry.' : 'Could not save this receipt. Please try again.');
      }
    });
  }

  openPreview(): void {
    if (!this.receipt.fileName || this.previewLoading) return;
    this.closePreview();
    const request = ++this.previewRequest;
    this.previewOpen = true;
    this.previewLoading = true;
    this.api.receiptFile(this.receipt.id).pipe(finalize(() => { if (this.previewRequest === request) this.previewLoading = false; })).subscribe({
      next: blob => {
        if (this.previewRequest !== request || !this.previewOpen) return;
        const extension = this.receipt.fileName?.split('.').pop()?.toLowerCase();
        const pdf = blob.type === 'application/pdf' || extension === 'pdf';
        const image = blob.type.startsWith('image/') || ['jpg', 'jpeg', 'png', 'gif', 'webp', 'heic', 'heif'].includes(extension ?? '');
        const typedBlob = (blob.type && blob.type !== 'application/octet-stream') ? blob : new Blob([blob], { type: pdf ? 'application/pdf' : this.imageType(extension) });
        this.previewUrl = URL.createObjectURL(typedBlob);
        this.previewType = pdf ? 'pdf' : image ? 'image' : 'other';
        if (this.previewType === 'pdf') this.previewPdf = this.sanitizer.bypassSecurityTrustResourceUrl(this.previewUrl);
      },
      error: (error: HttpErrorResponse) => {
        if (this.previewRequest === request && this.previewOpen) {
          const message = error.error?.message;
          this.previewError = typeof message === 'string' && message.length < 300 ? message : 'Could not load the private receipt file.';
        }
      }
    });
  }

  private imageType(extension: string | undefined): string {
    const types: Record<string, string> = { jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', gif: 'image/gif', webp: 'image/webp', heic: 'image/heic', heif: 'image/heif' };
    return types[extension ?? ''] ?? 'application/octet-stream';
  }

  closePreview(): void {
    ++this.previewRequest;
    this.previewOpen = false;
    this.previewLoading = false;
    if (this.previewUrl) URL.revokeObjectURL(this.previewUrl);
    this.previewUrl = null;
    this.previewPdf = null;
    this.previewError = '';
  }
}
