# akhil.ai

Investment research app built with **Angular 21**, **Java 17**, and **Spring Boot 3.5**. The browser calls the Java API, which fetches real Yahoo Finance prices, history, search results, and annual financials. Connected mode uses provider data. The financials page also offers an explicitly labeled, user-provided Microsoft reference statement for 2017–2026; it is not independently verified.

## Run the full application

Requires Java 17+, Maven 3.9+, and npm. Node 22.12+ is recommended for installation; a project-local Node 22.22 runtime is included for Angular commands so the system Node does not need to be replaced.

```sh
bash scripts/build.sh
bash scripts/start.sh
```

Open **http://localhost:8080**. The packaged Java application serves both the compiled Angular frontend and `/api`, so no CORS configuration or separate frontend server is needed.

Or use Docker:

```sh
docker compose up --build
```

The container runs as a non-root user, and Compose exposes it only on localhost. Docker packaging is provided; local Angular and Maven builds are the primary validation path.

## Development

Start the backend:

```sh
mvn -f backend/pom.xml spring-boot:run
```

In another terminal:

```sh
npm --prefix frontend ci
npm --prefix frontend start
```

Open http://localhost:4200. Angular proxies `/api` to the Java backend on port 8080.

## Features

- Provider-backed company and ticker search, including equities, ETFs, and indices.
- Actual daily close charts, selectable ranges, dates, volume, and daily price changes.
- S&P 500, NASDAQ Composite, Dow Jones, and VIX market cards.
- A detailed 23-row income statement with revenue, costs, SG&A, R&D, operating profit, pretax income, tax, net income, EPS, shares, EBITDA, and calculated margins. Provider availability varies by metric.
- Search and select metrics, filter fiscal dates, reverse column order, and switch monetary rows to common-size percentages of revenue.
- Amounts and shares display in millions; EPS stays per share and ratios stay percentages. CSV export follows the selected dates, metrics, order, and units.
- Annual net margin calculated from reported annual revenue and net income.
- Browser-saved watchlists (up to 20 symbols), gain/loss filters, sorting, CSV export, and dark mode.
- Explicit loading, missing-data, rate-limit, and provider-failure states. Missing values remain missing, not zero.

## Data connection

No API key is required. `YahooClient` calls Yahoo Finance's **unofficial, public endpoints** on the server. This is suitable for personal research and development; endpoint stability and availability are not guaranteed. This is not an official licensed market-data integration. Review the provider's terms and use a licensed provider for commercial redistribution.

Prices are the latest available **daily bars**, which can include an in-progress trading session; they are not a streaming quote feed. The app displays the provider's market date, currency, retrieval time, and source. Daily change is computed from the last two non-null daily closes. Chart prices are the provider's close series; no additional dividend adjustment is applied. Financials are annual reported periods, not TTM. Index/ETF financial statements may be unavailable.

Successful prices/search responses are cached for 5 minutes; financials for 6 hours. The bounded in-memory cache holds up to 256 requests and resets on restart. Provider errors never fall back to demo data. Watchlists are browser-local, with migration support for the earlier prototype's stored list.

### API

| Endpoint | Description |
| --- | --- |
| `GET /api/health` | Java API availability (does not claim provider availability) |
| `GET /api/search?q=Apple` | Provider-backed symbol search |
| `GET /api/stocks/AAPL/quote` | Latest daily bar with previous close, date, currency and source |
| `GET /api/stocks/AAPL/history?range=1y` | History; ranges: `1mo`, `3mo`, `6mo`, `1y`, `5y` |
| `GET /api/stocks/AAPL/financials` | Annual financial metrics by fiscal year-end (Yahoo) |

`400` means invalid input, `404` means unavailable data, `429` means provider rate limiting, and `502` means provider failure. No secrets are sent to or stored in the browser.

### Server configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `8080` | Java HTTP port |
| `SERVER_ADDRESS` | `127.0.0.1` | Bind address; Docker sets `0.0.0.0` |
| `MARKET_CACHE_SECONDS` | `300` | Price/search cache TTL |
| `MARKET_BASE_URL` | `https://query1.finance.yahoo.com` | Provider base URL; used by tests to inject a local mock server |

The free provider currently returns roughly four annual periods even with a longer requested range. The app never fills missing years. `GET /api/stocks/MSFT/financials?source=reference` returns the ten-year statement pasted by the user, with separate attribution and no invented retrieval timestamp. Revenue growth and margins use supplied percentages in reference mode; in connected mode, they are calculated from available reported values. Non-operating income is calculated as pretax minus operating income when no direct value is present. Missing-year or cross-currency revenue growth is suppressed. The provider does not establish a fixed 15-minute quote delay, so the UI retains its accurate daily-bar/as-of labeling.

## Tests

```sh
mvn -f backend/pom.xml test
npm --prefix frontend run build
npm --prefix frontend test
```

The backend integration tests exercise the real HTTP client against a local mock provider: null daily bars, percent-change calculation, chronological financial alignment, zero versus missing values, quarterly exclusion, invalid input, search filtering, caching, rate limiting, malformed responses, and provider errors. They do not call external services.

## Deployment

The Dockerfile builds Angular, packages its files inside Spring Boot, and starts one Java service on port 8080. Deploy this container to a JVM/container host and put authentication/TLS in front of it if needed. The app currently has no multi-user accounts or server-side portfolio storage.

The earlier ChatGPT Sites URL still serves the previous static prototype. Sites uses a JavaScript Worker runtime and cannot run this Java backend. `.openai/hosting.json` is retained only as the earlier Site's identity; it is not the deployment configuration for this version. The Angular/Java version must be deployed to a Java-capable host.

## Structure

- `frontend/` — standalone Angular components, typed HTTP service, charts and styles.
- `backend/` — Spring Boot REST API, provider client, normalization, cache and integration tests.
- `scripts/` — local build and start commands.
- `Dockerfile`, `compose.yaml` — full application container packaging.

The original static prototype is preserved in Git history.
