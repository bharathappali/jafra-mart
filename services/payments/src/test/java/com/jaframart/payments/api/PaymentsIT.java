package com.jaframart.payments.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

@QuarkusTest
class PaymentsIT {
  @Test
  void endpointsAndScenarioContract() {
    given().contentType("application/json")
        .body("{\"orderNumber\":\"JM-20261019-000123\",\"customerId\":\"user\",\"amountCents\":23398,\"currency\":\"USD\",\"cardToken\":\"tok_visa\"}")
        .when().post("/api/payments/authorize")
        .then().statusCode(200)
        .body("status", equalTo("AUTHORIZED"))
        .body("paymentId", equalTo("P-000123"));

    given().contentType("application/json")
        .body("{\"orderNumber\":\"JM-20261019-000124\",\"customerId\":\"user\",\"amountCents\":1000,\"currency\":\"USD\",\"cardToken\":\"tok_decline\"}")
        .when().post("/api/payments/authorize")
        .then().statusCode(200)
        .body("status", equalTo("DECLINED"));

    given().contentType("application/json")
        .body("{\"orderNumber\":\"JM-20261019-000125\",\"customerId\":\"user\",\"amountCents\":0,\"currency\":\"USD\",\"cardToken\":\"tok_visa\"}")
        .when().post("/api/payments/authorize")
        .then().statusCode(400);

    given().when().get("/api/payments/P-000123").then().statusCode(200).body("paymentId", equalTo("P-000123"));
    given().when().get("/api/payments/P-missing").then().statusCode(404);
    given().when().get("/api/info").then().statusCode(200)
        .body("component", equalTo("payments"))
        .body("framework", equalTo("quarkus 3.40.1"));

    given().when().get("/internal/scenarios").then().statusCode(200)
        .body("id", hasItems("S03", "S12"));
    given().contentType("application/json").body("{}")
        .when().post("/internal/scenarios/S03/start")
        .then().statusCode(202).body("state", equalTo("RAMPING"));
    given().contentType("application/json").body("{}")
        .when().post("/internal/scenarios/S03/start")
        .then().statusCode(409);
    given().when().post("/internal/scenarios/S03/reset").then().statusCode(200).body("state", equalTo("IDLE"));
    given().contentType("application/json").body("{}")
        .when().post("/internal/scenarios/S12/start")
        .then().statusCode(202).body("state", equalTo("RAMPING"));
    given().when().post("/internal/scenarios/S12/reset").then().statusCode(200).body("state", equalTo("IDLE"));
  }
}
