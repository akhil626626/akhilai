package ai.akhil.market;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import static ai.akhil.market.MarketModels.*;

@Service
public class MarketService {
    private final YahooClient client;
    private static final Map<String, String> METRICS = Map.of(
            "annualTotalRevenue", "revenue", "annualGrossProfit", "grossProfit",
            "annualOperatingIncome", "operatingIncome", "annualNetIncome", "netIncome",
            "annualFreeCashFlow", "freeCashFlow");
    public MarketService(YahooClient client) { this.client = client; }
    public String symbol(String input) {
        String symbol = input.trim().toUpperCase(Locale.ROOT);
        if (!symbol.matches("\\^?[A-Z0-9][A-Z0-9.\\-]{0,14}"))
            throw new MarketException(HttpStatus.BAD_REQUEST, "INVALID_SYMBOL", "Enter a valid stock symbol, such as AAPL or BRK-B.");
        return symbol;
    }
    static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
    static Double number(JsonNode value) {
        if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble())) return null;
        return value.asDouble();
    }
    static Long integer(JsonNode value) { return value != null && value.isIntegralNumber() ? value.asLong() : null; }

    public SearchResult search(String query) {
        if (query == null || query.isBlank()) return new SearchResult(List.of(), "Yahoo Finance", Instant.now());
        if (query.length() > 80) throw new MarketException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "Search is limited to 80 characters.");
        var payload = client.get("/v1/finance/search?q=" + encode(query.trim()) + "&quotesCount=10&newsCount=0", false);
        List<Company> companies = new ArrayList<>();
        for (var node : payload.json().path("quotes")) {
            String type = node.path("quoteType").asText();
            if (!Set.of("EQUITY", "ETF", "INDEX").contains(type)) continue;
            String symbol = node.path("symbol").asText();
            if (!symbol.matches("\\^?[A-Z0-9][A-Z0-9.\\-]{0,14}")) continue;
            companies.add(new Company(symbol, node.path("longname").asText(node.path("shortname").asText(symbol)),
                    node.path("exchDisp").asText(node.path("exchange").asText()), type));
        }
        return new SearchResult(companies, "Yahoo Finance", payload.fetchedAt());
    }

    public History history(String input, String range) {
        String symbol = symbol(input);
        if (!Set.of("1mo", "3mo", "6mo", "1y", "5y").contains(range))
            throw new MarketException(HttpStatus.BAD_REQUEST, "INVALID_RANGE", "Supported ranges: 1mo, 3mo, 6mo, 1y, 5y.");
        // Use one year for short ranges so the quote always has a previous daily bar.
        String providerRange = range.equals("5y") ? "5y" : "1y";
        var payload = client.get("/v8/finance/chart/" + encode(symbol) + "?interval=1d&range=" + providerRange, false);
        JsonNode result = payload.json().path("chart").path("result").path(0);
        JsonNode meta = result.path("meta"), timestamps = result.path("timestamp");
        JsonNode quoteData = result.path("indicators").path("quote").path(0);
        ZoneId timezone;
        try { timezone = ZoneId.of(meta.path("exchangeTimezoneName").asText("UTC")); }
        catch (DateTimeException e) { timezone = ZoneOffset.UTC; }
        List<Bar> all = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            Double close = number(quoteData.path("close").path(i));
            if (close == null || close <= 0 || !timestamps.path(i).isNumber()) continue;
            String date = Instant.ofEpochSecond(timestamps.path(i).asLong()).atZone(timezone).toLocalDate().toString();
            all.add(new Bar(date, close, integer(quoteData.path("volume").path(i))));
        }
        if (all.isEmpty()) throw new MarketException(HttpStatus.NOT_FOUND, "NO_DATA", "No price history is available for " + symbol + ".");
        all.sort(Comparator.comparing(Bar::date));
        Bar last = all.get(all.size() - 1);
        Double previous = all.size() > 1 ? all.get(all.size() - 2).close() : null;
        Double change = previous == null || previous == 0 ? null : (last.close() / previous - 1) * 100;
        Quote quote = new Quote(symbol, meta.path("longName").asText(meta.path("shortName").asText(symbol)),
                meta.path("fullExchangeName").asText(meta.path("exchangeName").asText("—")),
                meta.path("currency").asText(""), last.close(), previous, change, last.date(),
                number(meta.path("fiftyTwoWeekHigh")), number(meta.path("fiftyTwoWeekLow")), last.volume(),
                "Yahoo Finance", payload.fetchedAt());
        LocalDate end = LocalDate.parse(last.date());
        LocalDate cutoff = switch (range) {
            case "1mo" -> end.minusMonths(1); case "3mo" -> end.minusMonths(3);
            case "6mo" -> end.minusMonths(6); case "5y" -> end.minusYears(5); default -> end.minusYears(1);
        };
        return new History(quote, range, all.stream().filter(b -> !LocalDate.parse(b.date()).isBefore(cutoff)).toList());
    }

    public Financials financials(String input) {
        String symbol = symbol(input);
        // Day-based end time keeps the six-hour cache effective.
        long end = LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long start = LocalDate.now(ZoneOffset.UTC).minusYears(6).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        String types = String.join(",", new TreeSet<>(METRICS.keySet()));
        var payload = client.get("/ws/fundamentals-timeseries/v1/finance/timeseries/" + encode(symbol)
                + "?type=" + types + "&period1=" + start + "&period2=" + end, true);
        Map<String, Map<String, Double>> byDate = new TreeMap<>();
        Map<String, String> currencies = new HashMap<>();
        for (var series : payload.json().path("timeseries").path("result")) {
            for (var metric : METRICS.entrySet()) {
                for (var row : series.path(metric.getKey())) {
                    String date = row.path("asOfDate").asText();
                    Double value = number(row.path("reportedValue").path("raw"));
                    if (value == null || !row.path("periodType").asText().equals("12M")) continue;
                    try { LocalDate.parse(date); } catch (DateTimeException e) { continue; }
                    String currency = row.path("currencyCode").asText("");
                    if (currencies.containsKey(date) && !currencies.get(date).equals(currency)) continue;
                    currencies.put(date, currency);
                    byDate.computeIfAbsent(date, ignored -> new LinkedHashMap<>()).put(metric.getValue(), value);
                }
            }
        }
        if (byDate.isEmpty()) throw new MarketException(HttpStatus.NOT_FOUND, "NO_FINANCIALS", "Annual financials are unavailable for " + symbol + ".");
        List<FinancialPeriod> periods = byDate.entrySet().stream()
                .map(e -> new FinancialPeriod(e.getKey(), currencies.get(e.getKey()), e.getValue())).toList();
        return new Financials(symbol, "Yahoo Finance", "https://finance.yahoo.com/quote/" + encode(symbol) + "/financials/",
                payload.fetchedAt(), periods);
    }
}
