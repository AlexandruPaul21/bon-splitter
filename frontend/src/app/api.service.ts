import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AuthState, Balances, Group, Receipt, ReceiptUpdate } from './models';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  me(): Observable<AuthState> {
    return this.http.get<AuthState>('/api/auth/me', { withCredentials: true });
  }

  setup(password: string): Observable<AuthState> {
    return this.http.post<AuthState>('/api/auth/setup', { password }, { withCredentials: true });
  }

  login(password: string): Observable<AuthState> {
    return this.http.post<AuthState>('/api/auth/login', { password }, { withCredentials: true });
  }

  logout(): Observable<void> {
    return this.http.post<void>('/api/auth/logout', {}, { withCredentials: true });
  }

  groups(): Observable<Group[]> {
    return this.http.get<Group[]>('/api/groups', { withCredentials: true });
  }

  createGroup(name: string, currency: string): Observable<Group> {
    return this.http.post<Group>('/api/groups', { name, currency }, { withCredentials: true });
  }

  addMember(groupId: string, name: string): Observable<Group> {
    return this.http.post<Group>(`/api/groups/${encodeURIComponent(groupId)}/members`, { name }, { withCredentials: true });
  }

  removeMember(groupId: string, memberId: string): Observable<void> {
    return this.http.delete<void>(`/api/groups/${encodeURIComponent(groupId)}/members/${encodeURIComponent(memberId)}`, { withCredentials: true });
  }

  receipts(groupId: string): Observable<Receipt[]> {
    return this.http.get<Receipt[]>(`/api/groups/${encodeURIComponent(groupId)}/receipts`, { withCredentials: true });
  }

  createReceipt(groupId: string, file?: File): Observable<Receipt> {
    const form = new FormData();
    if (file) form.append('file', file, file.name);
    return this.http.post<Receipt>(`/api/groups/${encodeURIComponent(groupId)}/receipts`, form, { withCredentials: true });
  }

  updateReceipt(receiptId: string, update: ReceiptUpdate): Observable<Receipt> {
    return this.http.put<Receipt>(`/api/receipts/${encodeURIComponent(receiptId)}`, update, { withCredentials: true });
  }

  confirmReceipt(receiptId: string): Observable<Receipt> {
    return this.http.post<Receipt>(`/api/receipts/${encodeURIComponent(receiptId)}/confirm`, {}, { withCredentials: true });
  }

  receiptFile(receiptId: string): Observable<Blob> {
    return this.http.get(`/api/receipts/${encodeURIComponent(receiptId)}/file`, { responseType: 'blob', withCredentials: true });
  }

  balances(groupId: string): Observable<Balances> {
    return this.http.get<Balances>(`/api/groups/${encodeURIComponent(groupId)}/balances`, { withCredentials: true });
  }
}
