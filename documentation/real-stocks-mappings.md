# Default Real-Stock Mappings

This sample contains 18 markets for the StockMarket mod's optional Finnhub feed.

## Mappings

| In-game market | Finnhub symbol | Item ID | Scale | Notes |
|---|---|---|---:|---|
| XAU / Gold | `GLD` | `stockmarket:share_xau` | 10.918 | Gold ETF proxy, not spot gold |
| NASDAQ-100 / NAS100 | `QQQ` | `stockmarket:share_nas100` | 41.232 | Nasdaq-100 ETF proxy |
| S&P 500 / SP500 | `SPY` | `stockmarket:share_sp500` | 10.055 | S&P 500 ETF proxy |
| EUR/USD | `FXE` | `stockmarket:share_eurusd` | 0.010815 | Euro trust ETF proxy, not live forex spot |
| BTC / Bitcoin | `IBIT` | `stockmarket:share_btc` | 1762.2 | Bitcoin ETF proxy; US-market-hours limitations |
| NVIDIA | `NVDA` | `stockmarket:share_nvda` | 1.0 | Technology / semiconductors |
| Alphabet Class A | `GOOGL` | `stockmarket:share_googl` | 1.0 | Communication services / internet |
| Alphabet Class C | `GOOG` | `stockmarket:share_goog` | 1.0 | Same company, different share class |
| Apple | `AAPL` | `stockmarket:share_aapl` | 1.0 | Technology / consumer electronics |
| Microsoft | `MSFT` | `stockmarket:share_msft` | 1.0 | Technology / software and cloud |
| JPMorgan Chase | `JPM` | `stockmarket:share_jpm` | 1.0 | Financials / banking |
| ExxonMobil | `XOM` | `stockmarket:share_xom` | 1.0 | Energy |
| Eli Lilly | `LLY` | `stockmarket:share_lly` | 1.0 | Healthcare / pharmaceuticals |
| Walmart | `WMT` | `stockmarket:share_wmt` | 1.0 | Consumer staples / retail |
| Caterpillar | `CAT` | `stockmarket:share_cat` | 1.0 | Industrials / machinery |
| Visa | `V` | `stockmarket:share_v` | 1.0 | Financials / payments |
| Disney | `DIS` | `stockmarket:share_dis` | 1.0 | Communication services / media |
| Tesla | `TSLA` | `stockmarket:share_tsla` | 1.0 | Consumer discretionary / automotive |

## USD quote-unit and Pair Mode

The mod automatically creates a dedicated `stockmarket:share_usd` item/market and anchors its reference price at **1.00 USD**. In Pair Mode, the HAVE side is locked to this USD token; choose the counterpart on the WANT side. The cross-rate chart and order panel therefore show a USD-per-unit quote (for example, USD per EUR or USD per BTC) instead of an arbitrary item/item rate. Pair Mode's counterpart list is limited to the configured financial share tokens.

The USD token itself is not fetched from Finnhub and does not need a ticker entry in `real-stocks.json`. It is kept at 1.00 independently of the API token. Players still need USD-token inventory in their selected bank account to submit item-for-item orders; the USD token's money-mode market uses the same 1.00 reference price. It is a separate inventory item, not an automatic conversion from BankSystem cash. For a local test world, you can seed a test balance with `/give @p stockmarket:share_usd 64` (or take it from the creative inventory); multiplayer server operators must decide how USD tokens enter the economy.

## Charts on newly created markets

The StockMarket chart-history database stores candles on the configured candle interval. If a market is opened before its first candle has been saved—or a singleplayer world is paused before that interval completes—the history query may return no SQL rows. The server now returns the market's current in-memory candle as an initial live-only history response; persisted candles take over once saved, allowing normal and cross-rate charts to render immediately.

## Proxy caveat

Finnhub's stock quote endpoint is documented for US stocks. It does not make these five instruments direct spot feeds: XAU uses GLD, NASDAQ-100 uses QQQ, S&P 500 uses SPY, EUR/USD uses FXE, and BTC uses IBIT. These proxies track their target markets imperfectly, and ETF prices can update only while their US markets are trading. The starting scale factors are rough calibration values from public market snapshots on 2026-10-29; update them if you want closer price alignment.

The other 13 entries are direct US equity tickers. GOOGL and GOOG are Alphabet's two US share classes.

## Setup

1. Start the server once to generate `config/StockMarket/real-stocks.json`, or copy [the example JSON](real-stocks-example.json) there.
2. Replace `REPLACE_WITH_YOUR_FINNHUB_API_TOKEN` with your Finnhub token.
3. Restart the server. Default mappings use unique `stockmarket:share_*` items registered by this branch.
4. To disable auto-population of the defaults, set `useDefaultMappings` to `false` and edit `mappings` yourself.

The placeholder token keeps requests inactive until you replace it. If you display Finnhub-derived quotes to other players on a multiplayer server, check Finnhub's current [Terms of Service](https://finnhub.io/terms-of-service) and get written permission first; the terms restrict redistribution or sharing of market data and derived results.
