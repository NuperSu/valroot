# Market data on Main

Main's market sections come from CoinGecko's public API. CoinGecko is also the first source for every price the app values holdings with; see [Price sources](#price-sources).

Code: `core/…/market/MarketFeeds.kt` (parsing and cache), `MarketService.refreshFeeds` / `overview` (`app.market`), `sharedUi/…/MarketPresentation.kt` (sections), and `HttpCoingeckoTransport` in `core/src/jvmSharedMain/kotlin/io/valroot/core/market/`.

## Provider

- **Requests.** Two requests per refresh, with no API key required:
  - `/coins/markets?vs_currency=<display currency>&order=market_cap_desc&per_page=250&page=1&sparkline=true&price_change_percentage=1h,24h,7d` returns the top 250 coins with prices, market caps, changes and 7-day hourly sparklines (about 1 MB);
  - `/search/trending` returns CoinGecko's trending searches.
- **Rate limit.** The keyless limit is a few requests per minute.
  - The market list is refetched at most once a minute on manual refresh, and every 15 minutes on schedule or when Main opens with Auto-sync on.
  - Trending is refetched at most every 10 minutes.
  - An HTTP 429 pauses every feed request for one minute, and the saved lists stay on screen.
- **API key.** An optional CoinGecko Demo key (Settings › API Keys › External services) is sent as `x-cg-demo-api-key` for feeds and price history. It is kept in `device_settings`, sealed with the device key (see [architecture](architecture.md)), and is not part of backups.
- **Consent.** CoinGecko receives the display currency and the device's IP address, and no wallet data. DefiLlama, which prices coins on EVM chains in USD, receives those coin IDs and the IP address. Market requests need market-data consent: the "Load market data" prompt on Main, or turning on Auto-sync. Consent is stored in `application_settings` and therefore backed up.
- **Attribution.** "Data by CoinGecko" appears under the Main sections.

## Identity and caching

- **Identity.** Coins are identified by CoinGecko coin ID, never by symbol. `bitcoin` maps to the portfolio's `bitcoin:btc`, so favorites and coin pages match holdings; other coins are `coingecko:<id>`.
- **Caching.** Each feed is cached per display currency, with the time CoinGecko returned it, in `market_feed_cache`. The cache is excluded from backups and refetched after a restore. Each feed is saved independently: a failed trending request keeps the market list, and every failure keeps the previous copy.
- **Trending prices.** A trending coin's price is its `price_btc` times CoinGecko's Bitcoin price in the display currency from the market list. Its 24-hour change comes from the trending entry in that currency.

## Price sources

Every price is looked for in this order, and a later source is used only when the earlier ones have nothing fresh enough (two hours for current values, a day for history):

1. **CoinGecko**: current prices of linked coins (`/simple/price`), their history (`/market_chart`, a year of daily prices, then 90 days of hourly ones) and Bitcoin history fetched on request. The free Demo key reaches back 365 days.
2. **Kraken**: Bitcoin's hourly and daily prices from automatic refresh, and the fallback for candles.
3. **DefiLlama**: US dollar prices of linked coins (EVM tokens, exchange coins), current and back to each coin's first movement. It fills coins CoinGecko does not price and history older than CoinGecko's year.

A manual price newer than the chosen provider price still replaces it (`PricePolicy.preferSource` in `Market.kt`, `PriceService.policy`).

## Currencies

Values are shown in US dollars only for now (`DISPLAY_CURRENCIES` in `ApplicationTypes.kt`). The currency selectors on Portfolio and in Settings list euros and pounds as planned and do not select them. A display, secondary or portfolio currency saved earlier reads as US dollars and stays saved. Euros and pounds remain assets: a trade paid in euros is recorded in euros, and its cost basis in dollars is unknown until it is classified with a dollar value.

Planned: every price is fetched in US dollars, and each other currency's exchange rate (such as EUR/USD) is fetched separately; a value in euros is the dollar value converted at the rate of the same time. One price history per coin then serves every currency, so adding a currency costs one rate history instead of a second price history per coin.

## Sections and semantics

- **Top Coins:** the first 20 of the top 250 by market cap. Each card shows the 24-hour change and a line of the last 24 hourly points.
- **Trending:** CoinGecko's trending searches; their order is CoinGecko's.
- **Top Gainers / Top Losers:** the five largest 24-hour rises and falls *within the top 250 by market cap*, labelled "TOP 250 BY MARKET CAP · 24H". They are not whole-market rankings.
- **Favorites:** a star on any list adds or removes a coin; favorites are stored in `application_settings` as asset IDs.
- **Status line:** shows when the lists were updated, and any rate limit (with the wait), offline state or unreadable response.
- **Coin pages for coins you do not hold:**
  - they show the CoinGecko price, 24-hour and 7-day change, market cap and rank, and the 7-day hourly chart labelled 1W;
  - transactions can only be recorded for Bitcoin, so Add Transaction is disabled there.

## Candles

Coin charts can show candles: the open, high, low and close prices traded in each period, as the market reported them. Code: `core/…/market/MarketCandles.kt`, `MarketService.candles` (`app.market`), `loadCandles` / `fetchCandles` in `PlatformActions.kt`, `CoinCandles.kt`. Tests: `MarketCandlesTest`, `CoinCandlesTest`, `InteractiveChartTest`.

- **CoinGecko first:** `/coins/{id}/ohlc` in the display currency, with market data consent. CoinGecko sets the candle length from the range: 30 minutes for 1D, 4 hours for 1W and 1M, 4 days for 3M and 1Y. Its prices are aggregated across exchanges, and its shortest candle is 30 minutes, so it has no 1H candles. It lists closed candles only, so a 4-day candle can be up to four days behind: the candle still forming is combined from its shorter closed candles (4-hour ones from the 7-day range, then 30-minute ones from the 1-day range), one or two more requests. If they fail, the closed candles still show. The forming candle's tooltip ends at "now". Responses are kept for three minutes in the running app and shared between ranges (the 7-day response is both the 1W candles and the 4-hour pieces of 3M and 1Y), so switching ranges rarely needs a request. A rate-limited candle request starts the same one-minute pause as the market lists; no candle request is made during it.
- **Kraken as fallback:** when CoinGecko cannot answer (1H, no market data consent, rate limit, offline or no data) and Kraken requests are allowed (the order book consent), Kraken's public OHLC for the coin's Kraken pair in the display currency: 1-minute candles for 1H, 30 minutes for 1D, 4 hours for 1W, daily for 1M and 3M, weekly for 1Y. Kraken's candles are one venue's trades and include the candle still forming.
- **No guesses:** without candles from either source the chart draws its line and says why. Candles are never derived from a sampled line.
- **Loading:** candles load when the Candles style is chosen on a coin's General tab, once per coin, range and currency while the page is open; they are not saved.
- **Portfolio and Insights charts** are always lines: a portfolio's value is not traded, so it has no candles, and adding up each coin's highs would overstate its high.

## Logos

- **Source:** each coin's logo is the `image` URL from the market list, or `small` from trending, converted to CoinGecko's small size. Only `https://coin-images.coingecko.com` and `https://assets.coingecko.com` image URLs are accepted.
- **When they load:** logos load only after market-data consent, at most four downloads at a time, without redirects, up to 256 KiB each.
- **Caching:** logos are cached in `market_logo_cache` (excluded from backups) and refetched after 30 days. A logo that fails is not retried until the next launch.
- **Where they appear:** the same logo is used on Main, Portfolio holdings and coin pages, because coins share one asset ID. Coins without a logo URL show a coloured mark with their initial.

## Not covered

Price alerts and order books are M5.
