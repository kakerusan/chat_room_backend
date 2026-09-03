package fun.hatsumi.chatbackend.support;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * db tag 门控机制冒烟测试：本身标记 @Tag("db")，
 * 在 surefire excludedGroups=db 生效时应被跳过（mvn surefire 报告显示 skipped）。
 */
@Tag("db")
class TagSmokeTest {

    @Test
    void taggedDbTest() {
    }
}
