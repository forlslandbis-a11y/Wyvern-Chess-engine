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
