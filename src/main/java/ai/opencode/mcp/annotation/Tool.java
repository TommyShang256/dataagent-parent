package ai.opencode.mcp.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 将 Spring Bean 方法暴露为 MCP 工具。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Tool {

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
