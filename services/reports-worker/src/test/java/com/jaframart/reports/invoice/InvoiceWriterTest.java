package com.jaframart.reports.invoice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvoiceWriterTest {
  @TempDir
  Path temp;

  @Test
  void invoiceTextIsAboutSixKilobytes() {
    OrderInvoice order = sample("JM-20261019-000001");
    String text = InvoiceRenderer.render(order);
    assertEquals(InvoiceRenderer.TARGET_BYTES, text.length());
    assertTrue(text.contains("JM-20261019-000001"));
    assertTrue(text.contains("Frosted Glass Smart Lamp"));
  }

  @Test
  void rotatesAtTwoThousandFiles() throws Exception {
    InvoiceWriter writer = new InvoiceWriter(temp.toString());
    try {
      for (int i = 1; i <= InvoiceWriter.MAX_FILES + 5; i++) {
        writer.writeNow(sample(String.format("JM-ROT-%06d", i)));
      }
      assertEquals(InvoiceWriter.MAX_FILES, writer.fileCount());
      assertTrue(Files.notExists(temp.resolve("JM-ROT-000001.txt")));
      assertTrue(Files.isRegularFile(temp.resolve("JM-ROT-000006.txt")));
      assertTrue(Files.isRegularFile(temp.resolve(String.format("JM-ROT-%06d.txt", InvoiceWriter.MAX_FILES + 5))));
    } finally {
      writer.close();
    }
  }

  private static OrderInvoice sample(String orderNumber) {
    return new OrderInvoice(
        orderNumber,
        "customer001",
        "Asha Rao",
        "PLACED",
        12999,
        0,
        12999,
        "P-000001",
        "2026-10-19T12:00:00.000Z",
        List.of(new OrderInvoice.Line("SKU-00042", "Frosted Glass Smart Lamp", 1, 12999)));
  }
}
