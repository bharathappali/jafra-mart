package com.jaframart.catalog.model;

import java.util.List;

public final class WordLists {
  private WordLists() {}

  public static final List<String> ADJ = List.of(
      "Frosted", "Lunar", "Velvet", "Amber", "Crisp", "Nimble", "Quiet", "Bright",
      "Cozy", "Swift", "Bold", "Gentle", "Urban", "Rustic", "Polar", "Solar",
      "Misty", "Golden", "Silver", "Coral", "Minty", "Rosy", "Breezy", "Classic");

  public static final List<String> MAT = List.of(
      "Glass", "Bamboo", "Oak", "Steel", "Cotton", "Linen", "Ceramic", "Wool",
      "Leather", "Copper", "Marble", "Walnut", "Silk", "Granite", "Cork", "Titanium");

  public static final List<String> NOUN = List.of(
      "Lamp", "Mug", "Chair", "Desk", "Kettle", "Backpack", "Notebook", "Headphones",
      "Speaker", "Blanket", "Pillow", "Vase", "Clock", "Mirror", "Shelf", "Bottle",
      "Jacket", "Sneakers", "Watch", "Wallet", "Tray", "Bowl", "Planter", "Stool",
      "Rug", "Candle", "Frame", "Charger", "Keyboard", "Monitor");

  public static final List<String> CATEGORIES = List.of(
      "Home", "Kitchen", "Office", "Audio", "Outdoor", "Fashion",
      "Footwear", "Accessories", "Decor", "Electronics", "Wellness", "Toys");

  public static final List<String> FIRST = List.of(
      "Asha", "Ravi", "Meera", "Arjun", "Priya", "Kiran", "Neha", "Vikram", "Anya", "Rohan",
      "Sara", "Dev", "Isha", "Kabir", "Lila", "Omar", "Nina", "Tara", "Leo", "Maya");

  public static final List<String> LAST = List.of(
      "Rao", "Iyer", "Shah", "Menon", "Gupta", "Das", "Patel", "Nair", "Reddy", "Singh");
}
