package ai.opencode.mcp.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Exposes a Spring bean method as an MCP tool. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Tool {

  String name() default "";

  String title() default "";

  String description() default "";

  boolean readOnly() default false;

  boolean destructive() default true;

  boolean idempotent() default false;

  boolean openWorld() default true;
}
