package com.entropy.database.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
import java.util.Map;

@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = {
    "entropy.mcp.database.enabled=true",
    "entropy.mcp.database.dialect=h2",
    // 鉴权默认已改为开启，开启就必须有 admin 口令。这个用例只查 information_schema，
    // 显式关掉鉴权比给它编一个口令更准确地表达意图。此前它能启动是因为 SecurityConfigTest
    // 的静态块往 JVM 里塞了一个永不清理的系统属性，surefire 默认 reuseForks=true 于是被顺带蹭到；
    // 那条泄漏修掉后，这里的依赖就显形了。
    "entropy.mcp.security.enabled=false"
})
public class TestH2Schemas {

    @DynamicPropertySource
    static void registerH2DataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.jdbc-url", () -> "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    public void testSchemasQuery() {
        String sql = "SELECT schema_name FROM information_schema.schemata ORDER BY schema_name";
        
        // Test queryForList with String.class
        List<String> result1 = jdbcTemplate.queryForList(sql, String.class);
        System.out.println("queryForList(sql, String.class): " + result1);
        System.out.println("Type: " + result1.getClass().getName());
        if (!result1.isEmpty()) {
            System.out.println("First element type: " + result1.get(0).getClass().getName());
        }
        
        // Test queryForList without type
        List<Map<String, Object>> result2 = jdbcTemplate.queryForList(sql);
        System.out.println("queryForList(sql): " + result2);
        System.out.println("Type: " + result2.getClass().getName());
        if (!result2.isEmpty()) {
            System.out.println("First element type: " + result2.get(0).getClass().getName());
            System.out.println("First element: " + result2.get(0));
        }
    }
}
