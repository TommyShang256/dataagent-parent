package ai.opencode.mcp.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.opencode.mcp.annotation.ToolParam;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

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
    }
}
