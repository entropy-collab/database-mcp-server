package com.entropy.database.mcp.init;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnProperty(name = "entropy.mcp.test-data.enabled", havingValue = "true")
public class TestDataInitializer {

    @Bean
    public CommandLineRunner initData(JdbcTemplate jdbcTemplate) {
        return args -> {
            jdbcTemplate.execute("DROP TABLE IF EXISTS test_users");
            jdbcTemplate.execute("""
                CREATE TABLE test_users (
                    id INT PRIMARY KEY,
                    name VARCHAR(100),
                    email VARCHAR(100),
                    phone VARCHAR(20),
                    department VARCHAR(50)
                )
                """);
            for (int i = 1; i <= 15; i++) {
                jdbcTemplate.update(
                    "INSERT INTO test_users (id, name, email, phone, department) VALUES (?, ?, ?, ?, ?)",
                    i,
                    "User_" + i,
                    "user" + i + "@example.com",
                    "+86-138-" + String.format("%04d%04d", i, i),
                    i % 3 == 0 ? "Engineering" : i % 3 == 1 ? "Sales" : "Marketing"
                );
            }
            System.out.println("Test data initialized: 15 users in test_users table");
        };
    }
}
