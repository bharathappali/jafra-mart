package com.jaframart.cart.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

@QuarkusTest
class CartIT {
  @Test
  void endpointsAndScenario() {
    given().contentType("application/json").body("{\"sku\":\"SKU-00042\",\"quantity\":1}")
        .when().post("/api/carts/user/items")
        .then().statusCode(200).body("items[0].sku", equalTo("SKU-00042"));
    given().when().get("/api/carts/user").then().statusCode(200);
    given().contentType("application/json").body("{\"code\":\"WELCOME10\"}")
        .when().post("/api/carts/user/coupon")
        .then().statusCode(200).body("valid", equalTo(true));
    given().contentType("application/json").body("{\"code\":\"BADCODE1\"}")
        .when().post("/api/carts/user/coupon")
        .then().statusCode(200).body("valid", equalTo(false));
    given().when().delete("/api/carts/user").then().statusCode(204);
    given().when().get("/api/info").then().statusCode(200)
        .body("component", equalTo("cart"))
        .body("framework", equalTo("quarkus 3.40.1"));
    given().when().get("/internal/scenarios").then().statusCode(200).body("id", hasItem("S07"));
    given().contentType("application/json").body("{}")
        .when().post("/internal/scenarios/S07/start")
        .then().statusCode(202).body("state", equalTo("RAMPING"));
    given().contentType("application/json").body("{}")
        .when().post("/internal/scenarios/S07/start")
        .then().statusCode(409);
    given().when().post("/internal/scenarios/S07/reset").then().statusCode(200).body("state", equalTo("IDLE"));
  }
}
