package ai.opencode.mcp.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ai.opencode.mcp.annotation.ToolParam;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.net.URI;
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
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.Calendar;
import java.util.Currency;
import java.util.Date;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 验证 Java 参数类型到 MCP 工具 JSON Schema 的转换能力。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
class McpJsonSchemaGeneratorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private final McpJsonSchemaGenerator generator = new McpJsonSchemaGenerator(objectMapper);

    @Test
    @DisplayName("映射标量格式和 Jackson 枚举值")
    void mapsScalarsFormatsAndJacksonEnumValues() throws Exception {
        Map<String, Object> root = schema(
                "scalars", int.class, Integer.class, BigDecimal.class, UUID.class, URI.class);
        Map<String, Object> properties = map(root.get("properties"));

        assertThat(properties.get("count")).isEqualTo(Map.of("type", "integer"));
        assertThat(nonNull(map(properties.get("boxed")))).containsEntry("type", "integer");
        assertThat(nonNull(map(properties.get("amount")))).containsEntry("type", "number");
        assertThat(nonNull(map(properties.get("id"))))
                .containsEntry("type", "string").containsEntry("format", "uuid");
        assertThat(nonNull(map(properties.get("uri")))).containsEntry("format", "uri");
        assertThat(list(root.get("required"))).containsExactly("count", "boxed", "amount", "id", "uri");

        Map<String, Object> formats = schema("formats", LocalDate.class, Status.class, byte[].class);
        Map<String, Object> formatProperties = map(formats.get("properties"));
        assertThat(nonNull(map(formatProperties.get("date")))).containsEntry("format", "date");
        assertThat(nonNull(map(formatProperties.get("status"))))
                .containsEntry("enum", List.of("ready", "done"));
        assertThat(nonNull(map(formatProperties.get("bytes"))))
                .containsEntry("contentEncoding", "base64");
        assertThat(list(formats.get("required"))).containsExactly("date", "status", "bytes");
    }

    @Test
    @DisplayName("保留嵌套泛型并复用递归定义")
    void preservesNestedGenericsAndReusesRecursiveDefinitions() throws Exception {
        var root = schema("models", Map.class, Page.class, Node.class, JsonNode.class);
        var json = objectMapper.writeValueAsString(root);
        var definitions = map(root.get("$defs"));

        assertThat(json).contains("Page_Order", "Order", "#/$defs/Node", "#/$defs/Order");
        assertThat(definitions).containsKeys("Page_Order", "Order", "Node");
        assertThat(json.split("\\\"Node\\\":", -1)).hasSize(2);
        assertThat(map(map(definitions.get("Node")).get("properties"))).containsKeys("value", "next");
        assertThat(map(map(root.get("properties")).get("open"))).isEmpty();
    }

    @Test
    @DisplayName("遵循 Jackson 输入属性继承和任意字段接收器")
    void honorsJacksonInputPropertiesInheritanceAndAnySetter() throws Exception {
        var root = schema("bean", JacksonBean.class);
        var beanReference = nonNull(map(map(root.get("properties")).get("bean")));
        var name = ((String) beanReference.get("$ref")).substring("#/$defs/".length());
        var definition = map(map(root.get("$defs")).get(name));
        var properties = map(definition.get("properties"));

        assertThat(properties).containsKeys("parent", "renamed", "requiredValue");
        assertThat(properties).doesNotContainKeys("hidden", "readOnly");
        assertThat(list(definition.get("required"))).containsExactly("requiredValue");
        assertThat(definition.get("additionalProperties")).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("处理相互递归、定义重名和容器绑定")
    void handlesMutualRecursionDefinitionCollisionsAndContainerBindings() throws Exception {
        var mutual = schema("mutual", MutualA.class);
        assertThat(map(mutual.get("$defs"))).containsKeys("MutualA", "MutualB");
        assertThat(objectMapper.writeValueAsString(mutual)).contains("#/$defs/MutualA", "#/$defs/MutualB");

        var collisions = schema("collisions", First.Model.class, Second.Model.class);
        assertThat(map(collisions.get("$defs"))).containsKeys("Model", "Model_2");

        var containers = schema("containers", Set.class, Iterable.class, List.class, OptionalLong.class,
                OptionalDouble.class);
        var json = objectMapper.writeValueAsString(containers);
        assertThat(json).contains("set", "iterable", "bounded", "optionalLong", "optionalDouble", "#/$defs/Order");
    }

    @Test
    @DisplayName("建模参数存在性、null 和 Optional 变体")
    void modelsPresenceNullAndOptionalVariants() throws Exception {
        var root = schema("optional", String.class, String.class, Optional.class, OptionalInt.class, int.class);
        var properties = map(root.get("properties"));

        assertThat(list(root.get("required"))).containsExactly("requiredText", "primitive");
        assertThat(map(properties.get("text"))).containsKey("anyOf");
        assertThat(map(properties.get("optional"))).containsKey("anyOf");
        assertThat(map(properties.get("optionalInt"))).containsKey("anyOf");
        assertThat(properties.get("primitive")).isEqualTo(Map.of("type", "integer"));

        var validator = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(objectMapper.valueToTree(root));
        assertThat(validator.validate(objectMapper.valueToTree(Map.of("requiredText", "ok", "primitive", 7))))
                .isEmpty();
        assertThat(validator.validate(objectMapper.valueToTree(Map.of("requiredText", "ok")))).isNotEmpty();
        assertThat(validator.validate(objectMapper.valueToTree(Map.of(
                "requiredText", "ok", "primitive", 7, "unexpected", true)))).isNotEmpty();
    }

    @Test
    @DisplayName("报告不支持的具体类型和 Map 键路径")
    void reportsUnsupportedConcreteTypesAndMapKeysWithPath() throws Exception {
        var invalidMap = SchemaTools.class.getDeclaredMethod("invalidMap", Map.class);
        assertThatThrownBy(() -> generator.forMethod(invalidMap))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalidMap", "values{key}", Order.class.getName());

        var unsupported = SchemaTools.class.getDeclaredMethod("unsupported", InputStream.class);
        assertThatThrownBy(() -> generator.forMethod(unsupported))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported", "stream", InputStream.class.getName());

        var primitive = SchemaTools.class.getDeclaredMethod("invalidPrimitive", int.class);
        assertThatThrownBy(() -> generator.forMethod(primitive))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalidPrimitive", "value", "cannot be optional");
    }

    @Test
    @DisplayName("覆盖全部基础数值和字符布尔类型")
    void mapsAllPrimitiveWrapperAndNumberFamilies() throws Exception {
        Map<String, Object> root = schema("basicFamilies", StringBuilder.class, Character.class, char.class,
                Boolean.class, boolean.class);
        assertThat(map(root.get("properties"))).hasSize(5);

        assertThat(map(schema("integralFamilies", byte.class, Byte.class, short.class, Short.class, long.class)
                .get("properties"))).hasSize(5);
        assertThat(map(schema("integralFamilies2", Long.class, BigInteger.class, AtomicInteger.class,
                AtomicLong.class).get("properties"))).hasSize(4);
        assertThat(map(schema("decimalFamilies", float.class, Float.class, double.class, Double.class)
                .get("properties"))).hasSize(4);
    }

    @Test
    @DisplayName("覆盖日期时间和可文本化基础设施类型")
    void mapsAllDateTimeAndTextualInfrastructureFamilies() throws Exception {
        assertThat(map(schema("dateTimeFamilies", URL.class, java.sql.Date.class, LocalTime.class,
                OffsetTime.class, java.sql.Time.class).get("properties"))).hasSize(5);
        assertThat(map(schema("instantFamilies", Instant.class, LocalDateTime.class, OffsetDateTime.class,
                ZonedDateTime.class, java.sql.Timestamp.class).get("properties"))).hasSize(5);
        assertThat(map(schema("legacyTimeFamilies", Date.class, Calendar.class).get("properties"))).hasSize(2);
        assertThat(map(schema("temporalTextFamilies", Duration.class, Period.class, Year.class,
                YearMonth.class, MonthDay.class).get("properties"))).hasSize(5);
        assertThat(map(schema("infrastructureTextFamilies", ZoneId.class, Locale.class, Currency.class,
                java.io.File.class, Path.class).get("properties"))).hasSize(5);
        assertThat(map(schema("charsetFamily", Charset.class).get("properties"))).hasSize(1);
        assertThat(map(schema("temporalAccessorFamily", TemporalAccessor.class).get("properties"))).hasSize(1);
    }

    @Test
    @DisplayName("覆盖数组、开放对象和所有允许的 Map 键类型")
    void mapsArraysOpenObjectsAndAllowedMapKeyFamilies() throws Exception {
        Map<String, Object> containers = schema("openAndArray", Object.class, String[].class);
        assertThat(map(map(containers.get("properties")).get("open"))).isEmpty();
        assertThat(nonNull(map(map(containers.get("properties")).get("names"))))
                .containsEntry("type", "array");

        assertThat(map(schema("mapKeyFamilies", Map.class, Map.class, Map.class, Map.class, Map.class)
                .get("properties"))).hasSize(5);
        assertThat(map(schema("mapKeyFamilies2", Map.class, Map.class, Map.class, Map.class, Map.class)
                .get("properties"))).hasSize(5);
        assertThat(map(schema("mapKeyCurrency", Map.class).get("properties"))).hasSize(1);
    }

    @Test
    @DisplayName("按 Jackson 标量类型生成枚举约束并拒绝结构化枚举值")
    void mapsAllJacksonEnumScalarTypesAndRejectsStructuredValues() throws Exception {
        Map<String, Object> properties = map(schema("enumFamilies", IntegralValue.class, DecimalValue.class,
                BooleanValue.class, NullValue.class, MixedValue.class).get("properties"));
        assertThat(properties).hasSize(5);
        assertThat(nonNull(map(properties.get("mixed")))).doesNotContainKey("type");
        assertThat(map(schema("emptyEnum", EmptyValue.class).get("properties"))).hasSize(1);

        assertThatThrownBy(() -> schema("structuredEnum", StructuredValue.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("enum value is not scalar JSON");
    }

    @Test
    @DisplayName("支持字段 AnySetter、属性说明和拒绝 null 的 Jackson 元数据")
    void honorsFieldAnySetterDescriptionsAndNullRejectionMetadata() throws Exception {
        Map<String, Object> root = schema("metadataBean", MetadataBean.class, FieldAnySetterBean.class);
        String json = objectMapper.writeValueAsString(root);
        assertThat(json).contains("字段说明", "additionalProperties", "integer");
        assertThat(json).doesNotContain("\"nonNull\":{\"anyOf\"");
    }

    @Test
    @DisplayName("原始 Optional 使用 Jackson 推导的开放值类型")
    void mapsRawOptionalToJacksonInferredOpenValueType() throws Exception {
        Method rawOptional = SchemaTools.class.getDeclaredMethod("rawOptional", Optional.class);
        assertThat(generator.forMethod(rawOptional).toString()).contains("value");
    }

    @Test
    @DisplayName("拒绝所有不可安全建模的输入类型")
    void rejectsAllUnsupportedInterfaceAndAbstractFamilies() {
        Map<String, Class<?>> unsupported = Map.ofEntries(
                Map.entry("unsupportedClass", Class.class),
                Map.entry("unsupportedClassLoader", ClassLoader.class),
                Map.entry("unsupportedThread", Thread.class),
                Map.entry("unsupportedReader", Reader.class),
                Map.entry("unsupportedOutput", OutputStream.class),
                Map.entry("unsupportedThrowable", Throwable.class),
                Map.entry("unsupportedInterface", Runnable.class),
                Map.entry("unsupportedAbstract", AbstractInput.class));
        unsupported.forEach((method, type) -> {
            Throwable failure = catchThrowable(() -> schema(method, type));
            assertThat(failure).as(method)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(method);
        });
    }

    private Map<String, Object> schema(String name, Class<?>... parameterTypes) throws Exception {
        return generator.forMethod(SchemaTools.class.getDeclaredMethod(name, parameterTypes));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static Map<String, Object> nonNull(Map<String, Object> schema) {
        if (!schema.containsKey("anyOf")) {
            return schema;
        }
        return map(list(schema.get("anyOf")).get(0));
    }

    enum Status {
        READY("ready"), DONE("done");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        @JsonValue
        String value() {
            return value;
        }
    }

    enum IntegralValue {
        VALUE;

        @JsonValue
        int value() {
            return 1;
        }
    }

    enum DecimalValue {
        VALUE;

        @JsonValue
        double value() {
            return 1.5;
        }
    }

    enum BooleanValue {
        VALUE;

        @JsonValue
        boolean value() {
            return true;
        }
    }

    enum NullValue {
        VALUE;

        @JsonValue
        Object value() {
            return null;
        }
    }

    enum MixedValue {
        TEXT("text"), NUMBER(1);

        private final Object value;

        MixedValue(Object value) {
            this.value = value;
        }

        @JsonValue
        Object value() {
            return value;
        }
    }

    enum StructuredValue {
        VALUE;

        @JsonValue
        Map<String, String> value() {
            return Map.of("key", "value");
        }
    }

    enum EmptyValue {
    }

    record Order(@JsonProperty(required = true) UUID id) {
    }

    record Page<T>(List<T> items) {
    }

    record Node(String value, Node next) {
    }

    record MutualA(MutualB child) {
    }

    record MutualB(MutualA parent) {
    }

    static class First {
        record Model(String first) {
        }
    }

    static class Second {
        record Model(int second) {
        }
    }

    static class ParentBean {
        public String parent;
    }

    abstract static class AbstractInput {
    }

    static class JacksonBean extends ParentBean {
        @JsonProperty("renamed")
        public String original;

        @JsonProperty(required = true)
        public String requiredValue;

        @JsonIgnore
        public String hidden;

        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public String readOnly;

        @JsonAnySetter
        public void additional(String name, String value) {
        }
    }

    static class MetadataBean {
        @JsonPropertyDescription("字段说明")
        public String described;

        @JsonSetter(nulls = Nulls.FAIL)
        public String nonNull;

        @JsonProperty(required = true)
        public Optional<String> optionalRequired;
    }

    static class FieldAnySetterBean {
        @JsonAnySetter
        public Map<String, Integer> additional;
    }

    static class SchemaTools {
        void scalars(
                int count,
                Integer boxed,
                BigDecimal amount,
                UUID id,
                URI uri) {
        }

        void formats(LocalDate date, Status status, byte[] bytes) {
        }

        void models(
                Map<String, List<Optional<Order>>> values,
                Page<Order> page,
                Node node,
                JsonNode open) {
        }

        void bean(JacksonBean bean) {
        }

        void mutual(MutualA value) {
        }

        void collisions(First.Model first, Second.Model second) {
        }

        void containers(
                Set<Order> set,
                Iterable<Order> iterable,
                List<? extends Order> bounded,
                OptionalLong optionalLong,
                OptionalDouble optionalDouble) {
        }

        void optional(
                @ToolParam(required = false) String text,
                String requiredText,
                Optional<String> optional,
                OptionalInt optionalInt,
                int primitive) {
        }

        void invalidMap(Map<Order, String> values) {
        }

        void unsupported(InputStream stream) {
        }

        void invalidPrimitive(@ToolParam(required = false) int value) {
        }

        void basicFamilies(StringBuilder text, Character boxedCharacter, char primitiveCharacter,
                Boolean boxedBoolean, boolean primitiveBoolean) {
        }

        void integralFamilies(byte primitiveByte, Byte boxedByte, short primitiveShort, Short boxedShort,
                long primitiveLong) {
        }

        void integralFamilies2(Long boxedLong, BigInteger bigInteger, AtomicInteger atomicInteger,
                AtomicLong atomicLong) {
        }

        void decimalFamilies(float primitiveFloat, Float boxedFloat, double primitiveDouble, Double boxedDouble) {
        }

        void dateTimeFamilies(URL url, java.sql.Date date, LocalTime localTime, OffsetTime offsetTime,
                java.sql.Time time) {
        }

        void instantFamilies(Instant instant, LocalDateTime localDateTime, OffsetDateTime offsetDateTime,
                ZonedDateTime zonedDateTime, java.sql.Timestamp timestamp) {
        }

        void legacyTimeFamilies(Date date, Calendar calendar) {
        }

        void temporalTextFamilies(Duration duration, Period period, Year year, YearMonth yearMonth,
                MonthDay monthDay) {
        }

        void infrastructureTextFamilies(ZoneId zoneId, Locale locale, Currency currency, java.io.File file,
                Path path) {
        }

        void charsetFamily(Charset charset) {
        }

        void temporalAccessorFamily(TemporalAccessor temporalAccessor) {
        }

        void openAndArray(Object open, String[] names) {
        }

        void mapKeyFamilies(Map<Object, String> object, Map<StringBuilder, String> text,
                Map<Character, String> character, Map<Boolean, String> bool, Map<Integer, String> number) {
        }

        void mapKeyFamilies2(Map<Status, String> status, Map<UUID, String> uuid, Map<URI, String> uri,
                Map<URL, String> url, Map<Locale, String> locale) {
        }

        void mapKeyCurrency(Map<Currency, String> currency) {
        }

        void enumFamilies(IntegralValue integral, DecimalValue decimal, BooleanValue bool, NullValue nil,
                MixedValue mixed) {
        }

        void structuredEnum(StructuredValue value) {
        }

        void emptyEnum(EmptyValue value) {
        }

        void metadataBean(MetadataBean metadata, FieldAnySetterBean anySetter) {
        }

        @SuppressWarnings("rawtypes")
        void rawOptional(Optional value) {
        }

        void unsupportedClass(Class<?> value) {
        }

        void unsupportedClassLoader(ClassLoader value) {
        }

        void unsupportedThread(Thread value) {
        }

        void unsupportedReader(Reader value) {
        }

        void unsupportedOutput(OutputStream value) {
        }

        void unsupportedThrowable(Throwable value) {
        }

        void unsupportedInterface(Runnable value) {
        }

        void unsupportedAbstract(AbstractInput value) {
        }
    }
}
