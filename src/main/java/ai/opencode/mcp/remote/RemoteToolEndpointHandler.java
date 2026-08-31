package ai.opencode.mcp.remote;

import ai.opencode.mcp.api.ToolRegistration;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * 定义将一种远程端点配置绑定为注解工具调用计划的公共扩展契约。
 *
 * @author beining.shang
 * @since 2026-08-31
 */
public interface RemoteToolEndpointHandler {

  /**
   * 获取端点类型名称，用于启动校验和诊断信息。
   *
   * @return 稳定且可读的端点类型名称
   */
  String endpointType();

  /**
   * 获取当前实现声明的全部工具引用。
   *
   * @return 不可变的工具引用集合
   */
  Set<String> references();

  /**
   * 将匹配的注解方法绑定为远程工具注册信息。
   *
   * @param method 注解工具对应的 Java 方法
   * @param registration 扫描得到且名称与当前实现引用匹配的工具注册信息
   * @return 安装远程调用计划后的工具注册信息
   */
  ToolRegistration bind(Method method, ToolRegistration registration);
}
