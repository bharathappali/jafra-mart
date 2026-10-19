package com.jaframart.catalog.model;

public record Coupon(String code, int percentOff, int centsOff, String onlySku, String message) {}
