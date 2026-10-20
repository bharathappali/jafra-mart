package com.jaframart.reports.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ReportsIT {
  @Autowired
  MockMvc mockMvc;

  @Test
  void endpointsAndScenarioContract() throws Exception {
    String order = "{\"orderNumber\":\"JM-20261019-000777\",\"customerId\":\"customer001\",\"customerName\":\"Asha Rao\","
        + "\"status\":\"PLACED\",\"subtotalCents\":12999,\"discountCents\":0,\"totalCents\":12999,"
        + "\"paymentId\":\"P-000777\",\"createdAt\":\"2026-10-19T12:00:00.000Z\","
        + "\"items\":[{\"sku\":\"SKU-00042\",\"productName\":\"Frosted Glass Smart Lamp\",\"quantity\":1,\"unitPriceCents\":12999}]}";

    mockMvc.perform(post("/api/invoices").contentType(MediaType.APPLICATION_JSON).content(order))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.orderNumber").value("JM-20261019-000777"))
        .andExpect(jsonPath("$.queued").value(true));

    mockMvc.perform(get("/api/invoices/JM-20261019-000777"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("JM-20261019-000777")));

    mockMvc.perform(post("/api/invoices/reprint").contentType(MediaType.APPLICATION_JSON).content("{\"count\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reprinted").value(1))
        .andExpect(jsonPath("$.fsyncs").isNumber())
        .andExpect(jsonPath("$.millis").isNumber());

    mockMvc.perform(get("/api/reports/exports/orders").param("rows", "1000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows").value(1000))
        .andExpect(jsonPath("$.sha256").isString())
        .andExpect(jsonPath("$.bytes").isNumber())
        .andExpect(jsonPath("$.millis").isNumber());

    mockMvc.perform(get("/api/reports/daily"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.invoices").isNumber())
        .andExpect(jsonPath("$.exports").isNumber())
        .andExpect(jsonPath("$.lastReconciliation").isString());

    mockMvc.perform(get("/api/info")).andExpect(status().isOk())
        .andExpect(jsonPath("$.component").value("reports-worker"))
        .andExpect(jsonPath("$.framework").value("spring-boot 4.1.1"));

    mockMvc.perform(get("/internal/scenarios")).andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(3)))
        .andExpect(jsonPath("$[*].id", hasItem("S06")))
        .andExpect(jsonPath("$[*].id", hasItem("S11")))
        .andExpect(jsonPath("$[*].id", hasItem("S13")));

    for (String id : new String[] {"S06", "S11", "S13"}) {
      mockMvc.perform(post("/internal/scenarios/" + id + "/start").contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.state", equalTo("RAMPING")));
      mockMvc.perform(post("/internal/scenarios/" + id + "/start").contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isConflict());
      mockMvc.perform(post("/internal/scenarios/" + id + "/reset")).andExpect(status().isOk())
          .andExpect(jsonPath("$.state", equalTo("IDLE")));
    }
  }
}
