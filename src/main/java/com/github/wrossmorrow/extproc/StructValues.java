package com.github.wrossmorrow.extproc;

import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Package-private bidirectional conversions between protobuf {@link Struct}/{@link Value} and
 * standard Java values for Envoy {@code metadata_context}, {@code dynamic_metadata}, and CEL {@code
 * attributes}.
 */
final class StructValues {

  private StructValues() {}

  static Map<String, Object> toMap(Struct struct) {
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    if (struct == null) {
      return result;
    }
    for (Map.Entry<String, Value> entry : struct.getFieldsMap().entrySet()) {
      result.put(entry.getKey(), toJavaObject(entry.getValue()));
    }
    return result;
  }

  static Object toJavaObject(Value value) {
    if (value == null) {
      return null;
    }
    switch (value.getKindCase()) {
      case NULL_VALUE:
      case KIND_NOT_SET:
        return null;
      case NUMBER_VALUE:
        return Double.valueOf(value.getNumberValue());
      case STRING_VALUE:
        return value.getStringValue();
      case BOOL_VALUE:
        return Boolean.valueOf(value.getBoolValue());
      case STRUCT_VALUE:
        return toMap(value.getStructValue());
      case LIST_VALUE:
        List<Value> elements = value.getListValue().getValuesList();
        List<Object> list = new ArrayList<Object>(elements.size());
        for (Value elem : elements) {
          list.add(toJavaObject(elem));
        }
        return list;
      default:
        return null;
    }
  }

  static Value toProtoValue(Object value) {
    if (value == null) {
      return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
    }
    if (value instanceof Value) {
      return (Value) value;
    }
    if (value instanceof Struct) {
      return Value.newBuilder().setStructValue((Struct) value).build();
    }
    if (value instanceof ListValue) {
      return Value.newBuilder().setListValue((ListValue) value).build();
    }
    if (value instanceof Boolean) {
      return Value.newBuilder().setBoolValue(((Boolean) value).booleanValue()).build();
    }
    if (value instanceof String) {
      String str = (String) value;
      if (!StandardCharsets.UTF_8.newEncoder().canEncode(str)) {
        throw new IllegalArgumentException("dynamic metadata string contains invalid UTF-16");
      }
      return Value.newBuilder().setStringValue(str).build();
    }
    if (value instanceof Number) {
      double d = ((Number) value).doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        throw new IllegalArgumentException("dynamic metadata number must be finite");
      }
      return Value.newBuilder().setNumberValue(d).build();
    }
    if (value instanceof Map<?, ?>) {
      Struct.Builder structBuilder = Struct.newBuilder();
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        if (!(entry.getKey() instanceof String)) {
          throw new IllegalArgumentException("dynamic metadata map keys must be strings");
        }
        structBuilder.putFields((String) entry.getKey(), toProtoValue(entry.getValue()));
      }
      return Value.newBuilder().setStructValue(structBuilder.build()).build();
    }
    if (value instanceof Iterable<?>) {
      ListValue.Builder listBuilder = ListValue.newBuilder();
      for (Object item : (Iterable<?>) value) {
        listBuilder.addValues(toProtoValue(item));
      }
      return Value.newBuilder().setListValue(listBuilder.build()).build();
    }
    if (value instanceof Object[]) {
      ListValue.Builder listBuilder = ListValue.newBuilder();
      for (Object item : (Object[]) value) {
        listBuilder.addValues(toProtoValue(item));
      }
      return Value.newBuilder().setListValue(listBuilder.build()).build();
    }
    throw new IllegalArgumentException(
        "unsupported dynamic metadata value type: " + value.getClass().getName());
  }
}
