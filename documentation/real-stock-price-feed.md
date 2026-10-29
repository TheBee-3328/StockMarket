# Optional real-stock price feed (NeoForge / Minecraft 1.21.1)

The experimental Twelve Data importer runs on the master server only. It uses the provider's `/price` endpoint, caches the last applied quote in memory, performs HTTPS calls on a daemon worker, and schedules market mutations back onto the Minecraft server thread. Ordinary item markets are not modified unless a ticker mapping is explicitly configured.

## Setup

1. Start the server once. The importer creates `world/data/StockMarket/real-stocks.json`.
2. Obtain a Twelve Data API key and put it in the `apiKey` property.
3. Configure ticker mappings. Each mapping has `ticker`, `itemId`, and optional positive `scale`.
4. Restart the server. The provider is queried at startup and then on the configured interval.

Example mapping format:

```json
{
  "enabled": true,
  "apiKey": "YOUR_TWELVE_DATA_API_KEY",
  "refreshSeconds": 60,
  "mappings": [
    { "ticker": "AAPL", "itemId": "examplemod:apple_share", "scale": 1.0 },
    { "ticker": "MSFT", "itemId": "examplemod:microsoft_share", "scale": 1.0 }
  ]
}
```

Use items reserved for shares from a content mod or a dedicated share-token mod. Do not map AAPL to `minecraft:apple`, or other ordinary items: the market for that item would then represent the stock instead of its in-game supply and demand. The importer refuses to change a market that already exists.

## Rate limits and limitations

- Refresh interval is clamped to at least 60 seconds.
- A short delay is inserted between multiple tickers to avoid bursting the free-tier API quota. A large watchlist cannot be polled every minute under a low-credit plan.
- HTTP/API errors and invalid quotes leave the last valid price untouched.
- HTTP 429 triggers a five-minute backoff.
- The current implementation is an experimental price importer, not a fully separate virtual equity asset type. It maps a ticker to a dedicated Minecraft item market; it does not provide fractional shares, dividends, split handling, market-hours behavior, or bid/ask exchange simulation.
- The default config contains example mappings for vanilla items only to demonstrate the shape. Replace them with dedicated share items before using it on a live server. Because the importer refuses to overwrite an existing market, those vanilla mappings should not be used on a world where those item markets already exist.
