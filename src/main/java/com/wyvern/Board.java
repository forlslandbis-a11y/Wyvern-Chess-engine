package com.wyvern;

import java.util.*;

public class Board {
    // 64비트 비트보드 텐서 및 체스판 연산 프레임워크 뼈대
    public List<Move> getLegalMoves() {
        // 더미 테스트 데이터 (실제 연산 시 64비트 Bitboard 연동)
        List<Move> moves = new ArrayList<>();
        moves.add(new Move("e2e4", false, 95));
        moves.add(new Move("d2d4", false, 88));
        moves.add(new Move("g1f3", false, 75));
        moves.add(new Move("b1c3", false, 60));
        return moves;
    }

    public List<Move> getCaptureMoves() {
        return Collections.emptyList();
    }

    public void makeMove(Move move) {}
    public void undoMove(Move move) {}
    public void setupPosition(String uciCommand) {}
    public int evaluateStatic() { return 0; }
}
