package fun.hatsumi.chatbackend;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 应用 context 冒烟测试。
 *
 * <p>context 启动需要 datasource + Flyway，因此标记 @Tag("db")：
 * MySQL 部署前自动跳过（Deferred 模式），部署后随全量测试启用。</p>
 */
@Tag("db")
@SpringBootTest
@ActiveProfiles("test")
class ChatBackendApplicationTests {

    @Test
    void contextLoads() {
    }

}
