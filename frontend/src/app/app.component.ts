import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { ConfirmationService, MessageService } from 'primeng/api';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { TagModule } from 'primeng/tag';
import { ToastModule } from 'primeng/toast';
import { finalize } from 'rxjs';
import { ApiService } from './api.service';
import { Balances, Group, Member, Receipt } from './models';
import { formatMoney, memberName } from './money';
import { ReceiptEditorComponent } from './receipt-editor.component';

type GroupTab = 'receipts' | 'balances' | 'members';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule, ButtonModule, ConfirmDialogModule, DialogModule, InputTextModule,
    ProgressSpinnerModule, TagModule, ToastModule, ReceiptEditorComponent],
  providers: [ConfirmationService],
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly messages = inject(MessageService);
  private readonly confirmation = inject(ConfirmationService);

  authLoading = true;
  authenticated = false;
  setupRequired = false;
  password = '';
  confirmPassword = '';
  showPassword = false;
  authBusy = false;
  authError = '';

  groups: Group[] = [];
  groupsLoading = false;
  groupsError = '';
  activeGroupId: string | null = null;
  tab: GroupTab = 'receipts';
  receipts: Receipt[] = [];
  receiptsLoading = false;
  receiptsError = '';
  private receiptsRequest = 0;
  balances: Balances | null = null;
  balancesLoading = false;
  private balancesRequest = 0;
  balancesError = '';
  selectedReceiptId: string | null = null;

  groupDialogOpen = false;
  groupName = '';
  groupCurrency = 'USD';
  groupBusy = false;
  groupError = '';
  memberNameInput = '';
  memberBusy = false;
  memberError = '';
  uploadDialogOpen = false;
  selectedFile: File | null = null;
  uploadBusy = false;
  uploadError = '';
  loggingOut = false;
  mobileNavOpen = false;

  readonly formatMoney = formatMoney;

  get activeGroup(): Group | undefined { return this.groups.find(group => group.id === this.activeGroupId); }
  get selectedReceipt(): Receipt | undefined { return this.receipts.find(receipt => receipt.id === this.selectedReceiptId); }
  get confirmedReceipts(): Receipt[] { return this.receipts.filter(receipt => receipt.status === 'CONFIRMED'); }
  get draftReceipts(): Receipt[] { return this.receipts.filter(receipt => receipt.status === 'DRAFT'); }
  get totalSpent(): number { return this.confirmedReceipts.reduce((sum, receipt) => sum + receipt.total, 0); }
  get sortedReceipts(): Receipt[] { return [...this.receipts].sort((a, b) => (b.date ?? '').localeCompare(a.date ?? '')); }

  ngOnInit(): void { this.checkSession(); }

  checkSession(): void {
    this.authLoading = true;
    this.authError = '';
    this.api.me().pipe(finalize(() => { this.authLoading = false; })).subscribe({
      next: state => {
        this.authenticated = state.authenticated;
        this.setupRequired = state.setupRequired;
        if (this.authenticated) this.loadGroups();
      },
      error: () => { this.authError = 'Unable to connect. Check that the server is running, then try again.'; }
    });
  }

  submitAuth(): void {
    if (this.authBusy) return;
    this.authError = '';
    if (!this.password) { this.authError = 'Enter a password to continue.'; return; }
    if (this.setupRequired && this.password.length < 12) { this.authError = 'Use at least 12 characters for your password.'; return; }
    if (this.setupRequired && this.password !== this.confirmPassword) { this.authError = 'Passwords do not match.'; return; }
    this.authBusy = true;
    (this.setupRequired ? this.api.setup(this.password) : this.api.login(this.password))
      .pipe(finalize(() => { this.authBusy = false; })).subscribe({
        next: state => {
          this.authenticated = state.authenticated;
          this.setupRequired = state.setupRequired;
          this.password = '';
          this.confirmPassword = '';
          if (state.authenticated) this.loadGroups();
          else this.authError = 'Authentication did not complete. Please try again.';
        },
        error: error => { this.authError = this.errorText(error, this.setupRequired ? 'Could not finish setup.' : 'Incorrect password or login unavailable.'); }
      });
  }

  logout(): void {
    if (this.loggingOut) return;
    this.loggingOut = true;
    this.api.logout().pipe(finalize(() => { this.loggingOut = false; })).subscribe({
      next: () => {
        this.authenticated = false;
        this.groups = [];
        this.activeGroupId = null;
        this.selectedReceiptId = null;
        this.receipts = [];
        this.balances = null;
        this.mobileNavOpen = false;
      },
      error: error => this.showError(this.errorText(error, 'Could not sign out. Please try again.'))
    });
  }

  loadGroups(preferredId?: string): void {
    this.groupsLoading = true;
    this.groupsError = '';
    this.api.groups().pipe(finalize(() => { this.groupsLoading = false; })).subscribe({
      next: groups => {
        this.groups = groups;
        const nextId = preferredId ?? (groups.some(group => group.id === this.activeGroupId) ? this.activeGroupId : groups[0]?.id);
        if (nextId) this.selectGroup(nextId);
        else { this.activeGroupId = null; this.receipts = []; this.balances = null; }
      },
      error: error => this.groupsError = this.errorText(error, 'Unable to load groups. Please retry.')
    });
  }

  selectGroup(id: string): void {
    if (!this.groups.some(group => group.id === id)) return;
    const changed = this.activeGroupId !== id;
    this.activeGroupId = id;
    if (changed) {
      this.tab = 'receipts';
      this.selectedReceiptId = null;
      this.receipts = [];
      this.balances = null;
    }
    this.mobileNavOpen = false;
    this.refreshReceipts(id);
    this.refreshBalances(id);
  }

  showTab(tab: GroupTab): void {
    this.selectedReceiptId = null;
    this.tab = tab;
    this.mobileNavOpen = false;
    if (tab === 'balances' && this.activeGroupId) this.refreshBalances(this.activeGroupId);
  }

  refreshReceipts(id = this.activeGroupId): void {
    if (!id) return;
    const request = ++this.receiptsRequest;
    this.receiptsLoading = true;
    this.receiptsError = '';
    this.api.receipts(id).pipe(finalize(() => {
      if (this.activeGroupId === id && this.receiptsRequest === request) this.receiptsLoading = false;
    })).subscribe({
      next: receipts => { if (this.activeGroupId === id && this.receiptsRequest === request) this.receipts = receipts; },
      error: error => { if (this.activeGroupId === id && this.receiptsRequest === request) this.receiptsError = this.errorText(error, 'Could not load receipts.'); }
    });
  }

  refreshBalances(id = this.activeGroupId): void {
    if (!id) return;
    const request = ++this.balancesRequest;
    this.balancesLoading = true;
    this.balancesError = '';
    this.api.balances(id).pipe(finalize(() => {
      if (this.activeGroupId === id && this.balancesRequest === request) this.balancesLoading = false;
    })).subscribe({
      next: balances => { if (this.activeGroupId === id && this.balancesRequest === request) this.balances = balances; },
      error: error => { if (this.activeGroupId === id && this.balancesRequest === request) this.balancesError = this.errorText(error, 'Could not load balances.'); }
    });
  }

  createGroup(): void {
    if (this.groupBusy) return;
    this.groupError = '';
    const name = this.groupName.trim();
    if (!name) { this.groupError = 'Give this group a name.'; return; }
    this.groupCurrency = this.groupCurrency.trim().toUpperCase();
    if (!/^[A-Z]{3}$/.test(this.groupCurrency)) { this.groupError = 'Enter a three-letter currency code (e.g. USD).'; return; }
    this.groupBusy = true;
    this.api.createGroup(name, this.groupCurrency).pipe(finalize(() => { this.groupBusy = false; })).subscribe({
      next: group => {
        this.groupDialogOpen = false;
        this.groupName = '';
        this.groups = [...this.groups, group];
        this.selectGroup(group.id);
        this.showTab('members');
        this.showSuccess('Group created. Add your people to get started.');
      },
      error: error => this.groupError = this.errorText(error, 'Unable to create group.')
    });
  }

  addMember(): void {
    const group = this.activeGroup;
    if (!group || this.memberBusy) return;
    this.memberError = '';
    const name = this.memberNameInput.trim();
    if (!name) { this.memberError = 'Enter a name.'; return; }
    if (group.members.some(member => member.name.toLowerCase() === name.toLowerCase())) {
      this.memberError = 'This name is already in the group.';
      return;
    }
    this.memberBusy = true;
    this.api.addMember(group.id, name).pipe(finalize(() => { this.memberBusy = false; })).subscribe({
      next: updatedGroup => {
        this.groups = this.groups.map(item => item.id === group.id ? updatedGroup : item);
        this.memberNameInput = '';
        this.refreshBalances(group.id);
        this.showSuccess(`${name} joined ${group.name}.`);
      },
      error: error => this.memberError = this.errorText(error, 'Could not add this person.')
    });
  }

  askRemoveMember(member: Member): void {
    const group = this.activeGroup;
    if (!group || this.memberBusy) return;
    this.confirmation.confirm({
      header: 'Remove person?',
      message: `Remove ${member.name} from ${group.name}? People linked to receipts may not be removable.`,
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: 'Remove person',
      rejectLabel: 'Keep person',
      acceptButtonStyleClass: 'p-button-danger',
      accept: () => this.removeMember(group.id, member.id)
    });
  }

  private removeMember(groupId: string, memberId: string): void {
    this.memberBusy = true;
    this.memberError = '';
    this.api.removeMember(groupId, memberId).pipe(finalize(() => { this.memberBusy = false; })).subscribe({
      next: () => {
        this.groups = this.groups.map(group => group.id === groupId ? {
          ...group, members: group.members.filter(member => member.id !== memberId)
        } : group);
        this.refreshBalances(groupId);
        this.showSuccess('Person removed.');
      },
      error: error => this.memberError = this.errorText(error, 'Cannot remove this person. They may be linked to a receipt.')
    });
  }

  onFilePicked(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.selectedFile = input.files?.[0] ?? null;
    this.uploadError = '';
    if (this.selectedFile && !(['application/pdf'].includes(this.selectedFile.type) || this.selectedFile.type.startsWith('image/'))) {
      this.uploadError = 'Choose an image or PDF, or continue without a file.';
      this.selectedFile = null;
      input.value = '';
    }
  }

  createReceipt(): void {
    const group = this.activeGroup;
    if (!group || this.uploadBusy) return;
    this.uploadError = '';
    if (!group.members.length) { this.uploadError = 'Add a person to this group before creating a receipt.'; return; }
    if (this.selectedFile && !(['application/pdf'].includes(this.selectedFile.type) || this.selectedFile.type.startsWith('image/'))) {
      this.uploadError = 'Choose an image or PDF, or continue without a file.';
      return;
    }
    this.uploadBusy = true;
    this.api.createReceipt(group.id, this.selectedFile ?? undefined).pipe(finalize(() => { this.uploadBusy = false; })).subscribe({
      next: receipt => {
        if (this.activeGroupId === group.id) {
          this.receipts = [receipt, ...this.receipts.filter(item => item.id !== receipt.id)];
          this.selectedReceiptId = receipt.id;
          this.tab = 'receipts';
        }
        this.uploadDialogOpen = false;
        this.selectedFile = null;
        this.showSuccess('Draft created. Review and assign its items.');
      },
      error: error => this.uploadError = this.errorText(error, 'Could not create receipt. Please try again.')
    });
  }

  onReceiptChanged(receipt: Receipt): void {
    if (this.activeGroupId !== receipt.groupId) return;
    this.receipts = this.receipts.map(item => item.id === receipt.id ? receipt : item);
    if (receipt.status === 'CONFIRMED') {
      this.selectedReceiptId = null;
      this.refreshBalances(receipt.groupId);
    }
  }

  displayDate(date: string | null): string {
    if (!date) return 'Date not set';
    const dateOnly = date.slice(0, 10);
    const parsed = new Date(`${dateOnly}T00:00:00`);
    return Number.isNaN(parsed.getTime()) ? dateOnly : new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', year: 'numeric' }).format(parsed);
  }

  personName(id: string | null | undefined): string { return memberName(this.activeGroup?.members ?? [], id); }

  showSuccess(detail: string): void { this.messages.add({ severity: 'success', summary: 'Done', detail, life: 4200 }); }
  showError(detail: string): void { this.messages.add({ severity: 'error', summary: 'Something went wrong', detail, life: 6500 }); }

  private errorText(error: HttpErrorResponse, fallback: string): string {
    if (error.status === 0) return 'Cannot reach the server. Check your connection and try again.';
    if (error.status === 401 && this.authenticated) return 'Your session expired. Refresh and sign in again.';
    const message = error.error?.message;
    return typeof message === 'string' && message.length < 300 ? message : fallback;
  }
}
