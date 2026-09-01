package ai.opencode.mcp.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 描述 MCP 工具方法参数的名称、说明和必填语义。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolParam {

    /**
     * 获取参数名称；空字符串表示使用编译保留的 Java 参数名。
     *
     * @return MCP 工具参数名称
     */
    String name() default "";

    /**
     * 获取参数说明。
     *
     * @return 参数说明，空字符串表示未设置
     */
    String description() default "";

    /**
     * 判断参数是否必填。
     *
     * @return 必填时返回 {@code true}
     */
    boolean required() default true;
}
