package ai.akhil.market;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class MarketModels {
    private MarketModels() {}
    public record Company(String symbol, String name, String exchange, String type) {}
    public record Bar(String date, double close, Long volume) {}
    public record Quote(String symbol, String name, String exchange, String currency,
                        double price, Double previousClose, Double changePercent, String asOf,
                        Double fiftyTwoWeekHigh, Double fiftyTwoWeekLow, Long volume,
                        String source, Instant fetchedAt) {}
    public record History(Quote quote, String range, List<Bar> bars) {}
    public record FinancialPeriod(String date, String currency, Map<String, Double> values) {}
    public record Financials(String symbol, String source, String sourceUrl, Instant fetchedAt,
                             List<FinancialPeriod> periods) {}
    public record SearchResult(List<Company> companies, String source, Instant fetchedAt) {}
    public record ApiError(String code, String message) {}
}
