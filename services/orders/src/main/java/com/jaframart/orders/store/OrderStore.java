package com.jaframart.orders.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface OrderStore {
  String allocateOrderNumber();

  OrderRecord insert(OrderRecord order);

  Optional<OrderRecord> findByNumber(String orderNumber);

  List<OrderRecord> listByCustomer(String customerId, int limit);

  List<OrderRecord> recent(int limit);

  Map<String, Object> summary();

  List<Map<String, Object>> topCustomers(int limit);
}
