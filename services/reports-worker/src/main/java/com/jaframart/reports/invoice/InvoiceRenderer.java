package com.jaframart.reports.invoice;

/**
 * Renders a ~6 KB plain-text invoice for FileChannel writes.
 */
public final class InvoiceRenderer {
  static final int TARGET_BYTES = 6_144;

  private InvoiceRenderer() {}

  public static String render(OrderInvoice order) {
    StringBuilder sb = new StringBuilder(TARGET_BYTES + 256);
    sb.append("JAFRA MART INVOICE\n");
    sb.append("==================\n");
    sb.append("Order: ").append(order.orderNumber()).append('\n');
    sb.append("Customer: ").append(order.customerName()).append(" (").append(order.customerId()).append(")\n");
    sb.append("Status: ").append(order.status()).append('\n');
    sb.append("Payment: ").append(order.paymentId()).append('\n');
    sb.append("Created: ").append(order.createdAt()).append('\n');
    sb.append("Subtotal: ").append(order.subtotalCents()).append(" cents\n");
    sb.append("Discount: ").append(order.discountCents()).append(" cents\n");
    sb.append("Total: ").append(order.totalCents()).append(" cents\n");
    sb.append("---- ITEMS ----\n");
    for (OrderInvoice.Line line : order.items()) {
      sb.append(line.sku()).append('\t')
          .append(line.productName()).append('\t')
          .append(line.quantity()).append('\t')
          .append(line.unitPriceCents()).append('\n');
    }
    sb.append("---- DETAIL ----\n");
    int line = 0;
    while (sb.length() < TARGET_BYTES) {
      sb.append(String.format("LINE %04d ", line++));
      for (int i = 0; i < 64 && sb.length() < TARGET_BYTES; i++) {
        sb.append((char) ('A' + ((line + i) % 26)));
      }
      sb.append('\n');
    }
    if (sb.length() > TARGET_BYTES) {
      sb.setLength(TARGET_BYTES);
    }
    return sb.toString();
  }
}
