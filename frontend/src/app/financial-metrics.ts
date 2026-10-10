export type Metric = { key:string; label:string; kind:'money'|'shares'|'eps'|'percent'; emphasis?:boolean; derived?:string };
export type Period = {date:string;currency:string;values:Record<string,number>};
export const INCOME_METRICS:Metric[] = [
 {key:'revenue',label:'Total Revenues',kind:'money',emphasis:true},
 {key:'revenueGrowth',label:'Total Revenues %Chg',kind:'percent',derived:'Year-over-year revenue growth; requires consecutive fiscal years in the same currency.'},
 {key:'costOfSales',label:'Cost of Sales',kind:'money'},
 {key:'grossProfit',label:'Gross Profit',kind:'money',emphasis:true},
 {key:'grossMargin',label:'Gross Profit Margin',kind:'percent',derived:'Gross profit / revenue × 100.'},
 {key:'sga',label:'Selling, General & Administrative Expenses',kind:'money'},
 {key:'researchDevelopment',label:'Research & Development Expenses',kind:'money'},
 {key:'otherOperatingExpenses',label:'Other Operating Expenses',kind:'money'},
 {key:'operatingIncome',label:'Operating Profit',kind:'money',emphasis:true},
 {key:'operatingMargin',label:'Operating Margin',kind:'percent',derived:'Operating profit / revenue × 100.'},
 {key:'nonOperatingIncome',label:'Non-Operating Income',kind:'money',derived:'Income before tax minus operating profit.'},
 {key:'totalNonOperatingIncome',label:'Total Non-Operating Income',kind:'money',derived:'Income before tax minus operating profit.'},
 {key:'pretaxIncome',label:'Income Before Provision for Income Taxes',kind:'money',emphasis:true},
 {key:'taxProvision',label:'Provision for Income Taxes',kind:'money'},
 {key:'netIncome',label:'Consolidated Net Income',kind:'money',emphasis:true},
 {key:'netIncomeCommon',label:'Net Income Attributable to Common Shareholders',kind:'money'},
 {key:'basicEps',label:'Basic EPS',kind:'eps'},
 {key:'dilutedEps',label:'Diluted EPS',kind:'eps'},
 {key:'basicShares',label:'Basic Weighted Average Shares Outstanding',kind:'shares'},
 {key:'dilutedShares',label:'Diluted Weighted Average Shares Outstanding',kind:'shares'},
 {key:'sharesOutstanding',label:'Shares Outstanding',kind:'shares'},
 {key:'ebitda',label:'EBITDA',kind:'money',emphasis:true},
 {key:'effectiveTaxRate',label:'Effective Tax Rate',kind:'percent',derived:'Income tax provision / income before tax × 100.'}
];
export function metricValue(metric:Metric, period:Period, periods:Period[]):number|null {
 const v=period.values;
 if(v[metric.key]!=null && Number.isFinite(v[metric.key])) return v[metric.key];
 const ratio=(a:number|undefined,b:number|undefined)=> a!=null&&b!=null&&b!==0 ? a/b*100 : null;
 switch(metric.key){
  case 'grossMargin': return ratio(v['grossProfit'],v['revenue']);
  case 'operatingMargin': return ratio(v['operatingIncome'],v['revenue']);
  case 'effectiveTaxRate': return ratio(v['taxProvision'],v['pretaxIncome']);
  case 'nonOperatingIncome':case 'totalNonOperatingIncome':return v['pretaxIncome']!=null&&v['operatingIncome']!=null ? v['pretaxIncome']-v['operatingIncome']:null;
  case 'revenueGrowth':{
   const prev=periods.filter(p=>p.date<period.date).sort((a,b)=>a.date.localeCompare(b.date)).at(-1);
   if(!prev||prev.currency!==period.currency)return null;
   const days=(Date.parse(period.date)-Date.parse(prev.date))/86400000;
   if(days<330||days>400||!prev.values['revenue']||prev.values['revenue']<0||v['revenue']==null)return null;
   return (v['revenue']/prev.values['revenue']-1)*100;
  }
  default:return null;
 }
}
export function formatMetric(metric:Metric,period:Period,periods:Period[],commonSize:boolean):string {
 let value=metricValue(metric,period,periods);
 if(value==null||!Number.isFinite(value))return '—';
 const asPercent=metric.kind==='percent'||(commonSize&&metric.kind==='money');
 if(commonSize&&metric.kind==='money'){
  if(!period.values['revenue'])return '—';
  value=value/period.values['revenue']*100;
 }
 if(asPercent)return new Intl.NumberFormat('en-US',{maximumFractionDigits:1}).format(value)+'%';
 return new Intl.NumberFormat('en-US',{minimumFractionDigits:metric.kind==='eps'?2:0,maximumFractionDigits:metric.kind==='eps'?2:0}).format(metric.kind==='eps'?value:value/1e6);
}
