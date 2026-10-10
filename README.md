# akhil.ai

An investment research dashboard with company search, charts, financial statements, sector screening, saved watchlists, and CSV export.

## Run locally

```sh
python3 -m http.server 4173 --directory dist
```

Open http://localhost:4173. No build or dependencies are required.

## Files

- `dist/index.html`: dashboard markup
- `dist/style.css`: responsive styling and dark theme
- `dist/app.js`: demo data and browser interactions
- `.openai/hosting.json`: private Sites deployment configuration

All financial figures and charts are illustrative demo data. Research briefs are rule-based, not AI-generated. Watchlists and theme preferences are saved in browser local storage.
