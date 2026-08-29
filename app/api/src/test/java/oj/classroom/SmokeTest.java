package oj.classroom;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 冒烟测试：验证 Spring 上下文启动、V001 迁移在 H2(MySQL 模式) 下执行、
 * JPA 实体与视图映射通过 ddl-auto=validate。
 */
@SpringBootTest
@ActiveProfiles("test")
class SmokeTest {

    @Test
    void contextLoadsAndMigrationRuns() {
        // 上下文加载 + Flyway 迁移 + Hibernate validate 全部通过即视为成功
    }
}
