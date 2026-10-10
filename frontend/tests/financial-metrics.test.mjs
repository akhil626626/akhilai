import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {INCOME_METRICS,formatMetric,metricValue} from '../src/app/financial-metrics.ts';
const metric=key=>INCOME_METRICS.find(m=>m.key===key);
const p={date:'2026-06-30',currency:'USD',values:{revenue:331839000000,grossProfit:225465000000,basicEps:18,basicShares:7429000000,netIncome:0}};
test('formats millions, EPS, shares, and real zeros distinctly',()=>{
 assert.equal(formatMetric(metric('revenue'),p,[p],false),'331,839');
 assert.equal(formatMetric(metric('basicEps'),p,[p],false),'18.00');
 assert.equal(formatMetric(metric('basicShares'),p,[p],false),'7,429');
 assert.equal(formatMetric(metric('netIncome'),p,[p],false),'0');
 assert.equal(formatMetric(metric('otherOperatingExpenses'),p,[p],false),'—');
});
test('common size converts monetary metrics only',()=>{
 assert.equal(formatMetric(metric('revenue'),p,[p],true),'100%');
 assert.equal(formatMetric(metric('grossProfit'),p,[p],true),'67.9%');
 assert.equal(formatMetric(metric('basicEps'),p,[p],true),'18.00');
 assert.equal(formatMetric(metric('basicShares'),p,[p],true),'7,429');
 assert.equal(formatMetric(metric('grossMargin'),p,[p],true),'67.9%');
});
test('zero denominator never returns infinity',()=>{
 const zero={...p,values:{revenue:0,grossProfit:10,taxProvision:3,pretaxIncome:0}};
 assert.equal(formatMetric(metric('grossProfit'),zero,[zero],true),'—');
 assert.equal(metricValue(metric('effectiveTaxRate'),zero,[zero]),null);
});
test('growth uses the prior fiscal year irrespective of display order',()=>{
 const prev={date:'2025-06-30',currency:'USD',values:{revenue:281724000000}};
 assert.equal(formatMetric(metric('revenueGrowth'),p,[p,prev],false),'17.8%');
 assert.equal(metricValue(metric('revenueGrowth'),p,[p]),null);
 assert.equal(metricValue(metric('revenueGrowth'),p,[{...prev,currency:'EUR'},p]),null);
 assert.equal(metricValue(metric('revenueGrowth'),p,[{...prev,date:'2024-06-30'},p]),null);
});
test('reference preserves the user supplied ten years without filling blanks',()=>{
 const data=JSON.parse(readFileSync(new URL('../../backend/src/main/resources/msft-reference.json',import.meta.url),'utf8'));
 assert.equal(data.periods.length,10);assert.equal(data.periods[0].date,'2017-06-30');
 assert.equal(data.periods.at(-1).date,'2026-06-30');
 assert.equal(INCOME_METRICS.length,23);assert.equal(data.fetchedAt,null);
 assert.equal(data.periods[0].values.otherOperatingExpenses,306000000);
 assert.equal(data.periods.at(-1).values.otherOperatingExpenses,undefined);
 assert.equal(formatMetric(metric('dilutedEps'),data.periods.at(-1),data.periods,false),'17.95');
 assert.equal(formatMetric(metric('effectiveTaxRate'),data.periods[1],data.periods,false),'54.6%');
});
test('quarterly growth compares the same quarter a year earlier',()=>{
 const periods=[{date:'2024-09-30',currency:'USD',values:{revenue:100}},{date:'2025-06-30',currency:'USD',values:{revenue:120}},{date:'2025-09-30',currency:'USD',values:{revenue:150}}];
 assert.equal(metricValue(metric('revenueGrowth'),periods[2],periods),50);
});
test('balance common size uses assets as its denominator',()=>{
 const p={date:'2025-06-30',currency:'USD',values:{assets:100,cash:25}};
 assert.equal(formatMetric({key:'cash',kind:'money'},p,[p],true,'assets'),'25%');
});
test('free cash flow requires matching filing contexts',()=>{
 const p={date:'2025-06-30',currency:'USD',values:{operatingCashFlow:100,capitalExpenditure:30},evidence:{operatingCashFlow:{start:'2024-07-01',accession:'a'},capitalExpenditure:{start:'2025-01-01',accession:'a'}}};
 assert.equal(metricValue({key:'freeCashFlow'},p,[p]),null);
 p.evidence.capitalExpenditure.start='2024-07-01';
 assert.equal(metricValue({key:'freeCashFlow'},p,[p]),70);
});
