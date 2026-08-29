package ai.opencode.mcp.scanner;

import ai.opencode.mcp.annotation.ToolParam;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class McpJsonSchemaGenerator {

  private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

  private final ObjectMapper objectMapper;

  McpJsonSchemaGenerator(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  Map<String, Object> forMethod(Method method) {
    var properties = new LinkedHashMap<String, Object>();
    var required = new ArrayList<String>();
    for (var parameter : method.getParameters()) {
      var metadata = parameter.getAnnotation(ToolParam.class);
      var name = parameterName(parameter, metadata);
      var schema = new LinkedHashMap<>(schema(objectMapper.constructType(parameter.getParameterizedType()), Set.of()));
      if (metadata != null && !metadata.description().isBlank()) schema.put("description", metadata.description());
      properties.put(name, schema);
      if ((metadata == null || metadata.required()) && parameter.getType() != Optional.class) required.add(name);
      if (parameter.getType().isPrimitive() && metadata != null && !metadata.required()) {
        throw new IllegalStateException("Primitive tool parameter cannot be optional: " + parameter);
      }
    }

    var root = new LinkedHashMap<String, Object>();
    root.put("$schema", DIALECT);
    root.put("type", "object");
    root.put("properties", properties);
    if (!required.isEmpty()) root.put("required", required);
    root.put("additionalProperties", false);
    return root;
  }

  private Map<String, Object> schema(JavaType type, Set<Class<?>> visiting) {
    var raw = type.getRawClass();
    if (raw == Optional.class && type.containedTypeCount() == 1) return schema(type.containedType(0), visiting);
    if (raw == String.class || raw == Character.class || raw == char.class || raw == UUID.class) {
      return Map.of("type", "string");
    }
    if (Temporal.class.isAssignableFrom(raw)) return Map.of("type", "string");
    if (raw == Boolean.class || raw == boolean.class) return Map.of("type", "boolean");
    if (raw == Byte.class || raw == byte.class || raw == Short.class || raw == short.class
        || raw == Integer.class || raw == int.class || raw == Long.class || raw == long.class) {
      return Map.of("type", "integer");
    }
    if (Number.class.isAssignableFrom(raw) || raw == float.class || raw == double.class) {
      return Map.of("type", "number");
    }
    if (raw.isEnum()) {
      return Map.of("type", "string", "enum", List.of(raw.getEnumConstants()).stream().map(Object::toString).toList());
    }
    if (raw.isArray()) {
      return Map.of("type", "array", "items", schema(objectMapper.constructType(raw.getComponentType()), visiting));
    }
    if (Collection.class.isAssignableFrom(raw)) {
      var item = type.containedTypeCount() == 0 ? objectMapper.constructType(Object.class) : type.containedType(0);
      return Map.of("type", "array", "items", schema(item, visiting));
    }
    if (Map.class.isAssignableFrom(raw)) {
      var value = type.containedTypeCount() < 2 ? objectMapper.constructType(Object.class) : type.containedType(1);
      return Map.of("type", "object", "additionalProperties", schema(value, visiting));
    }
    if (raw == Object.class || visiting.contains(raw)) return Map.of();

    var next = new java.util.HashSet<>(visiting);
    next.add(raw);
    var properties = new LinkedHashMap<String, Object>();
    var description = objectMapper.getSerializationConfig().introspect(type);
    description.findProperties().stream()
        .filter(property -> property.couldSerialize() || property.couldDeserialize())
        .forEach(property -> properties.put(property.getName(), schema(property.getPrimaryType(), next)));
    var result = new LinkedHashMap<String, Object>();
    result.put("type", "object");
    result.put("properties", properties);
    result.put("additionalProperties", false);
    return result;
  }

  private static String parameterName(Parameter parameter, ToolParam metadata) {
    if (metadata != null && !metadata.name().isBlank()) return metadata.name();
    if (parameter.isNamePresent()) return parameter.getName();
    throw new IllegalStateException(
        "Tool parameter names are unavailable. Compile with -parameters or set @ToolParam(name=...): " + parameter);
  }
}
