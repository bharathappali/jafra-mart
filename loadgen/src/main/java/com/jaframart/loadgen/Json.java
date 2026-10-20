package com.jaframart.loadgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

final class Json {
  static final ObjectMapper MAPPER = new ObjectMapper()
      .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, false);

  private Json() {}

  static byte[] bytes(Object value) {
    try {
      return MAPPER.writeValueAsBytes(value);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  static String string(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  static JsonNode tree(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  static <T> T read(InputStream in, Class<T> type) {
    try {
      return MAPPER.readValue(in, type);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  static <T> T convert(Object value, Class<T> type) {
    return MAPPER.convertValue(value, type);
  }
}
