package org.example.dao;

import org.example.model.Test;
import org.example.model.User;

import java.sql.*;
import java.util.*;

public class DatabaseDaoImpl implements DatabaseDao {
    private final String url;
    private final String user;
    private final String password;

    public DatabaseDaoImpl(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
        initializeTables();
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private void initializeTables() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            
            // Create users table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    chat_id BIGINT PRIMARY KEY,
                    username VARCHAR(255) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )""");

            // Create admins table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS admins (
                    chat_id BIGINT PRIMARY KEY REFERENCES users(chat_id),
                    added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )""");

            // Create tests table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS tests (
                    test_id VARCHAR(255) PRIMARY KEY,
                    pdf_path VARCHAR(255) NOT NULL,
                    answers TEXT NOT NULL,
                    time_limit INTEGER NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )""");

            // Create test_scores table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS test_scores (
                    test_id VARCHAR(255) REFERENCES tests(test_id),
                    username VARCHAR(255) NOT NULL,
                    score INTEGER NOT NULL,
                    taken_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (test_id, username)
                )""");

            // Create test_history table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS test_history (
                    id SERIAL PRIMARY KEY,
                    username VARCHAR(255) NOT NULL,
                    history_entry TEXT NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )""");

            // Add default admin if not exists
            String defaultAdminChatId = "5226031192";
            String defaultAdminUsername = "IDK_What_2_SAY";
            
            if (defaultAdminChatId != null && defaultAdminUsername != null) {
                try {
                    Long adminChatId = Long.parseLong(defaultAdminChatId);
                    addAdmin(adminChatId, defaultAdminUsername);
                    System.out.println("Default admin initialized: " + defaultAdminUsername);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid DEFAULT_ADMIN_CHAT_ID format: " + defaultAdminChatId);
                }
            }

        } catch (SQLException e) {
            System.err.println("Error initializing database tables: " + e.getMessage());
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    @Override
    public boolean hasUser(Long chatId) {
        String sql = "SELECT COUNT(*) FROM users WHERE chat_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, chatId);
            ResultSet rs = stmt.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            System.err.println("Error checking user existence: " + e.getMessage());
            return false;
        }
    }

    @Override
    public String getUserUsername(Long chatId) {
        String sql = "SELECT username FROM users WHERE chat_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, chatId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("username");
            }
        } catch (SQLException e) {
            System.err.println("Error getting username: " + e.getMessage());
        }
        return null;
    }

    @Override
    public void saveUserUsername(Long chatId, String username) {
        String sql = "INSERT INTO users (chat_id, username) VALUES (?, ?) " +
                    "ON CONFLICT (chat_id) DO UPDATE SET username = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, chatId);
            stmt.setString(2, username);
            stmt.setString(3, username);
            stmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error saving username: " + e.getMessage());
        }
    }

    @Override
    public boolean isAdmin(Long chatId) {
        String sql = "SELECT COUNT(*) FROM admins WHERE chat_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, chatId);
            ResultSet rs = stmt.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            System.err.println("Error checking admin status: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void addAdmin(Long chatId, String username) {
        // First ensure user exists
        saveUserUsername(chatId, username);
        
        // Then add admin
        String sql = "INSERT INTO admins (chat_id) VALUES (?) ON CONFLICT DO NOTHING";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, chatId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error adding admin: " + e.getMessage());
        }
    }

    @Override
    public Collection<Test> getAllTests() {
        List<Test> tests = new ArrayList<>();
        String sql = "SELECT * FROM tests";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            
            while (rs.next()) {
                Test test = new Test(
                    rs.getString("test_id"),
                    rs.getString("pdf_path"),
                    rs.getString("answers"),
                    rs.getInt("time_limit")
                );
                loadTestScores(conn, test);
                tests.add(test);
            }
        } catch (SQLException e) {
            System.err.println("Error getting all tests: " + e.getMessage());
        }
        return tests;
    }

    private void loadTestScores(Connection conn, Test test) throws SQLException {
        String sql = "SELECT username, score FROM test_scores WHERE test_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, test.getTestId());
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                test.addUserScore(rs.getString("username"), rs.getInt("score"));
            }
        }
    }

    @Override
    public void saveTest(Test test) {
        String sql = "INSERT INTO tests (test_id, pdf_path, answers, time_limit) VALUES (?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, test.getTestId());
            stmt.setString(2, test.getPdfPath());
            stmt.setString(3, test.getAnswers());
            stmt.setInt(4, test.getTimeLimit());
            stmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error saving test: " + e.getMessage());
            throw new RuntimeException("Failed to save test", e);
        }
    }

    @Override
    public void updateTest(Test test) {
        Connection conn = null;
        try {
            conn = getConnection();
            conn.setAutoCommit(false);

            // Update test details
            String sql = "UPDATE tests SET pdf_path = ?, answers = ?, time_limit = ? WHERE test_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, test.getPdfPath());
                stmt.setString(2, test.getAnswers());
                stmt.setInt(3, test.getTimeLimit());
                stmt.setString(4, test.getTestId());
                stmt.executeUpdate();
            }

            // Update scores
            updateTestScores(conn, test);

            conn.commit();
        } catch (SQLException e) {
            try {
                if (conn != null) conn.rollback();
            } catch (SQLException ex) {
                System.err.println("Error rolling back transaction: " + ex.getMessage());
            }
            System.err.println("Error updating test: " + e.getMessage());
            throw new RuntimeException("Failed to update test", e);
        } finally {
            try {
                if (conn != null) {
                    conn.setAutoCommit(true);
                    conn.close();
                }
            } catch (SQLException e) {
                System.err.println("Error closing connection: " + e.getMessage());
            }
        }
    }

    private void updateTestScores(Connection conn, Test test) throws SQLException {
        // Clear existing scores
        String deleteSql = "DELETE FROM test_scores WHERE test_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(deleteSql)) {
            stmt.setString(1, test.getTestId());
            stmt.executeUpdate();
        }

        // Insert new scores
        String insertSql = "INSERT INTO test_scores (test_id, username, score) VALUES (?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
            for (Map.Entry<String, Integer> entry : test.getUserScores().entrySet()) {
                stmt.setString(1, test.getTestId());
                stmt.setString(2, entry.getKey());
                stmt.setInt(3, entry.getValue());
                stmt.executeUpdate();
            }
        }
    }

    @Override
    public void deleteTest(String testId) {
        Connection conn = null;
        try {
            conn = getConnection();
            conn.setAutoCommit(false);

            // Delete scores first (due to foreign key)
            String deleteSql = "DELETE FROM test_scores WHERE test_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(deleteSql)) {
                stmt.setString(1, testId);
                stmt.executeUpdate();
            }

            // Delete test
            String sql = "DELETE FROM tests WHERE test_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, testId);
                stmt.executeUpdate();
            }

            conn.commit();
        } catch (SQLException e) {
            try {
                if (conn != null) conn.rollback();
            } catch (SQLException ex) {
                System.err.println("Error rolling back transaction: " + ex.getMessage());
            }
            System.err.println("Error deleting test: " + e.getMessage());
            throw new RuntimeException("Failed to delete test", e);
        } finally {
            try {
                if (conn != null) {
                    conn.setAutoCommit(true);
                    conn.close();
                }
            } catch (SQLException e) {
                System.err.println("Error closing connection: " + e.getMessage());
            }
        }
    }

    @Override
    public void saveTestHistory(String username, String historyEntry) {
        String sql = "INSERT INTO test_history (username, history_entry) VALUES (?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            stmt.setString(2, historyEntry);
            stmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error saving test history: " + e.getMessage());
        }
    }

    @Override
    public Collection<String> getUserHistory(String username) {
        List<String> history = new ArrayList<>();
        String sql = "SELECT history_entry FROM test_history WHERE username = ? ORDER BY created_at DESC";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                history.add(rs.getString("history_entry"));
            }
        } catch (SQLException e) {
            System.err.println("Error getting user history: " + e.getMessage());
        }
        return history;
    }
} 