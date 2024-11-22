package org.example.model;

public class TestMistake {
    private final int questionNumber;
    private final String studentAnswer;
    private final String correctAnswer;

    public TestMistake(int questionNumber, String studentAnswer, String correctAnswer) {
        this.questionNumber = questionNumber;
        this.studentAnswer = studentAnswer;
        this.correctAnswer = correctAnswer;
    }

    public int getQuestionNumber() {
        return questionNumber;
    }

    public String getStudentAnswer() {
        return studentAnswer;
    }

    public String getCorrectAnswer() {
        return correctAnswer;
    }

    @Override
    public String toString() {
        return String.format("Question %d: Your answer was '%s', correct answer is '%s'",
            questionNumber, studentAnswer, correctAnswer);
    }
} 