package com.jaframart.reports.export;

import com.jaframart.reports.invoice.InvoiceWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExportServiceTest {
  @TempDir
  Path temp;

  @Test
  void baselineExportRowCountAndStableSha256() throws Exception {
    InvoiceWriter writer = new InvoiceWriter(temp.toString());
    try {
      ExportService exports = new ExportService(writer);
      ExportService.Result first = exports.exportBaseline(1000, 20261019L);
      ExportService.Result second = exports.exportBaseline(1000, 20261019L);
      assertEquals(1000, first.rows());
      assertEquals(first.sha256(), second.sha256());
      assertEquals(first.bytes(), second.bytes());
      ExportService.Result otherSeed = exports.exportBaseline(1000, 1L);
      assertEquals(1000, otherSeed.rows());
      // different seed must change synthetic rows and digest
      org.junit.jupiter.api.Assertions.assertNotEquals(first.sha256(), otherSeed.sha256());
    } finally {
      writer.close();
    }
  }
}
