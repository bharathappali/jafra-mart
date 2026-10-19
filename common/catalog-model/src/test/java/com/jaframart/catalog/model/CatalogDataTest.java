package com.jaframart.catalog.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogDataTest {
  @Test
  void twentyThousandProductsAndFlashSku() {
    var products = CatalogData.products();
    assertEquals(20_000, products.size());
    assertEquals("SKU-00001", products.get(0).sku());
    assertEquals("SKU-20000", products.get(19_999).sku());
    var flash = CatalogData.product("SKU-00042");
    assertEquals("Frosted Glass Smart Lamp", flash.name());
    assertEquals("Home", flash.category());
    assertEquals(12_999, flash.priceCents());
    assertEquals(3, flash.tags().size());
    for (var product : products) {
      assertTrue(product.priceCents() >= 499 && product.priceCents() <= 49_999);
      assertTrue(product.rating() >= 1.0 && product.rating() <= 5.0);
    }
    assertNull(CatalogData.product("SKU-99999"));
  }

  @Test
  void firstThreeNamesAreStable() {
    var products = CatalogData.products();
    assertEquals("Frosted Glass Shelf", products.get(0).name());
    assertEquals("Bright Titanium Chair", products.get(1).name());
    assertEquals("Golden Walnut Backpack", products.get(2).name());
  }

  @Test
  void customersAndCoupons() {
    var customers = CatalogData.customers();
    assertEquals(201, customers.size());
    assertEquals("customer001", customers.get(0).id());
    assertEquals("Asha Rao", customers.get(0).displayName());
    assertEquals("Maya Singh", customers.get(199).displayName());
    assertEquals("Demo User", customers.get(200).displayName());
    assertEquals(4, CatalogData.coupons().size());
    assertEquals(10, CatalogData.coupon("WELCOME10").percentOff());
    assertEquals(5, CatalogData.coupon("JAFRA5").percentOff());
    assertEquals(500, CatalogData.coupon("FREESHIP").centsOff());
    assertEquals("SKU-00042", CatalogData.coupon("FLASH20").onlySku());
    assertNull(CatalogData.coupon("NOPE"));
  }
}
