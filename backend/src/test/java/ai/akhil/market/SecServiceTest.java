package ai.akhil.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class SecServiceTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static String row(String start,String end,String filed,String form,double value) {
        return "{"+(start==null?"":"\"start\":\""+start+"\",")+"\"end\":\""+end+"\",\"filed\":\""+filed+"\",\"form\":\""+form+"\",\"accn\":\"0000789019-25-000001\",\"val\":"+value+"}";
    }
    private static String facts() {
        return "{\"entityName\":\"Fixture Inc\",\"facts\":{\"us-gaap\":{"+
          "\"Revenues\":{\"units\":{\"USD\":["+
          row("2023-07-01","2024-06-30","2024-07-30","10-K",100)+","+
          row("2023-07-01","2024-06-30","2025-07-30","10-K/A",110)+","+
          row("2024-07-01","2024-09-30","2024-10-30","10-Q",30)+","+
          row("2024-07-01","2024-12-31","2025-01-30","10-Q",65)+","+
          row("2024-10-01","2024-12-31","2025-01-30","10-Q",35)+"]}},"+
          "\"NetIncomeLoss\":{\"units\":{\"USD\":["+row("2023-07-01","2024-06-30","2024-07-30","10-K",0)+"]}},"+
          "\"Assets\":{\"units\":{\"USD\":["+row(null,"2024-06-30","2024-07-30","10-K",500)+","+row(null,"2024-12-31","2025-01-30","10-Q",600)+"]}},"+
          "\"NetCashProvidedByUsedInOperatingActivities\":{\"units\":{\"USD\":["+row("2024-07-01","2024-12-31","2025-01-30","10-Q",90)+"]}}"+
          "}}}";
    }
    @Test void annualUsesLatestAmendmentAndRetainsZeroAndEvidence() throws Exception {
        var r=SecService.normalize("MSFT","0000789019",JSON.readTree(facts()),Instant.now(),"annual","income");
        assertThat(r.periods()).hasSize(1);
        assertThat(r.periods().get(0).values()).containsEntry("revenue",110d).containsEntry("netIncome",0d).doesNotContainKey("ebitda");
        var evidence=r.periods().get(0).evidence().get("revenue");
        assertThat(evidence.form()).isEqualTo("10-K/A");
        assertThat(evidence.url()).isEqualTo("https://www.sec.gov/Archives/edgar/data/789019/000078901925000001/0000789019-25-000001-index.html");
    }
    @Test void quarterlyNeverIncludesCumulativeYtd() throws Exception {
        var r=SecService.normalize("MSFT","0000789019",JSON.readTree(facts()),Instant.now(),"quarterly","income");
        assertThat(r.periods()).hasSize(2);
        assertThat(r.periods().get(1).values().get("revenue")).isEqualTo(35d);
        var cash=SecService.normalize("MSFT","0000789019",JSON.readTree(facts()),Instant.now(),"quarterly","cashflow");
        assertThat(cash.periods()).isEmpty();
    }
    @Test void balanceUsesInstantValuesAtRelevantPeriodEnds() throws Exception {
        var annual=SecService.normalize("MSFT","0000789019",JSON.readTree(facts()),Instant.now(),"annual","balance");
        assertThat(annual.periods()).hasSize(1);
        assertThat(annual.periods().get(0).values().get("assets")).isEqualTo(500d);
        var quarter=SecService.normalize("MSFT","0000789019",JSON.readTree(facts()),Instant.now(),"quarterly","balance");
        assertThat(quarter.periods()).hasSize(1);
        assertThat(quarter.periods().get(0).values().get("assets")).isEqualTo(600d);
    }
    @Test void unsupportedCurrencyAndFormsAreNotMislabelledUsd() throws Exception {
        var root=JSON.readTree(facts().replace("USD","EUR").replace("10-K","20-F"));
        assertThat(SecService.normalize("TEST","1",root,Instant.now(),"annual","income").periods()).isEmpty();
    }
    @Test void clientMapsTickerCachesAndReportsSecDenial() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var count=new AtomicInteger();
        server.createContext("/",e->{
            count.incrementAndGet();boolean denied=e.getRequestURI().getPath().equals("/denied");
            String body=denied?"Access denied":e.getRequestURI().getPath().equals("/tickers")?"{\"0\":{\"ticker\":\"MSFT\",\"cik_str\":789019}}":facts();
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(denied?403:200,bytes.length);try(var out=e.getResponseBody()){out.write(bytes);}
        });server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            var service=new SecService(base,base+"/tickers","test test@example.com");
            assertThat(service.results("MSFT","annual","income").periods()).hasSize(1);
            service.results("MSFT","quarterly","balance"); assertThat(count.get()).isEqualTo(2);
            assertThatThrownBy(()->service.results("UNKNOWN","annual","income")).isInstanceOf(MarketException.class).hasMessageContaining("No SEC company mapping");
            assertThatThrownBy(()->service.results("MSFT","bad","income")).isInstanceOf(MarketException.class);
            assertThatThrownBy(()->new SecService(base,base+"/denied","test").results("MSFT","annual","income")).isInstanceOf(MarketException.class).hasMessageContaining("HTTP 403");
        } finally {server.stop(0);}
    }
}
