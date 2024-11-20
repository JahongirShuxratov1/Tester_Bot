package org.example.model;

import java.util.HashMap;
import java.util.Map;

public class Test {
    private String testId;
    private String pdfPath;
    private String answers;
    private int timeLimit;
    private Map<String, Integer> userScores;

    public Test(String testId, String pdfPath, String answers, int timeLimit) {
        this.testId = testId.isEmpty() ? "test_" + System.currentTimeMillis() : testId;
        if (!this.testId.startsWith("test_")) {
            this.testId = "test_" + this.testId;
        }
        this.pdfPath = pdfPath;
        this.answers = answers;
        this.timeLimit = timeLimit;
        this.userScores = new HashMap<>();
    }

    public String getTestId() { return testId; }
    public void setTestId(String testId) { this.testId = testId; }
    
    public String getPdfPath() { return pdfPath; }
    public void setPdfPath(String pdfPath) { this.pdfPath = pdfPath; }
    
    public String getAnswers() { return answers; }
    public void setAnswers(String answers) { this.answers = answers; }
    
    public int getTimeLimit() { return timeLimit; }
    public void setTimeLimit(int timeLimit) { this.timeLimit = timeLimit; }
    
    public Map<String, Integer> getUserScores() { return userScores; }
    public void setUserScores(Map<String, Integer> userScores) { this.userScores = userScores; }
    
    public void addUserScore(String username, int score) {
        userScores.put(username, score);
    }
} 