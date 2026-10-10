import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, Subscription, catchError, distinctUntilChanged, forkJoin, map, of, switchMap, timer } from 'rxjs';
import { MarketService, Company, Quote, History, Financials, errorMessage } from './market.service';

type Row = { symbol: string; quote: Quote | null; error: string | null; loading: boolean };
type Point = { date: string; value: number };
type View = 'overview' | 'watchlist' | 'screener' | 'financials';
@Component({ selector: 'app-root', standalone: true, imports: [CommonModule, FormsModule], templateUrl: './app.component.html' })
export class AppComponent {
  private api = inject(MarketService);
  private destroy = inject(DestroyRef);
  private searchTerms = new Subject<string>();
  private companyRequest?: Subscription;
  private watchRequest?: Subscription;
  view = signal<View>('overview');
  symbol = signal('NVDA');
  range = signal('1y');
  chartTab = signal<'price' | 'revenue' | 'netIncome'>('price');
  history = signal<History | null>(null);
  financials = signal<Financials | null>(null);
  loading = signal(true);
  priceError = signal('');
  financialError = signal('');
  searchError = signal('');
  searchLoading = signal(false);
  query = '';
  results = signal<Company[]>([]);
  searched = signal(false);
  watchlist = signal<string[]>(this.readWatchlist());
  watchRows = signal<Row[]>([]);
  marketRows = signal<Row[]>([]);
  dark = signal(this.storageGet('fiscal-dark') === 'true');
  notification = signal('');
  filter = signal('all');
  sort = signal<'symbol' | 'price' | 'changePercent' | 'volume'>('symbol');
  ascending = signal(true);
  hoverPoint = signal<Point | null>(null);
  ranges = [{ value:'1mo', label:'1M' },{ value:'3mo', label:'3M' },{ value:'6mo', label:'6M' },{ value:'1y', label:'1Y' },{ value:'5y', label:'5Y' }];
  financialMetrics = [{ key:'revenue', label:'Revenue' },{ key:'grossProfit', label:'Gross profit' },{ key:'operatingIncome', label:'Operating income' },{ key:'netIncome', label:'Net income' },{ key:'freeCashFlow', label:'Free cash flow' }];
  headings: Record<View, string> = { overview:'Market overview', watchlist:'Your watchlist', screener:'Company explorer', financials:'Company financials' };
  indexNames: Record<string, string> = { '^GSPC':'S&P 500', '^IXIC':'NASDAQ Composite', '^DJI':'Dow Jones', '^VIX':'VIX' };
  quote = computed(() => this.history()?.quote ?? null);
  isWatched = computed(() => this.watchlist().includes(this.symbol()));
  latestPeriod = computed(() => this.financials()?.periods.at(-1) ?? null);
  margin = computed(() => {
    const p = this.latestPeriod()?.values;
    return p && p['revenue'] && p['netIncome'] != null ? 100 * p['netIncome'] / p['revenue'] : null;
  });
  visibleRows = computed(() => {
    const key = this.sort();
    return this.watchRows().filter(r => this.filter() === 'all' ||
      (r.quote?.changePercent != null && (this.filter() === 'gainers' ? r.quote.changePercent >= 0 : r.quote.changePercent < 0)))
      .slice().sort((a,b) => {
        if (key === 'symbol') return a.symbol.localeCompare(b.symbol) * (this.ascending() ? 1 : -1);
        const av = a.quote?.[key], bv = b.quote?.[key];
        if (av == null) return 1; if (bv == null) return -1;
        return (av - bv) * (this.ascending() ? 1 : -1);
      });
  });
  points = computed<Point[]>(() => this.chartTab() === 'price'
    ? (this.history()?.bars.map(b => ({ date:b.date, value:b.close })) ?? [])
    : (this.financials()?.periods.filter(p => p.values[this.chartTab()] != null).map(p => ({ date:p.date, value:p.values[this.chartTab()] })) ?? []));
  chart = computed(() => {
    const p = this.points();
    if (!p.length) return { line:'', area:'', ticks:[] as number[], positions:[] as {x:number;y:number;point:Point}[] };
    let low = Math.min(...p.map(p => p.value)), high = Math.max(...p.map(p => p.value));
    const pad = (high - low || Math.abs(high) || 1) * .12; low -= pad; high += pad;
    const positions = p.map((point,i) => ({ x:20 + (p.length === 1 ? 280 : i / (p.length - 1) * 560), y:200 - (point.value - low) / (high - low) * 180, point }));
    const line = positions.map(p => `${p.x.toFixed(2)},${p.y.toFixed(2)}`).join(' ');
    return { line, area:`${positions[0].x},210 ${line} ${positions.at(-1)!.x},210`, ticks:[high,(high+low)/2,low], positions };
  });
  constructor() {
    this.searchTerms.pipe(distinctUntilChanged(), switchMap(q => {
      if (!q.trim()) return of({ companies:[] as Company[], error:'' });
      this.searchLoading.set(true); this.searchError.set('');
      return timer(350).pipe(switchMap(() => this.api.search(q)), map(r => ({...r,error:''})), catchError(e => of({ companies:[] as Company[],error:errorMessage(e) })));
    }), takeUntilDestroyed(this.destroy)).subscribe(r => { this.results.set(r.companies); this.searchError.set(r.error); this.searchLoading.set(false); this.searched.set(!!this.query.trim()); });
    this.loadCompany(); this.loadWatchlist(); this.loadMarkets();
    this.destroy.onDestroy(() => { this.companyRequest?.unsubscribe(); this.watchRequest?.unsubscribe(); });
  }
  storageGet(key:string) { try { return localStorage.getItem(key); } catch { return null; } }
  readWatchlist(): string[] {
    try { const data = JSON.parse(this.storageGet('akhil-watch') ?? this.storageGet('fiscal-watch') ?? 'null');
      if (Array.isArray(data)) return [...new Set(data.filter(x => typeof x === 'string' && /^\^?[A-Z0-9][A-Z0-9.\-]{0,14}$/.test(x)))].slice(0,20);
    } catch { /* A corrupt browser preference must not block market data. */ }
    return ['NVDA','AAPL','MSFT','GOOGL','AMZN'];
  }
  setView(view:View) { this.view.set(view); }
  onSearch(value:string) { this.query=value; this.results.set([]); this.searched.set(false); this.searchTerms.next(value); }
  selectCompany(symbol:string) {
    this.symbol.set(symbol); this.query=''; this.results.set([]); this.searched.set(false); this.searchTerms.next(''); this.loadCompany();
  }
  changeRange(value:string) { this.range.set(value); this.loadCompany(); }
  changeChart(value:'price'|'revenue'|'netIncome') { this.chartTab.set(value); this.hoverPoint.set(null); }
  loadCompany() {
    this.companyRequest?.unsubscribe(); this.loading.set(true); this.history.set(null); this.financials.set(null);
    this.priceError.set(''); this.financialError.set(''); this.hoverPoint.set(null);
    this.companyRequest = forkJoin({
      history:this.api.history(this.symbol(),this.range()).pipe(map(data => ({data,error:''})),catchError(e => of({data:null,error:errorMessage(e)}))),
      financials:this.api.financials(this.symbol()).pipe(map(data => ({data,error:''})),catchError(e => of({data:null,error:errorMessage(e)})))
    }).subscribe(r => { this.history.set(r.history.data); this.priceError.set(r.history.error); this.financials.set(r.financials.data); this.financialError.set(r.financials.error); this.loading.set(false); });
  }
  private rowRequest(symbol:string) { return this.api.quote(symbol).pipe(map(quote => ({symbol,quote,error:null,loading:false} as Row)), catchError(e => of({symbol,quote:null,error:errorMessage(e),loading:false} as Row))); }
  loadWatchlist() {
    this.watchRequest?.unsubscribe(); this.watchRows.set(this.watchlist().map(symbol => ({symbol,quote:null,error:null,loading:true})));
    if (!this.watchlist().length) return;
    this.watchRequest = forkJoin(this.watchlist().map(s => this.rowRequest(s))).subscribe(rows => this.watchRows.set(rows));
  }
  loadMarkets() {
    const symbols=Object.keys(this.indexNames); this.marketRows.set(symbols.map(symbol => ({symbol,quote:null,error:null,loading:true})));
    forkJoin(symbols.map(s => this.rowRequest(s))).pipe(takeUntilDestroyed(this.destroy)).subscribe(rows => this.marketRows.set(rows));
  }
  refresh() { this.loadCompany(); this.loadWatchlist(); this.loadMarkets(); this.notify('Reloading provider data. Responses may be cached for five minutes.'); }
  toggleWatchlist() {
    if (!this.isWatched() && this.watchlist().length >= 20) { this.notify('Your watchlist is limited to 20 companies.'); return; }
    const next = this.isWatched() ? this.watchlist().filter(s => s !== this.symbol()) : [...this.watchlist(),this.symbol()];
    this.watchlist.set(next); try { localStorage.setItem('akhil-watch',JSON.stringify(next)); } catch { this.notify('Watchlist changed, but browser storage is unavailable.'); }
    this.loadWatchlist();
  }
  toggleTheme() { this.dark.update(v => !v); try { localStorage.setItem('fiscal-dark',String(this.dark())); } catch { /* Optional preference */ } }
  sortBy(key:'symbol'|'price'|'changePercent'|'volume') { this.ascending.set(this.sort()===key ? !this.ascending() : true); this.sort.set(key); }
  money(value:number|null|undefined,currency=this.quote()?.currency||'USD') {
    if (value == null) return '—';
    try { return new Intl.NumberFormat('en-US',{style:'currency',currency,maximumFractionDigits:2}).format(value); }
    catch { return `${value.toFixed(2)} ${currency}`; }
  }
  compact(value:number|null|undefined) { return value == null ? '—' : new Intl.NumberFormat('en-US',{notation:'compact',maximumFractionDigits:2}).format(value); }
  percent(value:number|null|undefined) { return value == null ? '—' : `${value>=0?'+':''}${value.toFixed(2)}%`; }
  notify(message:string) { this.notification.set(message); setTimeout(() => this.notification.set(''),4500); }
  exportCsv() {
    let rows:(string|number)[][];
    if (this.view()==='financials') {
      const periods=this.financials()?.periods??[];
      rows=[['Metric',...periods.map(p=>`${p.date} (${p.currency})`)],...this.financialMetrics.map(m=>[m.label,...periods.map(p=>p.values[m.key]??'')])];
    } else rows=[['Symbol','Company','Price','Currency','Change percent','Volume','As of','Source'],...this.visibleRows().filter(r=>r.quote).map(r=>{const q=r.quote!;return [q.symbol,q.name,q.price,q.currency,q.changePercent??'',q.volume??'',q.asOf,q.source];})];
    const csv=rows.map(row=>row.map(v=>'"'+String(v).replaceAll('"','""')+'"').join(',')).join('\r\n');
    const url=URL.createObjectURL(new Blob([csv],{type:'text/csv;charset=utf-8'})); const a=document.createElement('a'); a.href=url;a.download=`akhil-ai-${this.view()}.csv`;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
  }
}
