package org.example.service;

import org.example.dao.DatabaseDao;
import org.example.model.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TestService {
    private final DatabaseDao databaseDao;
    private final Map<String, Test> testCache;

    public TestService(DatabaseDao databaseDao) {
        this.databaseDao = databaseDao;
        this.testCache = new ConcurrentHashMap<>();
        loadTests();
    }

    private void loadTests() {
        try {
            Collection<Test> tests = databaseDao.getAllTests();
            for (Test test : tests) {
                testCache.put(test.getTestId(), test);
            }
            System.out.println("Loaded " + testCache.size() + " tests from database");
        } catch (Exception e) {
            System.err.println("Error loading tests: " + e.getMessage());
        }
    }

    public Test getTest(String testId) {
        return testCache.get(testId);
    }

    public Collection<Test> getAllTests() {
        return testCache.values();
    }

    public boolean addTest(Test test) {
        try {
            // Generate test ID if not present
            if (test.getTestId() == null || test.getTestId().isEmpty()) {
                test.setTestId("test_" + System.currentTimeMillis());
            }

            // Save to database
            databaseDao.saveTest(test);
            
            // Update cache
            testCache.put(test.getTestId(), test);
            
            System.out.println("Test added successfully: " + test.getTestId());
            return true;
        } catch (Exception e) {
            System.err.println("Error adding test: " + e.getMessage());
            return false;
        }
    }

    public boolean updateTest(String testId, Test test) {
        try {
            // Verify test exists
            if (!testCache.containsKey(testId)) {
                System.err.println("Test not found: " + testId);
                return false;
            }

            // Update database
            databaseDao.updateTest(test);
            
            // Update cache
            testCache.put(testId, test);
            
            System.out.println("Test updated successfully: " + testId);
            return true;
        } catch (Exception e) {
            System.err.println("Error updating test: " + e.getMessage());
            return false;
        }
    }

    public boolean deleteTest(String testId) {
        try {
            Test test = testCache.get(testId);
            if (test == null) {
                System.err.println("Test not found: " + testId);
                return false;
            }

            // Delete PDF file
            try {
                Files.deleteIfExists(Paths.get(test.getPdfPath()));
            } catch (Exception e) {
                System.err.println("Error deleting PDF file: " + e.getMessage());
            }

            // Delete from database
            databaseDao.deleteTest(testId);
            
            // Remove from cache
            testCache.remove(testId);
            
            System.out.println("Test deleted successfully: " + testId);
            return true;
        } catch (Exception e) {
            System.err.println("Error deleting test: " + e.getMessage());
            return false;
        }
    }

    public void updateUserScore(String testId, String username, int score) {
        try {
            Test test = testCache.get(testId);
            if (test != null) {
                test.addUserScore(username, score);
                updateTest(testId, test);
            }
        } catch (Exception e) {
            System.err.println("Error updating user score: " + e.getMessage());
        }
    }
} 