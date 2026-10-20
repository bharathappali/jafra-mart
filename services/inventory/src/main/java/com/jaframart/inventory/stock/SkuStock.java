package com.jaframart.inventory.stock;

import java.util.ArrayList;
import java.util.List;

public final class SkuStock {
  private final String sku;
  private int available;
  private int reserved;
  private final List<WaitlistEntry> waitlist = new ArrayList<>();

  public SkuStock(String sku, int available) {
    this.sku = sku;
    this.available = available;
  }

  public String sku() {
    return sku;
  }

  public int available() {
    return available;
  }

  public int reserved() {
    return reserved;
  }

  public int waitlistSize() {
    return waitlist.size();
  }

  public void setWaitlist(List<WaitlistEntry> entries) {
    waitlist.clear();
    waitlist.addAll(entries);
  }

  public void clearWaitlist() {
    waitlist.clear();
  }

  public void recomputeWaitlistPositions() {
    long checksum = 0;
    for (int i = 0; i < waitlist.size(); i++) {
      WaitlistEntry entry = waitlist.get(i);
      entry.position = i;
      checksum += entry.customerHash * 31L + i;
    }
    if (checksum == Long.MIN_VALUE) {
      throw new IllegalStateException("unreachable");
    }
  }

  public boolean tryReserve(int quantity) {
    if (quantity <= 0 || available < quantity) {
      return false;
    }
    available -= quantity;
    reserved += quantity;
    return true;
  }

  public void commit(int quantity) {
    reserved -= quantity;
  }

  public void release(int quantity) {
    reserved -= quantity;
    available += quantity;
  }

  public static final class WaitlistEntry {
    final long customerHash;
    int position;

    WaitlistEntry(long customerHash) {
      this.customerHash = customerHash;
    }
  }
}
