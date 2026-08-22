package com.wyvern;

public class Move {
    private final String uciMove;
    private final boolean isCapture;
    private final int policyScore;

    public Move(String uciMove, boolean isCapture, int policyScore) {
        this.uciMove = uciMove;
        this.isCapture = isCapture;
        this.policyScore = policyScore;
    }

    public String toUci() { return uciMove; }
    public boolean isCapture() { return isCapture; }
    public int getPolicyScore() { return policyScore; }

    // ONNX Policy 인덱스 변환 로직 (from * 64 + to % 1968)
    public int toPolicyIndex() {
        if (uciMove == null || uciMove.length() < 4) return 0;
        int from = (uciMove.charAt(0) - 'a') + (uciMove.charAt(1) - '1') * 8;
        int to = (uciMove.charAt(2) - 'a') + (uciMove.charAt(3) - '1') * 8;
        return (from * 64 + to) % 1968;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Move)) return false;
        Move other = (Move) obj;
        return uciMove.equals(other.uciMove);
    }

    @Override
    public int hashCode() {
        return uciMove.hashCode();
    }

    @Override
    public String toString() {
        return uciMove;
    }
}
