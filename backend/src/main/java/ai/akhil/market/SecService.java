package ai.akhil.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Entity-wide, standard US-GAAP facts only. Missing facts are never synthesized. */
@Service
public class SecService {
    public record Evidence(String start, String filed, String form, String accession, String concept, String url) {}
    public record Period(String date, String currency, Map<String, Double> values, Map<String, Evidence> evidence) {}
    public record Filing(String date, String form, String accession, String url) {}
    public record Results(String symbol, String name, String cik, String source, String sourceUrl, Instant fetchedAt,
                          String frequency, String statement, String coverage, List<Period> periods, List<Filing> filings) {}
    private record Cached(JsonNode body, Instant time) {}
    private record Candidate(double value, String unit, String end, Evidence evidence, int priority) {}
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String dataUrl, tickerUrl, userAgent;
    private final Map<String,Cached> cache = new LinkedHashMap<>();
    private Instant nextRequest = Instant.EPOCH;
    public SecService(@Value("${sec.data-url:https://data.sec.gov}") String dataUrl,
                      @Value("${sec.ticker-url:https://www.sec.gov/files/company_tickers.json}") String tickerUrl,
                      @Value("${sec.user-agent:akhil.ai research github.com/akhil626626/akhilai}") String userAgent) {
        this.dataUrl=dataUrl; this.tickerUrl=tickerUrl; this.userAgent=userAgent;
    }
    private synchronized Cached fetch(String url) {
        var existing=cache.get(url);
        if(existing!=null && existing.time().plusSeconds(3600).isAfter(Instant.now())) return existing;
        try {
            long pause=Duration.between(Instant.now(),nextRequest).toMillis();
            if(pause>0) Thread.sleep(pause);
            nextRequest=Instant.now().plusMillis(250); // At most four requests/second, shared by all users.
            var response=http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25))
                    .header("User-Agent",userAgent).header("Accept","application/json").GET().build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()==403) throw new MarketException(HttpStatus.BAD_GATEWAY,"SEC_ACCESS_DENIED",
                    "SEC denied access from this server (HTTP 403). Configure SEC_USER_AGENT with your app name and contact email and use a server permitted by SEC. No filing values have been substituted.");
            if(response.statusCode()==429) throw new MarketException(HttpStatus.TOO_MANY_REQUESTS,"SEC_RATE_LIMITED","SEC request limit reached. Please retry later.");
            if(response.statusCode()==404) throw new MarketException(HttpStatus.NOT_FOUND,"SEC_NO_DATA","SEC has no company facts for this company.");
            if(response.statusCode()!=200) throw new java.io.IOException("SEC response "+response.statusCode());
            JsonNode body=mapper.readTree(response.body());
            if(body==null || !body.isObject()) throw new java.io.IOException("Invalid SEC JSON");
            Cached result=new Cached(body,Instant.now());
            if(cache.size()>=64) cache.remove(cache.keySet().iterator().next());
            cache.put(url,result); return result;
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt(); throw new MarketException(HttpStatus.BAD_GATEWAY,"SEC_UNAVAILABLE","SEC request was interrupted. Please retry.");
        } catch(java.io.IOException e) {
            throw new MarketException(HttpStatus.BAD_GATEWAY,"SEC_UNAVAILABLE","SEC filings could not be loaded. Please retry later.");
        }
    }
    public Results results(String symbol, String frequency, String statement) {
        validate(frequency,statement);
        String cik=null;
        for(JsonNode company:fetch(tickerUrl).body()) {
            if(company.path("ticker").asText().replace('.','-').equalsIgnoreCase(symbol.replace('.','-'))) {
                cik=String.format("%010d",company.path("cik_str").asLong()); break;
            }
        }
        if(cik==null) throw new MarketException(HttpStatus.NOT_FOUND,"SEC_COMPANY_NOT_FOUND","No SEC company mapping found for this symbol. Funds and non-US issuers may not have supported US-GAAP statements.");
        Cached facts=fetch(dataUrl+"/api/xbrl/companyfacts/CIK"+cik+".json");
        return normalize(symbol,cik,facts.body(),facts.time(),frequency,statement);
    }
    static void validate(String frequency,String statement) {
        if(!Set.of("annual","quarterly").contains(frequency)||!Set.of("income","balance","cashflow").contains(statement))
            throw new MarketException(HttpStatus.BAD_REQUEST,"INVALID_STATEMENT","Choose a supported statement and reporting period.");
    }
    static Map<String,List<String>> concepts(String statement) {
        Map<String,List<String>> m=new LinkedHashMap<>();
        if(statement.equals("income")) {
            m.put("revenue",List.of("RevenueFromContractWithCustomerExcludingAssessedTax","Revenues","SalesRevenueNet"));
            m.put("costOfSales",List.of("CostOfRevenue","CostOfGoodsAndServicesSold","CostOfGoodsSold"));
            m.put("grossProfit",List.of("GrossProfit"));
            m.put("sga",List.of("SellingGeneralAndAdministrativeExpense"));
            m.put("researchDevelopment",List.of("ResearchAndDevelopmentExpense"));
            m.put("operatingIncome",List.of("OperatingIncomeLoss"));
            m.put("pretaxIncome",List.of("IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest","IncomeLossFromContinuingOperationsBeforeIncomeTaxesMinorityInterestAndIncomeLossFromEquityMethodInvestments"));
            m.put("taxProvision",List.of("IncomeTaxExpenseBenefit"));
            m.put("netIncome",List.of("NetIncomeLoss","ProfitLoss"));
            m.put("netIncomeCommon",List.of("NetIncomeLossAvailableToCommonStockholdersBasic"));
            m.put("basicEps",List.of("EarningsPerShareBasic"));
            m.put("dilutedEps",List.of("EarningsPerShareDiluted"));
            m.put("basicShares",List.of("WeightedAverageNumberOfSharesOutstandingBasic"));
            m.put("dilutedShares",List.of("WeightedAverageNumberOfDilutedSharesOutstanding"));
        } else if(statement.equals("balance")) {
            m.put("cash",List.of("CashAndCashEquivalentsAtCarryingValue"));
            m.put("shortTermInvestments",List.of("ShortTermInvestments"));
            m.put("receivables",List.of("AccountsReceivableNetCurrent"));
            m.put("inventory",List.of("InventoryNet"));
            m.put("currentAssets",List.of("AssetsCurrent"));
            m.put("propertyPlantEquipment",List.of("PropertyPlantAndEquipmentNet"));
            m.put("goodwill",List.of("Goodwill"));
            m.put("assets",List.of("Assets"));
            m.put("accountsPayable",List.of("AccountsPayableCurrent"));
            m.put("currentLiabilities",List.of("LiabilitiesCurrent"));
            m.put("longTermDebt",List.of("LongTermDebtNoncurrent"));
            m.put("liabilities",List.of("Liabilities"));
            m.put("equity",List.of("StockholdersEquity","StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest"));
            m.put("retainedEarnings",List.of("RetainedEarningsAccumulatedDeficit"));
        } else {
            m.put("operatingCashFlow",List.of("NetCashProvidedByUsedInOperatingActivities"));
            m.put("capitalExpenditure",List.of("PaymentsToAcquirePropertyPlantAndEquipment"));
            m.put("investingCashFlow",List.of("NetCashProvidedByUsedInInvestingActivities"));
            m.put("financingCashFlow",List.of("NetCashProvidedByUsedInFinancingActivities"));
            m.put("dividendsPaid",List.of("PaymentsOfDividends","PaymentsOfDividendsCommonStock"));
            m.put("shareRepurchases",List.of("PaymentsForRepurchaseOfCommonStock"));
            m.put("stockCompensation",List.of("ShareBasedCompensation"));
            m.put("depreciation",List.of("DepreciationDepletionAndAmortization"));
        }
        return m;
    }
    static Results normalize(String symbol,String cik,JsonNode root,Instant fetched,String frequency,String statement) {
        validate(frequency,statement);
        JsonNode facts=root.path("facts").path("us-gaap");
        // Annual/quarter endpoints come from duration facts, not the filing's fiscal-period label:
        // a Q3 filing can also include Q1/Q2 comparatives and year-to-date amounts.
        Set<String> ends=new HashSet<>();
        List.of("RevenueFromContractWithCustomerExcludingAssessedTax","Revenues","SalesRevenueNet","NetIncomeLoss","NetCashProvidedByUsedInOperatingActivities").forEach(tag -> facts.path(tag).path("units").elements().forEachRemaining(rows -> {
            for(JsonNode row:rows) if(eligible(row,frequency)) ends.add(row.path("end").asText());
        }));
        Map<String,Map<String,Candidate>> chosen=new TreeMap<>();
        concepts(statement).forEach((key,tags)->{
            for(int priority=0;priority<tags.size();priority++) {
                String tag=tags.get(priority);
                var units=facts.path(tag).path("units").fields();
                while(units.hasNext()) {
                    var unit=units.next();
                    String expected=key.endsWith("Eps")?"USD/shares":key.endsWith("Shares")?"shares":"USD";
                    if(!unit.getKey().equals(expected)) continue;
                    for(JsonNode row:unit.getValue()) {
                        String end=row.path("end").asText();
                        if(!validForm(row) || !row.path("val").isNumber() || !Double.isFinite(row.path("val").asDouble())) continue;
                        if(statement.equals("balance")) { if(row.has("start") || !ends.contains(end)) continue; }
                        else if(!eligible(row,frequency)) continue;
                        String accession=row.path("accn").asText();
                        String url=filingUrl(cik,accession);
                        if(url==null) continue;
                        Evidence ev=new Evidence(row.path("start").asText(null),row.path("filed").asText(),row.path("form").asText(),accession,tag,url);
                        Candidate candidate=new Candidate(row.path("val").asDouble(),unit.getKey(),end,ev,priority);
                        String periodKey=end;
                        Map<String,Candidate> values=chosen.computeIfAbsent(periodKey,k->new LinkedHashMap<>());
                        Candidate previous=values.get(key);
                        if(previous==null || ev.filed().compareTo(previous.evidence().filed())>0 ||
                                (ev.filed().equals(previous.evidence().filed()) && priority<previous.priority())) values.put(key,candidate);
                    }
                }
            }
        });
        List<Period> periods=new ArrayList<>(); Map<String,Filing> filings=new TreeMap<>();
        chosen.values().forEach(candidates->{
            Map<String,Double> values=new LinkedHashMap<>(); Map<String,Evidence> evidence=new LinkedHashMap<>();
            candidates.forEach((key,c)->{values.put(key,c.value());evidence.put(key,c.evidence());
                Evidence e=c.evidence();filings.put(e.filed()+e.accession(),new Filing(e.filed(),e.form(),e.accession(),e.url()));});
            if(!values.isEmpty()) periods.add(new Period(candidates.values().iterator().next().end(),"USD",values,evidence));
        });
        var filingList=new ArrayList<>(filings.values());Collections.reverse(filingList);
        String coverage="SEC standard US-GAAP, entity-wide USD facts. Latest filed value for each metric; dates are fiscal period ends. "
                +(frequency.equals("quarterly")?"Only directly reported 75–105 day periods; Q4 and quarterly cash flows may be absent. " :"Annual durations of 330–400 days. ")
                +"Balance sheets are point-in-time. Custom tags, segments, guidance, estimates and earnings calendars are not included. Missing facts remain blank.";
        return new Results(symbol,root.path("entityName").asText(symbol),cik,"SEC EDGAR company facts",
                "https://www.sec.gov/edgar/browse/?CIK="+cik,fetched,frequency,statement,coverage,periods,filingList);
    }
    private static boolean validForm(JsonNode row) {return Set.of("10-K","10-Q","10-K/A","10-Q/A").contains(row.path("form").asText());}
    private static boolean eligible(JsonNode row,String frequency) {
        if(!validForm(row)||!row.has("start")) return false;
        if(frequency.equals("annual") && !row.path("form").asText().startsWith("10-K"))return false;
        try {
            long days=ChronoUnit.DAYS.between(LocalDate.parse(row.path("start").asText()),LocalDate.parse(row.path("end").asText()))+1;
            return frequency.equals("annual")?days>=330&&days<=400:days>=75&&days<=105;
        } catch(Exception e) {return false;}
    }
    private static String filingUrl(String cik,String accession) {
        if(!cik.matches("[0-9]{1,10}")||!accession.matches("[0-9]{10}-[0-9]{2}-[0-9]{6}")) return null;
        return "https://www.sec.gov/Archives/edgar/data/"+Long.parseLong(cik)+"/"+accession.replace("-","")+"/"+accession+"-index.html";
    }
}
