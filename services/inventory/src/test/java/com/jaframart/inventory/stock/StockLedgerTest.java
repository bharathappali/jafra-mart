package com.jaframart.inventory.stock;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class StockLedgerTest {
  @Test
  void reserveCommitReleaseArithmetic() {
    StockLedger ledger = new StockLedger(new AuditTrail(new ReservationJournal()));
    ledger.seed();
    int before = (int) ledger.availability("SKU-00001").get("available");
    StockLedger.Reservation held = ledger.reserve("JM-1", List.of(new StockLedger.Line("SKU-00001", 3)));
    assertNotNull(held);
    assertEquals(before - 3, ledger.availability("SKU-00001").get("available"));
    assertEquals(3, ledger.availability("SKU-00001").get("reserved"));
    ledger.commit(held.id());
    assertEquals(before - 3, ledger.availability("SKU-00001").get("available"));
    assertEquals(0, ledger.availability("SKU-00001").get("reserved"));
  }

  @Test
  void conflictLeavesStockUnchanged() {
    StockLedger ledger = new StockLedger(new AuditTrail(new ReservationJournal()));
    ledger.seed();
    int before = (int) ledger.availability("SKU-00001").get("available");
    assertNull(ledger.reserve("JM-2", List.of(new StockLedger.Line("SKU-00001", before + 1))));
    assertEquals(before, ledger.availability("SKU-00001").get("available"));
  }

  @Test
  void concurrentReserveNeverOversells() throws Exception {
    StockLedger ledger = new StockLedger(new AuditTrail(new ReservationJournal()));
    ledger.seed();
    String sku = "SKU-00003";
    int initial = (int) ledger.availability(sku).get("available");
    AtomicInteger committed = new AtomicInteger();
    var pool = Executors.newFixedThreadPool(16);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      futures.add(pool.submit(() -> {
        try {
          start.await();
          for (int n = 0; n < 40; n++) {
            StockLedger.Reservation reservation = ledger.reserve("JM-" + Thread.currentThread().threadId() + "-" + n,
                List.of(new StockLedger.Line(sku, 1)));
            if (reservation != null) {
              ledger.commit(reservation.id());
              committed.incrementAndGet();
            }
          }
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
      }));
    }
    start.countDown();
    for (Future<?> future : futures) {
      future.get();
    }
    pool.shutdown();
    int available = (int) ledger.availability(sku).get("available");
    assertEquals(initial - committed.get(), available);
  }
}
