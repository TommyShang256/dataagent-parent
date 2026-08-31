package ai.opencode.mcp.scanner;

import ai.opencode.mcp.annotation.ToolParam;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotatedField;
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Currency;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class McpJsonSchemaGenerator {

  private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

  private final ObjectMapper objectMapper;

  Map<String, Object> forMethod(java.lang.reflect.Method method) {
    var context = new SchemaContext(method);
    var properties = new LinkedHashMap<String, Object>();
    var required = new ArrayList<String>();
    for (var parameter : method.getParameters()) {
      var metadata = parameter.getAnnotation(ToolParam.class);
      var name = parameterName(parameter, metadata);
      var type = objectMapper.constructType(parameter.getParameterizedType());
      if (type.isPrimitive() && metadata != null && !metadata.required()) {
        throw context.failure(name, type, "primitive tool parameters cannot be optional");
      }

      var schema = new LinkedHashMap<>(context.valueSchema(type, name, null));
      if (metadata != null && !metadata.description().isBlank()) schema.put("description", metadata.description());
      properties.put(name, schema);
      if ((metadata == null || metadata.required()) && !isOptional(type)) required.add(name);
    }

    var root = new LinkedHashMap<String, Object>();
    root.put("$schema", DIALECT);
    root.put("type", "object");
    root.put("properties", properties);
    if (!required.isEmpty()) root.put("required", required);
    root.put("additionalProperties", false);
    if (!context.definitions.isEmpty()) root.put("$defs", context.definitions);
    return root;
  }

  @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
  private final class SchemaContext {

    private final java.lang.reflect.Method method;

    private final Map<String, Object> definitions = new LinkedHashMap<>();

    private final Map<String, String> definitionNames = new HashMap<>();

    private final Map<String, String> definitionTypes = new HashMap<>();

    private Map<String, Object> valueSchema(JavaType type, String path, Nulls valueNulls) {
      var schema = schema(type, path);
      if (acceptsNull(type, valueNulls)) return nullable(schema);
      return schema;
    }

    private Map<String, Object> schema(JavaType type, String path) {
      var raw = type.getRawClass();
      if (raw == Object.class || JsonNode.class.isAssignableFrom(raw)) return Map.of();
      if (raw == Optional.class) return schema(referencedType(type, path), path);
      if (raw == OptionalInt.class || raw == OptionalLong.class) return Map.of("type", "integer");
      if (raw == OptionalDouble.class) return Map.of("type", "number");
      if (raw == byte[].class) return Map.of("type", "string", "contentEncoding", "base64");
      if (raw == String.class || CharSequence.class.isAssignableFrom(raw)) return Map.of("type", "string");
      if (raw == Character.class || raw == char.class) {
        return Map.of("type", "string", "minLength", 1, "maxLength", 1);
      }
      if (raw == Boolean.class || raw == boolean.class) return Map.of("type", "boolean");
      if (isIntegral(raw)) return Map.of("type", "integer");
      if (isDecimal(raw)) return Map.of("type", "number");
      if (raw == UUID.class) return Map.of("type", "string", "format", "uuid");
      if (raw == URI.class || raw == URL.class) return Map.of("type", "string", "format", "uri");
      if (raw == LocalDate.class || raw == java.sql.Date.class) {
        return Map.of("type", "string", "format", "date");
      }
      if (raw == LocalTime.class || raw == OffsetTime.class || raw == java.sql.Time.class) {
        return Map.of("type", "string", "format", "time");
      }
      if (raw == Instant.class || raw == LocalDateTime.class || raw == OffsetDateTime.class
          || raw == ZonedDateTime.class || raw == java.sql.Timestamp.class || raw == Date.class
          || Calendar.class.isAssignableFrom(raw)) {
        return Map.of("type", "string", "format", "date-time");
      }
      if (raw == Duration.class || raw == Period.class || raw == Year.class || raw == YearMonth.class
          || raw == MonthDay.class || ZoneId.class.isAssignableFrom(raw) || raw == ZoneOffset.class
          || TemporalAccessor.class.isAssignableFrom(raw) || raw == Locale.class || raw == Currency.class
          || raw == File.class || Path.class.isAssignableFrom(raw) || Charset.class.isAssignableFrom(raw)) {
        return Map.of("type", "string");
      }
      if (raw.isEnum()) return enumSchema(raw, path);
      if (type.isArrayType()) {
        return Map.of("type", "array", "items", valueSchema(type.getContentType(), path + "[]", null));
      }
      if (type.isMapLikeType() || Map.class.isAssignableFrom(raw)) return mapSchema(type, path);
      if (type.isCollectionLikeType() || Iterable.class.isAssignableFrom(raw)) return iterableSchema(type, path);
      if (isUnsupported(raw) || raw.isInterface() || java.lang.reflect.Modifier.isAbstract(raw.getModifiers())) {
        throw failure(path, type, "concrete input structure is not available through Jackson introspection");
      }
      return objectReference(type, path);
    }

    private Map<String, Object> enumSchema(Class<?> raw, String path) {
      var values = new ArrayList<Object>();
      String commonType = null;
      var mixedTypes = false;
      for (var constant : raw.getEnumConstants()) {
        JsonNode node = objectMapper.valueToTree(constant);
        var currentType = jsonType(node);
        if (currentType == null) throw failure(path, objectMapper.constructType(raw), "enum value is not scalar JSON");
        if (commonType == null) commonType = currentType;
        else if (!commonType.equals(currentType)) mixedTypes = true;
        values.add(objectMapper.convertValue(node, Object.class));
      }
      var result = new LinkedHashMap<String, Object>();
      if (!mixedTypes && commonType != null) result.put("type", commonType);
      result.put("enum", values);
      return result;
    }

    private Map<String, Object> iterableSchema(JavaType type, String path) {
      var iterable = type.findSuperType(Iterable.class);
      var item = type.getContentType();
      if (item == null && iterable != null && iterable.containedTypeCount() > 0) item = iterable.containedType(0);
      if (item == null) item = objectMapper.constructType(Object.class);
      return Map.of("type", "array", "items", valueSchema(item, path + "[]", null));
    }

    private Map<String, Object> mapSchema(JavaType type, String path) {
      var map = type.findSuperType(Map.class);
      var key = type.getKeyType();
      var value = type.getContentType();
      if (map != null) {
        if (key == null) key = map.getKeyType();
        if (value == null) value = map.getContentType();
      }
      if (key == null) key = objectMapper.constructType(Object.class);
      if (value == null) value = objectMapper.constructType(Object.class);
      if (!jsonObjectKey(key.getRawClass())) {
        throw failure(path + "{key}", key, "map key cannot be represented as a JSON object property name");
      }
      return Map.of("type", "object", "additionalProperties", valueSchema(value, path + "{}", null));
    }

    private Map<String, Object> objectReference(JavaType type, String path) {
      var canonical = type.toCanonical();
      var name = definitionNames.computeIfAbsent(canonical, ignored -> allocateDefinitionName(type, canonical));
      if (!definitions.containsKey(name)) {
        var definition = new LinkedHashMap<String, Object>();
        definitions.put(name, definition);
        populateObjectDefinition(type, path, definition);
      }
      return Map.of("$ref", "#/$defs/" + name);
    }

    private void populateObjectDefinition(JavaType type, String path, Map<String, Object> definition) {
      var description = objectMapper.getDeserializationConfig().introspect(type);
      var properties = new LinkedHashMap<String, Object>();
      var required = new ArrayList<String>();
      for (var property : description.findProperties()) {
        if (!property.couldDeserialize()) continue;
        var propertyPath = path + "." + property.getName();
        var propertyType = property.getPrimaryType();
        var propertySchema = new LinkedHashMap<>(
            valueSchema(propertyType, propertyPath, property.getMetadata().getValueNulls()));
        var propertyDescription = property.getMetadata().getDescription();
        if (propertyDescription != null && !propertyDescription.isBlank()) {
          propertySchema.put("description", propertyDescription);
        }
        properties.put(property.getName(), propertySchema);
        if (property.isRequired() && !isOptional(propertyType)) required.add(property.getName());
      }

      definition.put("type", "object");
      definition.put("properties", properties);
      if (!required.isEmpty()) definition.put("required", required);
      var anySetter = description.findAnySetterAccessor();
      definition.put("additionalProperties", anySetter == null ? false : anySetterSchema(anySetter, path));
    }

    private Object anySetterSchema(com.fasterxml.jackson.databind.introspect.AnnotatedMember member, String path) {
      JavaType value = null;
      if (member instanceof AnnotatedMethod method && method.getParameterCount() >= 2) {
        value = method.getParameterType(1);
      } else if (member instanceof AnnotatedField field && field.getType().isMapLikeType()) {
        value = field.getType().getContentType();
      }
      return value == null ? Map.of() : valueSchema(value, path + ".<additional>", null);
    }

    private String allocateDefinitionName(JavaType type, String canonical) {
      var base = sanitize(typeName(type));
      var candidate = base;
      var suffix = 2;
      while (definitionTypes.containsKey(candidate) && !canonical.equals(definitionTypes.get(candidate))) {
        candidate = base + "_" + suffix++;
      }
      definitionTypes.put(candidate, canonical);
      return candidate;
    }

    private IllegalStateException failure(String path, JavaType type, String reason) {
      return new IllegalStateException("Cannot generate MCP input schema for " + method.toGenericString()
          + " at " + path + " (" + type.toCanonical() + "): " + reason);
    }
  }

  private static JavaType referencedType(JavaType type, String path) {
    var referenced = type.getReferencedType();
    if (referenced == null && type.containedTypeCount() > 0) referenced = type.containedType(0);
    if (referenced == null) throw new IllegalStateException("Optional type is missing its value type at " + path);
    return referenced;
  }

  private static boolean acceptsNull(JavaType type, Nulls valueNulls) {
    return !type.isPrimitive() && valueNulls != Nulls.FAIL;
  }

  private static boolean isOptional(JavaType type) {
    var raw = type.getRawClass();
    return raw == Optional.class || raw == OptionalInt.class || raw == OptionalLong.class || raw == OptionalDouble.class;
  }

  private static Map<String, Object> nullable(Map<String, Object> schema) {
    if (schema.isEmpty()) return schema;
    return Map.of("anyOf", List.of(schema, Map.of("type", "null")));
  }

  private static boolean isIntegral(Class<?> raw) {
    return raw == byte.class || raw == Byte.class || raw == short.class || raw == Short.class
        || raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class
        || raw == BigInteger.class || raw == AtomicInteger.class || raw == AtomicLong.class;
  }

  private static boolean isDecimal(Class<?> raw) {
    return raw == float.class || raw == Float.class || raw == double.class || raw == Double.class
        || raw == BigDecimal.class;
  }

  private static boolean jsonObjectKey(Class<?> raw) {
    return raw == Object.class || raw == String.class || CharSequence.class.isAssignableFrom(raw)
        || raw == Character.class || raw == char.class || raw == Boolean.class || raw == boolean.class
        || Number.class.isAssignableFrom(raw) || raw.isPrimitive() || raw.isEnum() || raw == UUID.class
        || raw == URI.class || raw == URL.class || raw == Locale.class || raw == Currency.class;
  }

  private static boolean isUnsupported(Class<?> raw) {
    return raw == Class.class || raw == ClassLoader.class || raw == Thread.class || raw.isAnonymousClass()
        || InputStream.class.isAssignableFrom(raw) || Reader.class.isAssignableFrom(raw)
        || OutputStream.class.isAssignableFrom(raw) || Throwable.class.isAssignableFrom(raw);
  }

  private static String jsonType(JsonNode node) {
    if (node.isTextual()) return "string";
    if (node.isIntegralNumber()) return "integer";
    if (node.isFloatingPointNumber()) return "number";
    if (node.isBoolean()) return "boolean";
    if (node.isNull()) return "null";
    return null;
  }

  private static String typeName(JavaType type) {
    var builder = new StringBuilder(type.getRawClass().getSimpleName());
    for (var index = 0; index < type.containedTypeCount(); index++) {
      var argument = type.containedType(index);
      if (argument != null) builder.append('_').append(typeName(argument));
    }
    return builder.toString();
  }

  private static String sanitize(String value) {
    var sanitized = value.replaceAll("[^A-Za-z0-9_]", "_");
    return sanitized.isBlank() ? "Type" : sanitized;
  }

  private static String parameterName(java.lang.reflect.Parameter parameter, ToolParam metadata) {
    if (metadata != null && !metadata.name().isBlank()) return metadata.name();
    if (parameter.isNamePresent()) return parameter.getName();
    throw new IllegalStateException(
        "Tool parameter names are unavailable. Compile with -parameters or set @ToolParam(name=...): " + parameter);
  }
}
