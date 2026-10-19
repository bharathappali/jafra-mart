package com.jaframart.catalog.model;

import java.util.List;

public record Product(String sku, String name, String category, long priceCents, double rating, List<String> tags) {}
