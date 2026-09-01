package ai.opencode.dataagent.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * DataAgent BFF 应用启动入口。
 *
 * @author beining.shang
 * @since 2026-09-01
 */
@SpringBootApplication
public class DataAgentWebApplication {

    /**
     * 创建 BFF 应用配置实例。
     */
    DataAgentWebApplication() {
    }

    /**
     * 启动 DataAgent BFF 应用。
     *
     * @param args 命令行参数
     *             返回值：无。
     */
    public static void main(String[] args) {
        SpringApplication.run(DataAgentWebApplication.class, args);
    }
}
