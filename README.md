# InventoryLedger

A Java 17 inventory journal for warehouse receipts, bin transfers, balances, idempotent request IDs, and restart replay. The example connects backend correctness to practical inventory-control work.

## Run

Install a JDK 17 or newer. No external libraries are required.

```sh
git clone https://github.com/priyamukkapati/InventoryLedger.git
cd InventoryLedger
sh run.sh test
sh run.sh demo
sh run.sh cli data/ledger.tsv receive receipt-1 PN-100 A1 100
sh run.sh cli data/ledger.tsv transfer transfer-1 PN-100 A1 B2 25
sh run.sh cli data/ledger.tsv balance PN-100 A1
sh run.sh cli data/ledger.tsv balance PN-100 B2
```

The balances are 75 and 25. Repeating `transfer-1` with the same fields prints `recorded=false` and leaves both balances unchanged, including after reopening the journal. Reusing its ID with different fields rejects the request. Quantity is a positive integer in the smallest tracked unit, not a decimal amount.

## Reliability proof

`sh run.sh demo` starts with 100 units, submits 1,000 one-unit transfers with eight threads, and reopens the journal. It checks that only 100 transfers succeed and that total stock is conserved.

```text
attempts=1000 accepted=100 rejected=900 source=0 destination=100 replayed_events=101 elapsed_ms=88.24
```

Recorded locally on 2026-10-14 with OpenJDK 17.0.20. Timing includes thread scheduling, journal append operations, and replay, and will vary by machine. It is not a database or HTTP load test.

`sh run.sh test` passes 12 checks for receipt retries, conflicting IDs, transfers, insufficient stock, replay, retries after restart, concurrent oversell prevention, integer overflow, corrupt journal tails, and storage failures. CI is configured to run the tests and demo.

## Design choices

Each receipt or transfer appends one journal record. A synchronized critical section validates both balances and appends before updating memory. A successful transfer debits and credits the same quantity. Checked arithmetic rejects overflow. Replay reconstructs balances from the journal and rejects invalid records instead of silently ignoring corruption.

Each SKU/location balance is separate. Request IDs are global to the journal. Base64 encodes text fields so tabs and newlines cannot split records. This encoding is not encryption.

## Limitations

This is a local CLI, not an SAP integration, REST service, or PostgreSQL application. One process must own the journal at a time; separate processes or separate store instances are not coordinated. There is no file lock, fsync guarantee, crash-safe transaction recovery, stock reservation, reconciliation UI, authentication, or deletion. A torn append fails replay and needs repair or backup restoration; there is no automatic repair. The journal and request-ID index grow indefinitely.

## Next proof

Add PostgreSQL balances and ledger entries in a transaction with row-level locks and a unique request-ID constraint. Run independent processes competing for the last unit and verify conserved stock after restart. Add a cycle-count variance endpoint after proving transfer correctness.

## Resume description

Java inventory journal with idempotent receipts and transfers, replayable persistence, and synchronized stock validation; accepted 100 of 1,000 competing transfers without negative balances in a local concurrency demo.
