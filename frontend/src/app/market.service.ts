import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';

export interface Company { symbol: string; name: string; exchange: string; type: string }
export interface Quote { symbol: string; name: string; exchange: string; currency: string; price: number; previousClose: number | null; changePercent: number | null; asOf: string; fiftyTwoWeekHigh: number | null; fiftyTwoWeekLow: number | null; volume: number | null; source: string; fetchedAt: string }
export interface Bar { date: string; close: number; volume: number | null }
export interface History { quote: Quote; range: string; bars: Bar[] }
export interface FinancialPeriod { date: string; currency: string; values: Record<string, number> }
export interface Financials { symbol: string; source: string; sourceUrl: string; fetchedAt: string; periods: FinancialPeriod[] }
@Injectable({ providedIn: 'root' })
export class MarketService {
  private http = inject(HttpClient);
  search(q: string) { return this.http.get<{companies: Company[]}>('/api/search', { params: { q } }); }
  quote(symbol: string) { return this.http.get<Quote>(`/api/stocks/${encodeURIComponent(symbol)}/quote`); }
  history(symbol: string, range: string) { return this.http.get<History>(`/api/stocks/${encodeURIComponent(symbol)}/history`, { params: { range } }); }
  financials(symbol: string) { return this.http.get<Financials>(`/api/stocks/${encodeURIComponent(symbol)}/financials`); }
}
export function errorMessage(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0 || error.status === 504) return 'Cannot reach the Java API. Check that the backend is running, then retry.';
    if (typeof error.error?.message === 'string') return error.error.message;
    if (error.status === 404) return 'This data is not available from the provider.';
  }
  return 'Data could not be loaded. Please retry.';
}
