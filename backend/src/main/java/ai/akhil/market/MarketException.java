package ai.akhil.market;

import org.springframework.http.HttpStatus;

public class MarketException extends RuntimeException {
    final HttpStatus status;
    final String code;
    public MarketException(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    static MarketException unavailable() {
        return new MarketException(HttpStatus.BAD_GATEWAY, "PROVIDER_UNAVAILABLE",
                "Yahoo Finance is unavailable. Please retry in a few minutes. No sample data has been substituted.");
    }
}
