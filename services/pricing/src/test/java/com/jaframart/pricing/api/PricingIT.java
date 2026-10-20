package com.jaframart.pricing.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

@QuarkusTest
class PricingIT {
  @Test
  void endpointsAndScenarios() {
    given().contentType("application/json")
        .body("{\"customerId\":\"customer007\",\"couponCode\":\"WELCOME10\",\"items\":[{\"sku\":\"SKU-00042\",\"quantity\":2}]}")
        .when().post("/api/quote")
        .then().statusCode(200)
        .body("subtotalCents", equalTo(25998))
        .body("discountCents", equalTo(2600))
        .body("totalCents", equalTo(23398));

    given().when().get("/api/campaigns")
        .then().statusCode(200)
        .body("rulesLoaded", equalTo(3))
        .body("hiddenClassesLive", equalTo(0));

    given().when().get("/api/info")
        .then().statusCode(200)
        .body("component", equalTo("pricing"))
        .body("framework", equalTo("quarkus 3.40.1"))
        .body("gc", equalTo("Serial"));

    given().when().get("/internal/scenarios")
        .then().statusCode(200)
        .body("id", hasItems("S08", "S09"));

    given().contentType("application/json").body("{\"intensity\":1.0}")
        .when().post("/internal/scenarios/S08/start")
        .then().statusCode(202)
        .body("state", equalTo("RAMPING"));

    given().contentType("application/json").body("{}")
        .when().post("/internal/scenarios/S08/start")
        .then().statusCode(409);

    given().when().post("/internal/scenarios/S08/reset")
        .then().statusCode(200)
        .body("state", equalTo("IDLE"));

    given().when().post("/internal/scenarios/reset-all")
        .then().statusCode(200);
  }
}
