CREATE TABLE IF NOT EXISTS orders (
  id              BIGSERIAL PRIMARY KEY,
  order_number    TEXT NOT NULL UNIQUE,            -- "JM-" + yyyyMMdd + "-" + 6-digit sequence, e.g. JM-20261019-000123
  customer_id     TEXT NOT NULL,
  customer_name   TEXT NOT NULL,
  status          TEXT NOT NULL,                   -- PLACED | PAYMENT_DECLINED | FAILED
  subtotal_cents  BIGINT NOT NULL,
  discount_cents  BIGINT NOT NULL,
  total_cents     BIGINT NOT NULL,
  currency        TEXT NOT NULL DEFAULT 'USD',
  coupon_code     TEXT,
  payment_id      TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS order_items (
  order_id         BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  line_no          INT    NOT NULL,
  sku              TEXT   NOT NULL,
  product_name     TEXT   NOT NULL,
  quantity         INT    NOT NULL,
  unit_price_cents BIGINT NOT NULL,
  PRIMARY KEY (order_id, line_no)
);
CREATE INDEX IF NOT EXISTS idx_orders_created_at ON orders(created_at);
CREATE INDEX IF NOT EXISTS idx_orders_customer   ON orders(customer_id);
CREATE SEQUENCE IF NOT EXISTS order_number_seq;
