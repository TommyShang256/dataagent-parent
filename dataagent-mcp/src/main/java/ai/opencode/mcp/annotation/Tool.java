package ai.opencode.mcp.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 将 Spring Bean 方法声明为 MCP 工具。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Tool {

    /**
     * 工具调用者类别。
     */
    enum Caller {
        /**
         * 由 Agent 原生工具调用或公共 CodeMode 发起的调用。
         */
        AGENT,

        /**
         * 由服务端 Script MCP 入口接收的调用。
         */
        SCRIPT
    }

    /**
     * 工具在启动绑定完成后的执行类别。
     */
    enum Type {
        /**
         * 执行注解方法体的本地工具。
         */
        LOCAL,

        /**
         * 调用 API Fabric HTTP 端点的远程工具。
         */
        API_FABRIC,

        /**
         * 调用 CSE 服务端点的远程工具。
         */
        CSE,

        /**
         * 由自定义远程端点处理器绑定的工具。
         */
        CUSTOM
    }

    /**
     * 获取工具名称；空字符串表示使用 Java 方法名。
     *
     * @return MCP 工具名称
     */
    String name() default "";

    /**
     * 获取工具展示标题。
     *
     * @return 工具展示标题，空字符串表示未设置
     */
    String title() default "";

    /**
     * 获取工具功能说明。
     *
     * @return 工具功能说明，空字符串表示未设置
     */
    String description() default "";

    /**
     * 获取允许调用当前工具的调用者类别。
     *
     * @return 允许调用者；默认仅允许 Agent
     */
    Caller[] allowedCallers() default {Caller.AGENT};

    /**
     * 判断工具是否只读。
     *
     * @return 只读时返回 {@code true}
     */
    boolean readOnly() default false;

    /**
     * 判断工具是否可能产生破坏性变更。
     *
     * @return 可能产生破坏性变更时返回 {@code true}
     */
    boolean destructive() default true;

    /**
     * 判断相同参数的重复调用是否幂等。
     *
     * @return 幂等时返回 {@code true}
     */
    boolean idempotent() default false;

    /**
     * 判断工具是否可能访问开放世界中的外部实体。
     *
     * @return 可能访问外部实体时返回 {@code true}
     */
    boolean openWorld() default true;
}
