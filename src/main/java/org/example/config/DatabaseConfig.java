package org.example.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public class DatabaseConfig {
    private static HikariDataSource dataSource;

    static {
        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:postgresql://localhost:5432/telegram_bot_db");
            config.setUsername("postgres");
            config.setPassword("3119090Kad");
            config.setMaximumPoolSize(5);
            
            // PostgreSQL specific settings
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            
            // Basic settings
            config.setAutoCommit(true);
            config.setMinimumIdle(1);
            config.setIdleTimeout(300000);
            config.setMaxLifetime(600000);
            config.setConnectionTimeout(20000);
            
            // Enable logging
            config.setPoolName("PostgreSQLBotPool");
            
            dataSource = new HikariDataSource(config);
            
            // Test connection immediately
            try (Connection conn = dataSource.getConnection()) {
                System.out.println("PostgreSQL database connection pool initialized successfully!");
            }
        } catch (SQLException e) {
            System.err.println("Error initializing PostgreSQL connection pool: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize database connection pool", e);
        }
    }

    public static DataSource getDataSource() {
        if (dataSource == null || dataSource.isClosed()) {
            throw new RuntimeException("Database connection pool is not initialized or has been closed!");
        }
        return dataSource;
    }

    public static Connection getConnection() throws SQLException {
        return getDataSource().getConnection();
    }

    public static boolean checkConnection() {
        try (Connection conn = getConnection()) {
            if (conn.isValid(5)) {  // timeout of 5 seconds
                System.out.println("Database connection is valid");
                return true;
            }
            System.err.println("Database connection is invalid");
            return false;
        } catch (SQLException e) {
            System.err.println("Database connection check failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public static void closePool() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
} 