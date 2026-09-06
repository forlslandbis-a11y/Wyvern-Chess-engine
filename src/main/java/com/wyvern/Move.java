package com.wyvern;

public class Move {
    private final String uciMove;
    private final boolean isCapture;
    private final int policyScore;

    // MVV-LVA 정렬 및 Delta Pruning용 캡처 기물 가치 (센티폰 단위)
    // 실제 비트보드 연동 전까지는 캡처 여부에 따른 임시값 사용
    private final int capturedPieceValue;

    public Move(String uciMove, boolean isCapture, int policyScore) {
        this(uciMove, isCapture, policyScore, isCapture ? 100 : 0);
    }

    public Move(String uciMove, boolean isCapture, int policyScore, int capturedPieceValue) {
        this.uciMove = uciMove;
        this.isCapture = isCapture;
        this.policyScore = policyScore;
        this.capturedPieceValue = capturedPieceValue;
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

    // SearchEngine.deepSearch() / quiescenceSearch()에서 정렬 기준으로 호출하지만
    // 정의가 없어 컴파일 에러였던 메서드. 캡처 수를 우선 정렬하기 위한 값 반환
    public int getCapturePriority() {
        return isCapture ? capturedPieceValue : -1;
    }

    // quiescenceSearch()의 Delta Pruning에서 호출하지만 정의가 없어 컴파일 에러였던 메서드
    public int getCapturedPieceValue() {
        return capturedPieceValue;
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
