# Optional real-stock price feed (NeoForge / Minecraft 1.21.1)

The experimental Finnhub importer runs on the master server only. It uses Finnhub's `/api/v1/quote` endpoint, performs HTTPS calls on a daemon worker, and schedules market mutations back onto the Minecraft server thread. The first-run configuration is intentionally safe: `mappings` starts empty and no quotes are fetched until you add explicit ticker mappings and a real Finnhub API token.

## Setup

1. Start the server once. The importer creates `config/StockMarket/real-stocks.json`.
2. Create a Finnhub account and obtain an API token.
3. Replace `apiKey`'s inert placeholder with the Finnhub token.
4. Add mappings from ticker to dedicated share-token item IDs. Each mapping has `ticker`, `itemId`, and optional positive `scale`.
5. Restart the server. The provider is queried at startup and then on the configured interval.

Example configuration (not generated automatically):

```json
{
  "enabled": true,
  "apiKey": "REPLACE_WITH_YOUR_FINNHUB_API_TOKEN",
  "refreshSeconds": 60,
  "mappings": [
    { "ticker": "AAPL", "itemId": "examplemod:apple_share", "scale": 1.0 },
    { "ticker": "MSFT", "itemId": "examplemod:microsoft_share", "scale": 1.0 }
  ]
}
```

Use items reserved for shares from a content mod or a dedicated share-token mod. The `minecraft:` namespace is rejected entirely. Do not map a ticker to items already being traded. The importer refuses to adopt an existing market unless that market was previously created by the feed for the same ticker and its ownership marker was persisted. When a ticker is first added, its target item must be unused as a market. Map each item ID to only one ticker.

## Quote and market behavior

Finnhub's quote response provides the current price in `c` and a Unix timestamp in `t`. If Finnhub returns a zero/invalid current price (for example, no current quote is available), the feed preserves the last valid market price.

Quotes are reference/anchor prices, not an immutable broker tape: a new quote sets the market's current reference price; player fills may move it between refreshes, and the next quote re-anchors it. Player limit orders are not cancelled or repriced by the feed. The regular market update path moves the virtual-liquidity window to the anchor price, preserving overlapping levels and filling only newly exposed levels through the existing default-volume policy. The quote callback itself does not reset the orderbook.

Feed ownership is stored in the market's persisted NBT. The same ticker can therefore keep refreshing its market after a restart, but a legacy or unrelated market cannot be accidentally taken over. If the configured ticker changes for an already-owned item ID, the feed refuses that conflicting mapping.

## Request limits and terms

- Finnhub's free tier is commonly documented at 60 API calls per minute. This feed conservatively budgets 55 calls/minute and waits at least 1.1 seconds between symbols. With five mapped stocks and `refreshSeconds: 60`, each stock can be queried approximately once per minute. The provider may change its quotas, endpoint availability or plan terms; verify your Finnhub account's current allowance.
- A HTTP 429 or provider response indicating request-limit exhaustion stops the active batch immediately and applies a five-minute backoff.
- HTTP/API errors and invalid quotes leave the last valid price untouched.
- Shutdown marks the feed closed before interrupting its worker. Any quote callbacks queued by an older feed instance are ignored, and an unavailable/malformed config disables the feed safely.
- **Important licensing caveat:** Finnhub's Terms of Service say that its plans are for personal use and prohibit redistribution or sharing access to data or derived results with third parties without written approval. If this mod displays Finnhub-derived prices to other players on a multiplayer server, obtain written approval from Finnhub first. See [Finnhub Terms of Service](https://finnhub.io/terms-of-service) and [Finnhub pricing](https://finnhub.io/pricing).
- This is an experimental importer, not a fully separate native equity asset type. It does not provide fractional shares, dividends, stock splits, market-hours behavior, or external bid/ask data.
