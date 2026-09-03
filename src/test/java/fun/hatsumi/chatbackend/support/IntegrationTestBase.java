package fun.hatsumi.chatbackend.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 连库集成测试基类。
 *
 * <p>标记 @Tag("db")：MySQL 部署前由 surefire excludedGroups 自动跳过（Deferred 模式），
 * MySQL 就绪并移除 pom 门控后自动启用。测试 profile 使用独立库 chatroom_lab_test，
 * 与开发库 chatroom_lab 完全隔离。</p>
 */
@Tag("db")
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestBase {
}
