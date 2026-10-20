package com.jaframart.inventory.stock;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.LockSupport;

@Component
public class AuditTrail {
  private final ReservationJournal journal;
  private volatile long parkNanos;

  public AuditTrail(ReservationJournal journal) {
    this.journal = journal;
  }

  public void setParkNanos(long parkNanos) {
    this.parkNanos = Math.max(0, parkNanos);
  }

  public void record(String sku, String action) {
    journal.append(sku + ":" + action + ":" + System.nanoTime());
    long park = parkNanos;
    if (park > 0) {
      LockSupport.parkNanos(park);
    }
  }
}
