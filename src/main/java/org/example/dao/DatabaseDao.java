package org.example.dao;

import org.example.model.Test;
import org.example.model.User;

import java.sql.*;
import java.util.Collection;

public interface DatabaseDao {
    // User management
    boolean hasUser(Long chatId);
    String getUserUsername(Long chatId);
    void saveUserUsername(Long chatId, String username);
    boolean isAdmin(Long chatId);
    void addAdmin(Long chatId, String username);
    
    // Test management
    Collection<Test> getAllTests();
    void saveTest(Test test);
    void updateTest(Test test);
    void deleteTest(String testId);
    
    // Test history
    void saveTestHistory(String username, String historyEntry);
    Collection<String> getUserHistory(String username);
} 