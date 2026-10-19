package com.jaframart.catalog.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SplittableRandom;

public final class CatalogData {
  public static final long SEED = 20261019L;
  public static final String FLASH_SKU = "SKU-00042";
  public static final int PRODUCT_COUNT = 20_000;

  private static final List<Product> PRODUCTS = generateProducts();
  private static final List<Customer> CUSTOMERS = generateCustomers();
  private static final List<Coupon> COUPONS = List.of(
      new Coupon("WELCOME10", 10, 0, null, "10% off"),
      new Coupon("JAFRA5", 5, 0, null, "5% off"),
      new Coupon("FREESHIP", 0, 500, null, "500 cents off"),
      new Coupon("FLASH20", 20, 0, FLASH_SKU, "20% off"));

  private CatalogData() {}

  public static List<Product> products() {
    return PRODUCTS;
  }

  public static Product product(String sku) {
    if (sku == null || !sku.startsWith("SKU-")) {
      return null;
    }
    try {
      int n = Integer.parseInt(sku.substring(4));
      if (n < 1 || n > PRODUCT_COUNT) {
        return null;
      }
      return PRODUCTS.get(n - 1);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  public static List<Customer> customers() {
    return CUSTOMERS;
  }

  public static List<Coupon> coupons() {
    return COUPONS;
  }

  public static Coupon coupon(String code) {
    if (code == null) {
      return null;
    }
    for (Coupon coupon : COUPONS) {
      if (coupon.code().equals(code)) {
        return coupon;
      }
    }
    return null;
  }

  private static List<Product> generateProducts() {
    SplittableRandom random = new SplittableRandom(SEED);
    String[] tags = tagPool();
    List<Product> products = new ArrayList<>(PRODUCT_COUNT);
    for (int i = 1; i <= PRODUCT_COUNT; i++) {
      String sku = String.format("SKU-%05d", i);
      String name = WordLists.ADJ.get(random.nextInt(24))
          + " " + WordLists.MAT.get(random.nextInt(16))
          + " " + WordLists.NOUN.get(random.nextInt(30));
      String category = WordLists.CATEGORIES.get(random.nextInt(12));
      int priceCents = 499 + random.nextInt(49_501);
      double rating = 1.0 + random.nextInt(41) / 10.0;
      List<String> productTags = threeTags(random, tags);
      if (FLASH_SKU.equals(sku)) {
        name = "Frosted Glass Smart Lamp";
        category = "Home";
        priceCents = 12999;
      }
      products.add(new Product(sku, name, category, priceCents, rating, productTags));
    }
    return List.copyOf(products);
  }

  private static String[] tagPool() {
    String[] pool = new String[WordLists.ADJ.size() + WordLists.MAT.size()];
    int i = 0;
    for (String word : WordLists.ADJ) {
      pool[i++] = word;
    }
    for (String word : WordLists.MAT) {
      pool[i++] = word;
    }
    return pool;
  }

  private static List<String> threeTags(SplittableRandom random, String[] pool) {
    LinkedHashSet<String> tags = new LinkedHashSet<>();
    while (tags.size() < 3) {
      tags.add(pool[random.nextInt(pool.length)]);
    }
    return List.copyOf(tags);
  }

  private static List<Customer> generateCustomers() {
    List<Customer> customers = new ArrayList<>(201);
    for (int n = 1; n <= 200; n++) {
      String id = String.format("customer%03d", n);
      String displayName = WordLists.FIRST.get((n - 1) % 20) + " " + WordLists.LAST.get((n - 1) / 20);
      customers.add(new Customer(id, id, displayName));
    }
    customers.add(new Customer("user", "user", "Demo User"));
    return List.copyOf(customers);
  }
}
