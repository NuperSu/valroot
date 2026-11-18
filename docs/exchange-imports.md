# Exchange export imports

Status: implementation present; validation pending. The import service (`app.exchangeImports`) previews reports, matches them to API and earlier imports, commits them with receipts, and can undo a batch. Binance, Bybit, and MEXC report adapters and the shared import workflow are implemented.

Exchange export files (Binance ZIP/CSV, Bybit ZIP/CSV, MEXC XLSX) extend the same exchange accounts as API history. They reach history an exchange's API no longer returns, and they can create an account without API credentials.

## Pipeline

1. **Read** (`jvmSharedMain`, `ExportFileReader`): each selected file becomes tables of exact cell text. ZIP archives yield their CSV and XLSX members; XLSX workbooks yield one table per sheet. Formulas are never evaluated: a cell with a formula is marked, and a format that needs it rejects the file. Dates stored as Excel serial numbers keep their serial and the workbook's date system.
2. **Detect** (`commonMain`, `ExchangeReports`): a table's report is identified by its header cells (after any metadata preamble), never by the file name. Duplicate column names keep their positions. Each report has an exchange, a product family (spot, funding, futures, card), an evidence role and a parser version.
3. **Interpret**: each row becomes a normalized record: status, time (resolved per column), legs with exact amounts, native ID where the file has one, and the original cells.
4. **Match**: records are compared with what the account already holds (API and earlier imports) as economic events, not rows.
5. **Commit** (one transaction): source rows, events, derived activities, opening recalculation and the receipt. Changed matching outcomes require a new preview and roll back the transaction. Grouped events retain every physical source row and its exact cells.

## Limits

64 MiB per selected file, 256 MiB expanded input in total, 512 archive entries and 200,000 data rows, 256 columns per row and 1,000,000 cells per batch. Archive paths with `..` or absolute paths, encrypted entries and nested ZIP archives are rejected; XLSX members inside an export ZIP are read. Legacy `.xls` workbooks are rejected with a request to save as XLSX or CSV.

## Time zones

Per column first (`Time(UTC)`, `Date(UTC+8)`, `Creation Time(UTC+04:00)`), then the report's metadata or file name (`…(UTC+4)…`), then a choice in the preview. Exchange times never default to the device's time zone, and one sheet's offset never applies to another sheet.

## Evidence roles

- **Primary:** creates economic events (fills, deposits, withdrawals, conversions, balance-log rows).
- **Supporting:** order summaries; recorded as evidence, never as trades.
- **Checkpoint:** asset snapshots; compared with derived balances, never deposits.
- **Deferred:** futures statements, trades, positions, funding and P&L; kept with their product family for later futures accounting, counted on the receipt, not applied.

## Matching

- Native IDs match only within their exchange, account, wallet and event namespace.
- Without IDs, matching requires equal principal amounts and times within the report's matching window. Transfers retain transaction IDs from matching evidence, and records with conflicting IDs remain distinct.
- Statement imports identify occurrences by economic content and preserve the multiplicity of identical rows within each source table. Matching reimports, renamed files and reordered rows add evidence to the saved event. Detailed records supply the legs and fees when a statement group matches them.
- An ambiguous group waits for the user: same event, distinct event, or excluded.
- A completed event is never downgraded by a sparser source.
- Historical statement copies without enough source-table provenance remain separate; the preview asks for account-history review.

## Futures boundary

Completed transfers between supported spot/funding wallets and futures are shown as transferred-in, transferred-out and net flow per exchange. Futures balances, derivatives, funding and P&L are deferred and do not enter portfolio totals or accounting. Coins returning from futures have unknown basis unless a reviewed relationship says otherwise.

## Undo

A receipt lists the batch's files, reports and counts. Undo removes the batch's contributions and derives the history again; events another file or the API also supports stay, with their annotations and reviewed decisions. Undo also works while an account is archived and restores the newest remaining active batch decision for each reviewed group.

## Code

- Readers: `core/src/jvmSharedMain/…/exchange/imports/` (`ExportFileReader`, `XlsxReader`, `ZipDirectory`, which reads the central directory because exchanges write data descriptors that streaming readers reject).
- Tables, times, values and matching: `core/src/commonMain/…/exchange/imports/` (`ImportTables`, `ImportTime`, `ImportValues`, `ExportReports`, `ImportRecords`, `EventMatcher`).
- Service and storage: `ExchangeImportService` (`app.exchangeImports`) and `ImportRepository` (tables `exchange_import_batches`, `exchange_import_sources`, `exchange_import_events`, `exchange_import_decisions`).
- Settlement in decoding: `exchange/ImportedEvents.kt`, called by each decoder before balances are filled.
- Exchange adapters: `exchange/binance/BinanceExports.kt`, `exchange/bybit/BybitExports.kt`, `exchange/bybit/BybitImportedLogs.kt`, and `exchange/mexc/MexcExports.kt`.
- Shared UI: `sharedUi/src/commonMain/…/ExchangeImportWorkflow.kt`; file selection: `androidApp/…/AndroidImports.kt` and `desktopApp/…/DesktopImports.kt`.
- Import tests: `ExportFileReaderTest`, `EventMatcherTest`, `BinanceImportTest`, `BybitImportTest`, and `MexcImportTest`.

## Binance

- **Trades** (current and older `Date(UTC)` form) and **deposit and withdrawal histories** are detailed records; trades split their pair by the coins written with the amounts.
- **Transaction history:** trade, convert, dust and auto-invest rows are grouped per second and compared, net of fees, with the trades of that second; deposits and withdrawals compare by net change; Spot↔Funding and Simple Earn moves are internal; rewards, card spending, P2P and third-party wallet transfers are records; Spot↔UM/CM futures transfers are boundary transfers; rows of the futures wallets are deferred; unknown operations stay unresolved.
- **Orders** are supporting evidence.

## Bybit

- **Primary records:** Unified and Funding balance logs, including legacy Spot wallet logs, carry each wallet's balance after the change. They settle against matching API records and supply movements when API history is absent.
- **Supporting records:** transfer history supplies network and transaction details for matching deposits and withdrawals; spot-trade and card exports do not create movements by themselves. Card spending and refunds come from primary wallet logs.
- Unified spot trade legs are grouped using the contract, time, direction, quantity and fill price. API fill IDs identify matched evidence. When several fills cannot be distinguished, the observed movements stay separate and the account reports the pairing gap.
- Derivatives exports are deferred. Unsupported operations remain unresolved and are left out until classified.

## MEXC

- **Primary records:** spot fills, conversions, deposits, withdrawals, account transfers, other-funding history, and balance statements. Unrecognized statement operations remain unresolved and are left out until classified.
- **Supporting records:** order summaries are never trades. Asset snapshots are retained as checkpoints, not deposits; automatic checkpoint difference reporting is pending.
- Futures statements and derivatives reports are deferred; spot-to-futures transfers are shown only as transfers across the supported-wallet boundary.
