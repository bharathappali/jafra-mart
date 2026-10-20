package com.jaframart.pricing.engine;

import com.jaframart.pricing.rules.PriceRule;
import com.jaframart.scenario.ScenarioContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class CampaignEngine {
  private static final long METASPACE_CAP = 160L * 1024L * 1024L;
  private static final long GROWTH_LIMIT = Math.round(0.65 * METASPACE_CAP);

  private final TeardownGc teardownGc;
  private final Deque<Campaign> campaigns = new ArrayDeque<>();
  private final AtomicInteger campaignsCreated = new AtomicInteger();
  private final AtomicLong lastCreateEpochSec = new AtomicLong(-1);
  private volatile boolean teardownGcFlag;
  private volatile int holdSeconds = 180;

  @Inject
  public CampaignEngine(TeardownGc teardownGc) {
    this.teardownGc = teardownGc;
  }

  public void setHoldSeconds(int holdSeconds) {
    this.holdSeconds = Math.max(1, holdSeconds);
  }

  public synchronized void onTick(ScenarioContext ctx, double ci) {
    if (ci <= 0 || ctx.guard().growthFrozen()) {
      return;
    }
    if (metaspaceUsed() > GROWTH_LIMIT) {
      return;
    }
    int period = Math.max(5, holdSeconds / 9);
    long epoch = System.currentTimeMillis() / 1000L;
    long bucket = epoch / period;
    if (bucket == lastCreateEpochSec.get()) {
      return;
    }
    lastCreateEpochSec.set(bucket);
    int count = (int) Math.round(1500 * ci);
    List<PriceRule> rules = new ArrayList<>(count);
    try {
      for (int i = 0; i < count; i++) {
        rules.add(HiddenClassFactory.campaignRule(i));
      }
    } catch (Throwable ex) {
      ctx.log("campaign define failed: " + ex.getMessage());
      return;
    }
    campaigns.addLast(new Campaign("campaign-" + campaignsCreated.incrementAndGet(), rules));
    while (campaigns.size() > 4) {
      campaigns.removeFirst();
    }
  }

  public synchronized void reset() {
    campaigns.clear();
    lastCreateEpochSec.set(-1);
    teardownGc.gcOnce();
    teardownGcFlag = teardownGc.lastRan();
  }

  public synchronized String activeCampaignId() {
    Campaign campaign = campaigns.peekLast();
    return campaign == null ? "none" : campaign.id();
  }

  public synchronized List<PriceRule> liveRules() {
    Campaign campaign = campaigns.peekLast();
    if (campaign == null) {
      return List.of();
    }
    int n = Math.min(8, campaign.rules().size());
    return List.copyOf(campaign.rules().subList(0, n));
  }

  public synchronized int hiddenClassesLive() {
    int total = 0;
    for (Campaign campaign : campaigns) {
      total += campaign.rules().size();
    }
    return total;
  }

  public int campaignsLive() {
    return campaigns.size();
  }

  public int campaignsCreated() {
    return campaignsCreated.get();
  }

  public boolean teardownGc() {
    return teardownGcFlag;
  }

  public long metaspaceUsedMiB() {
    return metaspaceUsed() / (1024L * 1024L);
  }

  private static long metaspaceUsed() {
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if (pool.getName().toLowerCase().contains("metaspace")) {
        return pool.getUsage().getUsed();
      }
    }
    return 0;
  }

  private record Campaign(String id, List<PriceRule> rules) {}
}
