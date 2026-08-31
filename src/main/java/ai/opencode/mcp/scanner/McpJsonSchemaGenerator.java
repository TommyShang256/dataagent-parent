package ai.opencode.mcp.scanner;

import ai.opencode.mcp.annotation.ToolParam;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotatedField;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
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

/**
 * 将注解工具方法的 Java 参数类型转换为 Agent 可用的 JSON Schema。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class McpJsonSchemaGenerator {

  private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

  private final ObjectMapper objectMapper;

  Map<String, Object> forMethod(Method method) {
    SchemaContext context = new SchemaContext(method);
    Map<String, Object> properties = new LinkedHashMap<>();
    List<String> required = new ArrayList<>();
    for (Parameter parameter : method.getParameters()) {
      ToolParam metadata = parameter.getAnnotation(ToolParam.class);
      String name = parameterName(parameter, metadata);
      JavaType type = objectMapper.constructType(parameter.getParameterizedType());
      if (type.isPrimitive() && metadata != null && !metadata.required()) {
        throw context.failure(name, type, "primitive tool parameters cannot be optional");
      }

      Map<String, Object> schema = new LinkedHashMap<>(context.valueSchema(type, name, null));
      if (metadata != null && !metadata.description().isBlank()) {
        schema.put("description", metadata.description());
      }
      properties.put(name, schema);
      if ((metadata == null || metadata.required()) && !isOptional(type)) {
        required.add(name);
      }
    }

    Map<String, Object> root = new LinkedHashMap<>();
    root.put("$schema", DIALECT);
    root.put("type", "object");
    root.put("properties", properties);
    if (!required.isEmpty()) {
      root.put("required", required);
    }
    root.put("additionalProperties", false);
    if (!context.definitions.isEmpty()) {
      root.put("$defs", context.definitions);
    }
    return root;
  }

  @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
  private final class SchemaContext {

    private final Method method;

    private final Map<String, Object> definitions = new LinkedHashMap<>();

    private final Map<String, String> definitionNames = new HashMap<>();

    private final Map<String, String> definitionTypes = new HashMap<>();

    private Map<String, Object> valueSchema(JavaType type, String path, Nulls valueNulls) {
      Map<String, Object> schema = schema(type, path);
      if (acceptsNull(type, valueNulls)) {
        return nullable(schema);
      }
      return schema;
    }

    private Map<String, Object> schema(JavaType type, String path) {
      Class<?> raw = type.getRawClass();
      if (raw == Object.class || JsonNode.class.isAssignableFrom(raw)) {
        return Map.of();
      }
      if (raw == Optional.class) {
        return schema(referencedType(type, path), path);
      }
      if (raw == OptionalInt.class || raw == OptionalLong.class) {
        return Map.of("type", "integer");
      }
      if (raw == OptionalDouble.class) {
        return Map.of("type", "number");
      }
      if (raw == byte[].class) {
        return Map.of("type", "string", "contentEncoding", "base64");
      }
      if (raw == String.class || CharSequence.class.isAssignableFrom(raw)) {
        return Map.of("type", "string");
      }
      if (raw == Character.class || raw == char.class) {
        return Map.of("type", "string", "minLength", 1, "maxLength", 1);
      }
      if (raw == Boolean.class || raw == boolean.class) {
        return Map.of("type", "boolean");
      }
      if (isIntegral(raw)) {
        return Map.of("type", "integer");
      }
      if (isDecimal(raw)) {
        return Map.of("type", "number");
      }
      if (raw == UUID.class) {
        return Map.of("type", "string", "format", "uuid");
      }
      if (raw == URI.class || raw == URL.class) {
        return Map.of("type", "string", "format", "uri");
      }
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
      if (raw.isEnum()) {
        return enumSchema(raw, path);
      }
      if (type.isArrayType()) {
        return Map.of("type", "array", "items", valueSchema(type.getContentType(), path + "[]", null));
      }
      if (type.isMapLikeType() || Map.class.isAssignableFrom(raw)) {
        return mapSchema(type, path);
      }
      if (type.isCollectionLikeType() || Iterable.class.isAssignableFrom(raw)) {
        return iterableSchema(type, path);
      }
      if (isUnsupported(raw) || raw.isInterface() || java.lang.reflect.Modifier.isAbstract(raw.getModifiers())) {
        throw failure(path, type, "concrete input structure is not available through Jackson introspection");
      }
      return objectReference(type, path);
    }

    private Map<String, Object> enumSchema(Class<?> raw, String path) {
      List<Object> values = new ArrayList<>();
      String commonType = null;
      boolean mixedTypes = false;
      for (Object constant : raw.getEnumConstants()) {
        JsonNode node = objectMapper.valueToTree(constant);
        String currentType = jsonType(node);
        if (currentType == null) {
          throw failure(path, objectMapper.constructType(raw), "enum value is not scalar JSON");
        }
        if (commonType == null) {
          commonType = currentType;
        } else if (!commonType.equals(currentType)) {
          mixedTypes = true;
        }
        values.add(objectMapper.convertValue(node, Object.class));
      }
      Map<String, Object> result = new LinkedHashMap<>();
      if (!mixedTypes && commonType != null) {
        result.put("type", commonType);
      }
      result.put("enum", values);
      return result;
    }

    private Map<String, Object> iterableSchema(JavaType type, String path) {
      JavaType iterable = type.findSuperType(Iterable.class);
      JavaType item = type.getContentType();
      if (item == null && iterable != null && iterable.containedTypeCount() > 0) {
        item = iterable.containedType(0);
      }
      if (item == null) {
        item = objectMapper.constructType(Object.class);
      }
      return Map.of("type", "array", "items", valueSchema(item, path + "[]", null));
    }

    private Map<String, Object> mapSchema(JavaType type, String path) {
      JavaType map = type.findSuperType(Map.class);
      JavaType key = type.getKeyType();
      JavaType value = type.getContentType();
      if (map != null) {
        if (key == null) {
          key = map.getKeyType();
        }
        if (value == null) {
          value = map.getContentType();
        }
      }
      if (key == null) {
        key = objectMapper.constructType(Object.class);
      }
      if (value == null) {
        value = objectMapper.constructType(Object.class);
      }
      if (!jsonObjectKey(key.getRawClass())) {
        throw failure(path + "{key}", key, "map key cannot be represented as a JSON object property name");
      }
      return Map.of("type", "object", "additionalProperties", valueSchema(value, path + "{}", null));
    }

    private Map<String, Object> objectReference(JavaType type, String path) {
      String canonical = type.toCanonical();
      String name = definitionNames.computeIfAbsent(canonical, ignored -> allocateDefinitionName(type, canonical));
      if (!definitions.containsKey(name)) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definitions.put(name, definition);
        populateObjectDefinition(type, path, definition);
      }
      return Map.of("$ref", "#/$defs/" + name);
    }

    private void populateObjectDefinition(JavaType type, String path, Map<String, Object> definition) {
      BeanDescription description = objectMapper.getDeserializationConfig().introspect(type);
      Map<String, Object> properties = new LinkedHashMap<>();
      List<String> required = new ArrayList<>();
      for (BeanPropertyDefinition property : description.findProperties()) {
        if (!property.couldDeserialize()) {
          continue;
        }
        String propertyPath = path + "." + property.getName();
        JavaType propertyType = property.getPrimaryType();
        Map<String, Object> propertySchema = new LinkedHashMap<>(
            valueSchema(propertyType, propertyPath, property.getMetadata().getValueNulls()));
        String propertyDescription = property.getMetadata().getDescription();
        if (propertyDescription != null && !propertyDescription.isBlank()) {
          propertySchema.put("description", propertyDescription);
        }
        properties.put(property.getName(), propertySchema);
        if (property.isRequired() && !isOptional(propertyType)) {
          required.add(property.getName());
        }
      }

      definition.put("type", "object");
      definition.put("properties", properties);
      if (!required.isEmpty()) {
        definition.put("required", required);
      }
      AnnotatedMember anySetter = description.findAnySetterAccessor();
      definition.put("additionalProperties", anySetter == null ? false : anySetterSchema(anySetter, path));
    }

    private Object anySetterSchema(AnnotatedMember member, String path) {
      JavaType value = null;
      if (member instanceof AnnotatedMethod method && method.getParameterCount() >= 2) {
        value = method.getParameterType(1);
      } else if (member instanceof AnnotatedField field && field.getType().isMapLikeType()) {
        value = field.getType().getContentType();
      }
      return value == null ? Map.of() : valueSchema(value, path + ".<additional>", null);
    }

    private String allocateDefinitionName(JavaType type, String canonical) {
      String base = sanitize(typeName(type));
      String candidate = base;
      int suffix = 2;
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
    JavaType referenced = type.getReferencedType();
    if (referenced == null && type.containedTypeCount() > 0) {
      referenced = type.containedType(0);
    }
    if (referenced == null) {
      throw new IllegalStateException("Optional type is missing its value type at " + path);
    }
    return referenced;
  }

  private static boolean acceptsNull(JavaType type, Nulls valueNulls) {
    return !type.isPrimitive() && valueNulls != Nulls.FAIL;
  }

  private static boolean isOptional(JavaType type) {
    Class<?> raw = type.getRawClass();
    return raw == Optional.class || raw == OptionalInt.class || raw == OptionalLong.class || raw == OptionalDouble.class;
  }

  private static Map<String, Object> nullable(Map<String, Object> schema) {
    if (schema.isEmpty()) {
      return schema;
    }
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
    if (node.isTextual()) {
      return "string";
    }
    if (node.isIntegralNumber()) {
      return "integer";
    }
    if (node.isFloatingPointNumber()) {
      return "number";
    }
    if (node.isBoolean()) {
      return "boolean";
    }
    if (node.isNull()) {
      return "null";
    }
    return null;
  }

  private static String typeName(JavaType type) {
    StringBuilder builder = new StringBuilder(type.getRawClass().getSimpleName());
    for (int index = 0; index < type.containedTypeCount(); index++) {
      JavaType argument = type.containedType(index);
      if (argument != null) {
        builder.append('_').append(typeName(argument));
      }
    }
    return builder.toString();
  }

  private static String sanitize(String value) {
    String sanitized = value.replaceAll("[^A-Za-z0-9_]", "_");
    return sanitized.isBlank() ? "Type" : sanitized;
  }

  private static String parameterName(Parameter parameter, ToolParam metadata) {
    if (metadata != null && !metadata.name().isBlank()) {
      return metadata.name();
    }
    if (parameter.isNamePresent()) {
      return parameter.getName();
    }
    throw new IllegalStateException(
        "Tool parameter names are unavailable. Compile with -parameters or set @ToolParam(name=...): " + parameter);
  }
}
