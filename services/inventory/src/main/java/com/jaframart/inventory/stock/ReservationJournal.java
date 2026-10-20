package com.jaframart.inventory.stock;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

@Component
public class ReservationJournal {
  private static final int CAPACITY = 100_000;

  private final ReentrantLock lock = new ReentrantLock(false);
  private final String[] ring = new String[CAPACITY];
  private int index;

  public void append(String entry) {
    lock.lock();
    try {
      ring[index] = entry;
      index = (index + 1) % CAPACITY;
    } finally {
      lock.unlock();
    }
  }
}
