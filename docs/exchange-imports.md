# Exchange export imports

Status: in progress. Phase 1 (readers, report inventory, provenance, matching, import service) is being built; exchange adapters, futures-boundary transfers and the UI follow in that order.

Exchange export files (Binance ZIP/CSV, Bybit ZIP/CSV, MEXC XLSX) extend the same exchange accounts as API history. They reach history an exchange's API no longer returns, and they can create an account without API credentials.

## Pipeline

1. **Read** (`jvmSharedMain`, `ExportFileReader`): each selected file becomes tables of exact cell text. ZIP archives yield their CSV and XLSX members; XLSX workbooks yield one table per sheet. Formulas are never evaluated: a cell with a formula is marked, and a format that needs it rejects the file. Dates stored as Excel serial numbers keep their serial and the workbook's date system.
2. **Detect** (`commonMain`, `ExchangeReports`): a table's report is identified by its header cells (after any metadata preamble), never by the file name. Duplicate column names keep their positions. Each report has an exchange, a product family (spot, funding, futures, card), an evidence role and a parser version.
3. **Interpret**: each row becomes a normalized record: status, time (resolved per column), legs with exact amounts, native ID where the file has one, and the original cells.
4. **Match**: records are compared with what the account already holds (API and earlier imports) as economic events, not rows.
5. **Commit** (one transaction): source rows, events, derived activities, opening recalculation and the receipt. Matches are checked again inside the transaction.

## Limits

64 MiB per selected file, 256 MiB expanded input in total, 512 archive entries and 200,000 data rows per batch. Archive paths with `..` or absolute paths, encrypted entries and nested ZIP archives are rejected; XLSX members inside an export ZIP are read. Legacy `.xls` workbooks are rejected with a request to save as XLSX or CSV.

## Time zones

Per column first (`Time(UTC)`, `Date(UTC+8)`, `Creation Time(UTC+04:00)`), then the report's metadata or file name (`…(UTC+4)…`), then a choice in the preview. Exchange times never default to the device's time zone, and one sheet's offset never applies to another sheet.

## Evidence roles

- **Primary:** creates economic events (fills, deposits, withdrawals, conversions, balance-log rows).
- **Supporting:** order summaries; recorded as evidence, never as trades.
- **Checkpoint:** asset snapshots; compared with derived balances, never deposits.
- **Deferred:** futures statements, trades, positions, funding and P&L; kept with their product family for later futures accounting, counted on the receipt, not applied.

## Matching

- Native IDs match only within their exchange, account, wallet and event namespace.
- Without IDs, time, amount and coin are evidence, not a key: identical rows are distinct events, and an import's events are identified by their content and their order among identical rows, so reimports, renamed files, overlapping archives and reordered rows add nothing new.
- Statement groups are matched as groups against detailed events; detailed evidence wins for trade legs and fees, and every source stays attached.
- An ambiguous group waits for the user: same event, distinct event, or excluded.
- A completed event is never downgraded by a sparser source.

## Futures boundary

Transfers between spot/funding and futures move the actual coins in the supported wallets; they are shown as transferred-in, transferred-out and net flow per exchange. Futures balances are not part of portfolio totals, no futures profit is inferred, and coins coming back from futures have an unknown basis unless a reviewed relationship says otherwise.

## Undo

A receipt lists the batch's files, reports and counts. Undo removes the batch's contributions and derives the history again; events another file or the API also supports stay, with their annotations and reviewed decisions.
