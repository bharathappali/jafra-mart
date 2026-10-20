package com.jaframart.payments.gateway;

import com.jaframart.payments.risk.NativeRiskScorer;
import com.jaframart.payments.risk.RiskScorer;
import com.jaframart.scenario.Seeds;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class PaymentService {
  private static final long LATENCY_WINDOW_MS = 10_000L;

  private final AcquirerSimulator acquirer = new AcquirerSimulator();
  private final Map<String, PaymentRecord> payments = new ConcurrentHashMap<>();
  private final AtomicLong pinnedScoringCalls = new AtomicLong();
  private final Object latencyLock = new Object();
  private final Deque<LatencySample> latencyWindow = new ArrayDeque<>();
  private final SplittableSeed pinRandom = new SplittableSeed(Seeds.globalSeed() ^ "S12".hashCode());

  private volatile double pinFraction;
  private volatile double appliedPinIntensity;
  private volatile boolean pinBlocking = true;

  public PaymentRecord authorize(String orderNumber, String customerId, long amountCents, String currency, String cardToken) {
    if (amountCents <= 0) {
      throw new IllegalArgumentException("amountCents must be > 0");
    }
    long started = System.nanoTime();
    int[] signals = RiskScorer.signals(orderNumber, customerId, amountCents, currency, cardToken);
    int riskScore;
    boolean pin = shouldPin(orderNumber);
    if (pin) {
      riskScore = NativeRiskScorer.score(signals, pinBlocking);
      pinnedScoringCalls.incrementAndGet();
    } else {
      riskScore = RiskScorer.score(signals);
    }
    int sleepMs = acquirer.await(orderNumber);
    String status = "tok_decline".equals(cardToken) ? "DECLINED" : "AUTHORIZED";
    String paymentId = paymentId(orderNumber);
    long latencyMs = Math.max(sleepMs, (System.nanoTime() - started) / 1_000_000L);
    recordLatency(latencyMs);
    PaymentRecord record = new PaymentRecord(paymentId, status, riskScore, latencyMs, orderNumber, customerId, amountCents, currency);
    payments.put(paymentId, record);
    return record;
  }

  public Optional<PaymentRecord> get(String paymentId) {
    return Optional.ofNullable(payments.get(paymentId));
  }

  public void setSlowIntensity(double ci, boolean growthFrozen) {
    int next = 40 + (int) Math.round(1160.0 * Math.max(0.0, ci));
    if (growthFrozen) {
      acquirer.setBaseLatencyMs(Math.min(acquirer.baseLatencyMs(), next));
    } else {
      acquirer.setBaseLatencyMs(next);
    }
  }

  public void setPinIntensity(double ci, boolean growthFrozen) {
    long p95 = authorizeMsP95();
    if ((growthFrozen || p95 > 3000L) && ci > appliedPinIntensity) {
      pinFraction = 0.8 * appliedPinIntensity;
      return;
    }
    appliedPinIntensity = ci;
    pinFraction = 0.8 * ci;
  }

  public void setPinBlocking(boolean pinBlocking) {
    this.pinBlocking = pinBlocking;
  }

  public void resetScenarios() {
    acquirer.setBaseLatencyMs(40);
    pinFraction = 0;
    appliedPinIntensity = 0;
    pinBlocking = true;
    pinnedScoringCalls.set(0);
    synchronized (latencyLock) {
      latencyWindow.clear();
    }
  }

  public int baseLatencyMs() {
    return acquirer.baseLatencyMs();
  }

  public long authorizations() {
    return acquirer.authorizations();
  }

  public long pinnedScoringCalls() {
    return pinnedScoringCalls.get();
  }

  public long authorizeMsP95() {
    synchronized (latencyLock) {
      pruneLocked(System.currentTimeMillis());
      if (latencyWindow.isEmpty()) {
        return 0;
      }
      long[] values = latencyWindow.stream().mapToLong(LatencySample::ms).sorted().toArray();
      int index = Math.min(values.length - 1, (int) Math.ceil(values.length * 0.95) - 1);
      return values[Math.max(0, index)];
    }
  }

  private boolean shouldPin(String orderNumber) {
    double fraction = pinFraction;
    if (fraction <= 0) {
      return false;
    }
    return pinRandom.nextDouble(orderNumber) < fraction;
  }

  private void recordLatency(long latencyMs) {
    long now = System.currentTimeMillis();
    synchronized (latencyLock) {
      latencyWindow.addLast(new LatencySample(now, latencyMs));
      pruneLocked(now);
    }
  }

  private void pruneLocked(long now) {
    while (!latencyWindow.isEmpty() && now - latencyWindow.peekFirst().atMs() > LATENCY_WINDOW_MS) {
      latencyWindow.removeFirst();
    }
  }

  static String paymentId(String orderNumber) {
    String suffix = orderNumber == null ? "000000" : orderNumber;
    int dash = suffix.lastIndexOf('-');
    if (dash >= 0 && dash + 1 < suffix.length()) {
      suffix = suffix.substring(dash + 1);
    }
    return "P-" + suffix;
  }

  public record PaymentRecord(
      String paymentId,
      String status,
      int riskScore,
      long latencyMs,
      String orderNumber,
      String customerId,
      long amountCents,
      String currency) {
    public Map<String, Object> toMap() {
      return Map.of(
          "paymentId", paymentId,
          "status", status,
          "riskScore", riskScore,
          "latencyMs", latencyMs);
    }
  }

  private static final class SplittableSeed {
    private final long seed;

    SplittableSeed(long seed) {
      this.seed = seed;
    }

    double nextDouble(String orderNumber) {
      return new java.util.SplittableRandom(seed ^ (orderNumber == null ? 0 : orderNumber.hashCode())).nextDouble();
    }
  }

  private record LatencySample(long atMs, long ms) {}
}
