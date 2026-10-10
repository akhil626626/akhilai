package ai.akhil.market;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
class MarketApiTest {
 static final HttpServer provider;
 static final AtomicInteger hits=new AtomicInteger();
 static {
  try {
   provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   provider.createContext("/",exchange->{
    hits.incrementAndGet();String path=exchange.getRequestURI().getPath();int status=200;String body;
    if(path.endsWith("/LIMIT")){status=429;body="{}";}
    else if(path.endsWith("/BROKEN")){body="<html>Unavailable</html>";}
    else if(path.endsWith("/MISSING")){body="{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\"}}}";}
    else if(path.contains("fundamentals-timeseries")){body="""
     {"timeseries":{"result":[{"annualTotalRevenue":[
     {"asOfDate":"2025-09-30","periodType":"12M","currencyCode":"USD","reportedValue":{"raw":200}},
     {"asOfDate":"2024-09-30","periodType":"12M","currencyCode":"USD","reportedValue":{"raw":100}},
     {"asOfDate":"2025-12-31","periodType":"3M","currencyCode":"USD","reportedValue":{"raw":999}}]},
     {"annualNetIncome":[{"asOfDate":"2024-09-30","periodType":"12M","currencyCode":"USD","reportedValue":{"raw":0}},
     {"asOfDate":"2025-09-30","periodType":"12M","currencyCode":"USD","reportedValue":{"raw":null}}]}],"error":null}}
     """;}
    else if(path.contains("/search")){body="""
     {"quotes":[{"symbol":"AAPL","longname":"Apple Inc.","quoteType":"EQUITY","exchDisp":"NASDAQ"},{"symbol":"NEWS","quoteType":"NONE"}]}
     """;}
    else{body="""
     {"chart":{"result":[{"meta":{"longName":"Test Company","currency":"USD","exchangeTimezoneName":"America/New_York","fiftyTwoWeekHigh":120,"fiftyTwoWeekLow":80},"timestamp":[1727884800,1727971200,1728057600],"indicators":{"quote":[{"close":[100,null,110],"volume":[1000,null,1200]}]}}],"error":null}}
     """;}
    byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}
   });provider.start();
  }catch(IOException e){throw new ExceptionInInitializerError(e);}
 }
 @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("market.base-url",()->"http://127.0.0.1:"+provider.getAddress().getPort());}
 @AfterAll static void stop(){provider.stop(0);}
 @Autowired MockMvc mvc;
 @Test void pricesSkipNullBarsAndCalculateChange() throws Exception {
  mvc.perform(get("/api/stocks/AAPL/history").param("range","1y")).andExpect(status().isOk()).andExpect(jsonPath("$.quote.price").value(110)).andExpect(jsonPath("$.quote.changePercent").value(org.hamcrest.Matchers.closeTo(10,0.00001))).andExpect(jsonPath("$.bars.length()").value(2)).andExpect(jsonPath("$.quote.source").value("Yahoo Finance")).andExpect(jsonPath("$.quote.fetchedAt").exists());
 }
 @Test void financialsAlignByDateAndKeepMissingDifferentFromZero() throws Exception {
  mvc.perform(get("/api/stocks/AAPL/financials")).andExpect(status().isOk()).andExpect(jsonPath("$.periods.length()").value(2)).andExpect(jsonPath("$.periods[0].date").value("2024-09-30")).andExpect(jsonPath("$.periods[0].values.revenue").value(100)).andExpect(jsonPath("$.periods[0].values.netIncome").value(0)).andExpect(jsonPath("$.periods[1].values.netIncome").doesNotExist());
 }
 @Test void invalidInputsNeverCallProvider() throws Exception {
  int before=hits.get();mvc.perform(get("/api/stocks/BAD!SYMBOL/quote")).andExpect(status().isBadRequest());mvc.perform(get("/api/stocks/AAPL/history").param("range","unknown")).andExpect(status().isBadRequest());assertThat(hits.get()).isEqualTo(before);
 }
 @Test void providerFailuresProduceErrorsInsteadOfFakePrices() throws Exception {
  mvc.perform(get("/api/stocks/BROKEN/quote")).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE")).andExpect(jsonPath("$.price").doesNotExist());mvc.perform(get("/api/stocks/LIMIT/quote")).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));mvc.perform(get("/api/stocks/MISSING/quote")).andExpect(status().isNotFound());
 }
 @Test void cacheIsSharedBetweenHistoryAndQuote() throws Exception {
  int before=hits.get();mvc.perform(get("/api/stocks/CACHE/history")).andExpect(status().isOk());mvc.perform(get("/api/stocks/CACHE/quote")).andExpect(status().isOk());assertThat(hits.get()-before).isEqualTo(1);
 }
 @Test void searchFiltersUnsupportedResults() throws Exception {
  mvc.perform(get("/api/search").param("q","Apple & Co")).andExpect(status().isOk()).andExpect(jsonPath("$.companies.length()").value(1)).andExpect(jsonPath("$.companies[0].symbol").value("AAPL"));
 }
 @Test void referenceStatementHasTenYearsAndExplicitAttribution() throws Exception {
  mvc.perform(get("/api/stocks/MSFT/financials").param("source","reference")).andExpect(status().isOk())
    .andExpect(jsonPath("$.periods.length()").value(10))
    .andExpect(jsonPath("$.source").value("User-provided Microsoft statement (unverified)"))
    .andExpect(jsonPath("$.periods[9].values.revenue").value(331839000000.0))
    .andExpect(jsonPath("$.periods[9].values.dilutedEps").value(17.95))
    .andExpect(jsonPath("$.periods[9].values.otherOperatingExpenses").doesNotExist());
  mvc.perform(get("/api/stocks/AAPL/financials").param("source","reference")).andExpect(status().isBadRequest());
  mvc.perform(get("/api/stocks/MSFT/financials").param("source","unknown")).andExpect(status().isBadRequest());
 }
}
