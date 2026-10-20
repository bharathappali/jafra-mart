package com.jaframart.pricing.engine;

import com.jaframart.catalog.model.CatalogData;
import com.jaframart.catalog.model.Coupon;
import com.jaframart.catalog.model.Product;
import com.jaframart.pricing.rules.BaseRule;
import com.jaframart.pricing.rules.PriceRule;
import com.jaframart.pricing.rules.RoundingRule;
import com.jaframart.pricing.rules.VolumeRule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class QuoteService {
  private final CampaignEngine campaigns;

  @Inject
  public QuoteService(CampaignEngine campaigns) {
    this.campaigns = campaigns;
  }

  public Map<String, Object> quote(String customerId, String couponCode, List<Item> items) {
    List<Map<String, Object>> lines = new ArrayList<>();
    long subtotal = 0;
    List<PriceRule> campaignRules = campaigns.liveRules();
    for (Item item : items) {
      Product product = CatalogData.product(item.sku());
      if (product == null) {
        throw new IllegalArgumentException("unknown sku " + item.sku());
      }
      long unit = product.priceCents();
      long line = unit * item.quantity();
      List<String> applied = new ArrayList<>();
      line = applyNamed(BaseRule.INSTANCE, "base", line, item.quantity(), applied);
      line = applyNamed(VolumeRule.INSTANCE, "volume", line, item.quantity(), applied);
      line = applyNamed(RoundingRule.INSTANCE, "rounding", line, item.quantity(), applied);
      for (PriceRule rule : campaignRules) {
        line = rule.apply(line, item.quantity());
      }
      subtotal += line;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("sku", item.sku());
      row.put("quantity", item.quantity());
      row.put("unitPriceCents", unit);
      row.put("lineTotalCents", line);
      row.put("appliedRules", List.copyOf(applied));
      lines.add(row);
    }
    long discount = discountCents(couponCode, subtotal, items);
    long total = Math.max(0, subtotal - discount);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("currency", "USD");
    body.put("lines", lines);
    body.put("subtotalCents", subtotal);
    body.put("discountCents", discount);
    body.put("totalCents", total);
    body.put("campaign", campaigns.activeCampaignId());
    return body;
  }

  private static long applyNamed(PriceRule rule, String name, long line, int quantity, List<String> applied) {
    long next = rule.apply(line, quantity);
    applied.add(name);
    return next;
  }

  static long discountCents(String couponCode, long subtotal, List<Item> items) {
    Coupon coupon = CatalogData.coupon(couponCode);
    if (coupon == null) {
      return 0;
    }
    if (coupon.centsOff() > 0) {
      return Math.min(subtotal, coupon.centsOff());
    }
    if (coupon.onlySku() != null) {
      long eligible = 0;
      for (Item item : items) {
        if (coupon.onlySku().equals(item.sku())) {
          Product product = CatalogData.product(item.sku());
          if (product != null) {
            eligible += product.priceCents() * (long) item.quantity();
          }
        }
      }
      return percent(eligible, coupon.percentOff());
    }
    return percent(subtotal, coupon.percentOff());
  }

  private static long percent(long amount, int percent) {
    return BigDecimal.valueOf(amount)
        .multiply(BigDecimal.valueOf(percent))
        .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
        .longValueExact();
  }

  public record Item(String sku, int quantity) {}
}
