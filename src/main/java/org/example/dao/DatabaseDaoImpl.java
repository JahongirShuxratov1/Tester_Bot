package org.example.dao;

import org.example.model.Test;
import org.example.model.User;
import org.example.model.TestMistake;

import java.sql.*;
import java.util.*;
import java.io.IOException;

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

            // Add broadcast_messages table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS broadcast_messages (
                    id SERIAL PRIMARY KEY,
                    admin_chat_id BIGINT REFERENCES users(chat_id),
                    message_content TEXT NOT NULL,
                    sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )""");

            // Add test_answers table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS test_answers (
                    id SERIAL PRIMARY KEY,
                    test_id VARCHAR(255) REFERENCES tests(test_id),
                    username VARCHAR(255) NOT NULL,
                    question_number INTEGER NOT NULL,
                    student_answer VARCHAR(1) NOT NULL,
                    is_correct BOOLEAN NOT NULL,
                    submitted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(test_id, username, question_number)
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
        String sql = "SELECT * FROM tests ORDER BY created_at ASC";
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
        
        cleanupOrphanedPdfFilesImmediately();
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
        String sql = "INSERT INTO tests (test_id, pdf_path, answers, time_limit, message_id) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, test.getTestId());
            stmt.setString(2, test.getPdfPath());
            stmt.setString(3, test.getAnswers());
            stmt.setInt(4, test.getTimeLimit());
            stmt.setString(5, test.getMessageId());
            stmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error saving test: " + e.getMessage());
            throw new RuntimeException("Failed to save test", e);
        }
        
        cleanupOrphanedPdfFilesImmediately();
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
        
        cleanupOrphanedPdfFilesImmediately();
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

            // First, get the PDF path before deleting the test
            String pdfPath = null;
            String getPdfPathSql = "SELECT pdf_path FROM tests WHERE test_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(getPdfPathSql)) {
                stmt.setString(1, testId);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    pdfPath = rs.getString("pdf_path");
                }
            }

            // Get all usernames who took this test
            Set<String> usersWithHistory = new HashSet<>();
            String getUsersSql = "SELECT DISTINCT username FROM test_scores WHERE test_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(getUsersSql)) {
                stmt.setString(1, testId);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    usersWithHistory.add(rs.getString("username"));
                }
            }

            // Delete test history entries for this test
            if (!usersWithHistory.isEmpty()) {
                String deleteHistorySql = "DELETE FROM test_history WHERE username = ? AND history_entry LIKE ?";
                try (PreparedStatement stmt = conn.prepareStatement(deleteHistorySql)) {
                    for (String username : usersWithHistory) {
                        stmt.setString(1, username);
                        stmt.setString(2, "%" + testId + "%");
                        stmt.executeUpdate();
                    }
                }
            }

            // Delete scores
            String deleteScoresSql = "DELETE FROM test_scores WHERE test_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(deleteScoresSql)) {
                stmt.setString(1, testId);
                stmt.executeUpdate();
            }

            // Delete test
            String deleteTestSql = "DELETE FROM tests WHERE test_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(deleteTestSql)) {
                stmt.setString(1, testId);
                stmt.executeUpdate();
            }

            conn.commit();

            // Delete the PDF file after successful database transaction
            if (pdfPath != null) {
                try {
                    java.nio.file.Path path = java.nio.file.Paths.get(pdfPath);
                    java.nio.file.Files.deleteIfExists(path);
                } catch (IOException e) {
                    System.err.println("Warning: Could not delete PDF file at " + pdfPath + ": " + e.getMessage());
                    // We don't throw an exception here because the database operation was successful
                    // The PDF file can be cleaned up manually if needed
                }
            }

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

    /**
     * Checks for orphaned PDF files in the tests directory
     * @return List of paths to orphaned PDF files
     */
    public List<String> findOrphanedPdfFiles() {
        List<String> orphanedFiles = new ArrayList<>();
        String testsDirectory = "C:\\Users\\Microsoft\\IdeaProjects\\TelegramBot\\tests";
        
        try {
            // First, get all PDF paths from database
            Set<String> databasePdfPaths = new HashSet<>();
            String sql = "SELECT pdf_path FROM tests";
            
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                
                while (rs.next()) {
                    String path = rs.getString("pdf_path");
                    databasePdfPaths.add(path.replace("/", "\\"));  // Normalize path separators
                    System.out.println("Database path: " + path);  // Debug print
                }
            }

            // Check all files in tests directory
            java.nio.file.Path directory = java.nio.file.Paths.get(testsDirectory);
            if (java.nio.file.Files.exists(directory)) {
                java.nio.file.Files.walk(directory)
                    .filter(path -> !java.nio.file.Files.isDirectory(path)) // Skip directories
                    .forEach(path -> {
                        String absolutePath = path.toAbsolutePath().toString();
                        System.out.println("Checking file: " + absolutePath);  // Debug print
                        if (!databasePdfPaths.contains(absolutePath)) {
                            System.out.println("Found orphaned file: " + absolutePath);  // Debug print
                            orphanedFiles.add(absolutePath);
                        }
                    });
            }

        } catch (SQLException e) {
            System.err.println("Error checking database for files: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("Error checking tests directory: " + e.getMessage());
        }

        return orphanedFiles;
    }

    /**
     * Deletes orphaned files from the tests directory
     * @return Number of files deleted
     */
    public int cleanupOrphanedFiles() {
        List<String> orphanedFiles = findOrphanedPdfFiles();
        System.out.println("Found " + orphanedFiles.size() + " orphaned files");  // Debug print
        int deletedCount = 0;

        for (String filePath : orphanedFiles) {
            try {
                java.nio.file.Path path = java.nio.file.Paths.get(filePath);
                System.out.println("Attempting to delete: " + filePath);  // Debug print
                if (java.nio.file.Files.deleteIfExists(path)) {
                    deletedCount++;
                    System.out.println("Successfully deleted: " + filePath);
                } else {
                    System.out.println("File not found or could not be deleted: " + filePath);
                }
            } catch (IOException e) {
                System.err.println("Failed to delete orphaned file " + filePath + ": " + e.getMessage());
            }
        }

        return deletedCount;
    }

    /**
     * Checks and removes any PDF files that don't have corresponding test entries
     * This method is automatically called after critical operations
     */
    private void cleanupOrphanedPdfFilesImmediately() {
        try {
            // Get all PDF paths from database
            Set<String> databasePdfPaths = new HashSet<>();
            String sql = "SELECT pdf_path FROM tests";
            
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String pdfPath = rs.getString("pdf_path");
                    if (pdfPath != null) {
                        databasePdfPaths.add(pdfPath);
                    }
                }
            }

            // Check each PDF path in database if file exists
            for (String pdfPath : databasePdfPaths) {
                java.nio.file.Path path = java.nio.file.Paths.get(pdfPath);
                if (!java.nio.file.Files.exists(path)) {
                    // If PDF file doesn't exist, remove the test from database
                    deleteTestByPdfPath(pdfPath);
                }
            }

        } catch (SQLException e) {
            System.err.println("Error during PDF cleanup: " + e.getMessage());
        }
    }

    /**
     * Deletes a test entry by its PDF path
     */
    private void deleteTestByPdfPath(String pdfPath) {
        Connection conn = null;
        try {
            conn = getConnection();
            conn.setAutoCommit(false);

            // First get the test_id
            String getTestIdSql = "SELECT test_id FROM tests WHERE pdf_path = ?";
            String testId = null;
            
            try (PreparedStatement stmt = conn.prepareStatement(getTestIdSql)) {
                stmt.setString(1, pdfPath);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    testId = rs.getString("test_id");
                }
            }

            if (testId != null) {
                // Delete related records
                String deleteScoresSql = "DELETE FROM test_scores WHERE test_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(deleteScoresSql)) {
                    stmt.setString(1, testId);
                    stmt.executeUpdate();
                }

                // Delete test entry
                String deleteTestSql = "DELETE FROM tests WHERE test_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(deleteTestSql)) {
                    stmt.setString(1, testId);
                    stmt.executeUpdate();
                }

                System.out.println("Removed test record for missing PDF: " + pdfPath);
            }

            conn.commit();
        } catch (SQLException e) {
            try {
                if (conn != null) conn.rollback();
            } catch (SQLException ex) {
                System.err.println("Error rolling back transaction: " + ex.getMessage());
            }
            System.err.println("Error deleting test by PDF path: " + e.getMessage());
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

    /**
     * Gets all registered users from the database
     * @return Collection of chat IDs for all users
     */
    @Override
    public Collection<Long> getAllUserChatIds() {
        List<Long> chatIds = new ArrayList<>();
        String sql = "SELECT chat_id FROM users";
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            
            while (rs.next()) {
                chatIds.add(rs.getLong("chat_id"));
            }
        } catch (SQLException e) {
            System.err.println("Error getting user chat IDs: " + e.getMessage());
        }
        
        return chatIds;
    }

    /**
     * Saves a broadcast message to the database for tracking
     * @param adminChatId The admin who sent the message
     * @param message The message content
     */
    @Override
    public void saveBroadcastMessage(Long adminChatId, String message) {
        String sql = "INSERT INTO broadcast_messages (admin_chat_id, message_content) VALUES (?, ?)";
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setLong(1, adminChatId);
            stmt.setString(2, message);
            stmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error saving broadcast message: " + e.getMessage());
        }
    }

    @Override
    public Test getTestByMessageId(String messageId) {
        String sql = "SELECT * FROM tests WHERE message_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, messageId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                Test test = new Test(
                    rs.getString("test_id"),
                    rs.getString("pdf_path"),
                    rs.getString("answers"),
                    rs.getInt("time_limit")
                );
                loadTestScores(conn, test);
                return test;
            }
        } catch (SQLException e) {
            System.err.println("Error getting test by message ID: " + e.getMessage());
        }
        return null;
    }

    // Add method to save detailed test results
    public void saveTestResults(String testId, String username, Map<Integer, String> studentAnswers, Map<Integer, Boolean> correctness) {
        String sql = "INSERT INTO test_answers (test_id, username, question_number, student_answer, is_correct) " +
                    "VALUES (?, ?, ?, ?, ?) ON CONFLICT (test_id, username, question_number) DO UPDATE SET " +
                    "student_answer = EXCLUDED.student_answer, is_correct = EXCLUDED.is_correct";
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            for (Map.Entry<Integer, String> entry : studentAnswers.entrySet()) {
                int questionNumber = entry.getKey();
                String studentAnswer = entry.getValue();
                boolean isCorrect = correctness.get(questionNumber);
                
                stmt.setString(1, testId);
                stmt.setString(2, username);
                stmt.setInt(3, questionNumber);
                stmt.setString(4, studentAnswer);
                stmt.setBoolean(5, isCorrect);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            System.err.println("Error saving test results: " + e.getMessage());
        }
    }

    // Add method to get incorrect answers for feedback
    public List<TestMistake> getIncorrectAnswers(String testId, String username) {
        List<TestMistake> mistakes = new ArrayList<>();
        String sql = """
            SELECT ta.question_number, ta.student_answer, t.answers 
            FROM test_answers ta 
            JOIN tests t ON ta.test_id = t.test_id 
            WHERE ta.test_id = ? AND ta.username = ? AND ta.is_correct = false 
            ORDER BY ta.question_number""";
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, testId);
            stmt.setString(2, username);
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                int questionNumber = rs.getInt("question_number");
                String studentAnswer = rs.getString("student_answer");
                String correctAnswer = rs.getString("answers").split(",")[questionNumber - 1].trim();
                
                mistakes.add(new TestMistake(questionNumber, studentAnswer, correctAnswer));
            }
        } catch (SQLException e) {
            System.err.println("Error getting incorrect answers: " + e.getMessage());
        }
        
        return mistakes;
    }
} 