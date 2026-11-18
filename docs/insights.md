# Insights

Insights covers one portfolio scope and the range selected in History (1H to ALL). Every section uses the same start and end times as the History chart.

Code: `core/…/application/MoneyWeightedReturn.kt` (rate solver and result types), `AnalyticsService.moneyWeightedReturn` / `insights` (`app.analytics`), and `sharedUi/…/InsightsPresentation.kt`. Tests: `MoneyWeightedReturnTest`, `InsightsScreenTest`.

## Sections

- **History:** the portfolio value series, shared with Portfolio. A time without a fresh price for every holding is a gap; the line never bridges it with a guess. Portfolio values are drawn as a line only (see market-data.md, Candles).
- **Portfolio Performance:** the money-weighted return (internal rate of return) over the range.
  - **Flows:** contributions and withdrawals are transfer movements that enter or leave the scope's accounts, valued with a fresh saved price at their time. Transfers between accounts in the scope cancel out, within one activity or as the two sides of a transfer link (reviewed, or found from a shared on-chain transaction) when both fall in the period.
  - **Not flows:** trades (internal to the portfolio), including on-chain swaps whose legs are recorded as transfers of a trade activity; fees and rewards are part of the return.
  - **Investor view:** the start value and money put in are negative, money taken out and the end value are positive. An activity exactly at the start time is part of the start value.
  - **Shown figures:** the rate compounded over the range, plus an annual rate only for ranges of a year or more, because annualising shorter periods exaggerates them. Money in, money out, start and end values, and the investment gain (end − start − money in + money out) are exact.
  - **Coins without a price:** a coin lacking a fresh price at the start, the end or one of its flows is left out of every part of the return (start, end and flows alike) and counted in a note, as unpriced coins are left out of totals. Leaving out only its unpriced points would turn its value into a false gain or loss. A trade between a measured coin and a left-out one moves value across that boundary, so the measured side counts as money out (buying the left-out coin) or in (selling it); a measured side without a price then is left out too, repeated until nothing changes.
  - **Flows at the same instant** are summed first, so money moved in and out at once cancels instead of counting as an investment.
  - **When no rate is shown:** no coin has a price at every point the range needs; no money invested; more than one rate fitting the flows (checked when the flows change sign more than once); or no rate found between −99.99% and +100,000,000% a year. Each case states its reason instead of approximating.
- **Portfolio Diversity:** current value per asset as a share of priced value. The six largest are shown and the rest are grouped as Other. Unpriced assets are counted in a note, not in the denominator. Tapping an asset opens it.
- **Gains Reporting:** per asset, the quantity held now, realized gains from the scoped FIFO report, and unrealized gains of open lots. A figure that depends on an invalid classification shows —; costs and proceeds without a recorded amount use the market value at the time. Bitcoin order not yet verified on chain is calculated in timestamp order and shown as normal.
- **Asset Location:** value per account at the end of the range, from recorded accounts only. Valroot never infers an exchange or wallet brand from an address. "Most used accounts" counts confirmed activities in the range with a movement in each account.
- **Trade Statistics:** confirmed trades in the range, and the number of assets that appear as trade legs.
- **Fees:** fee movements in the range, each in its own asset at full precision, and its value at the time it was paid. The total is shown only when every fee has a price.
- **Asset Worth:** one asset's position value (quantity × price) at each History sample. It rises with purchases as well as with price, as its caption says.

Hidden amounts (the eye on Portfolio) mask money and quantities everywhere here; percentages and charts stay visible.

## Not included

- **Decisions:** removed. A judgment about past choices needs definitions that were not agreed.
- **Market benchmark:** not shown. A comparison needs a defined series fetched within provider limits; see the plan.
