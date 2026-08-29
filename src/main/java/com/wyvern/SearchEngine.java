package com.wyvern;

import java.util.*;

public class SearchEngine {
    private static final int BEAM_WIDTH = 4; // 1단: Policy 기반 상위 K개 후보 선택
    private static final int INF = 100000;    // Int8 체크메이트(±12700)를 충분히 덮는 INF 범위

    private final OnnxEvaluator nnEvaluator = OnnxEvaluator.getInstance();

    /**
     * 와이번 핵심 탐색 루틴
     */
    public Move searchBestMove(Board board, int depth) {
        List<Move> legalMoves = board.getLegalMoves();
        if (legalMoves.isEmpty()) return null;

        // [1단 연산] Policy 점수로 상위 K개 Beam 필터링
        List<Move> topBeamMoves = filterTopKByPolicy(board, legalMoves, BEAM_WIDTH);

        Move bestMove = null;
        int bestScore = -INF;

        // [2단 연산] 선택된 Top-K 줄기 수직 탐색 (PGAB-BFS)
        for (Move move : topBeamMoves) {
            board.makeMove(move);
            int score = -deepSearch(board, depth - 1, -INF, INF);
            board.undoMove(move);

            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }
        }

        // [3단 연산: Safety Sweep] Policy에서 탈락했던 나머지 수 탐색
        List<Move> discardedMoves = new ArrayList<>(legalMoves);
        discardedMoves.removeAll(topBeamMoves);

        for (Move move : discardedMoves) {
            board.makeMove(move);
            int quickScore = -quiescenceSearch(board, -INF, INF);
            board.undoMove(move);

            // Int8 스케일 기준: 250 센티폰(폰 2.5개 이상 이득) 전술 감지 시 후보 교체
            if (quickScore > bestScore + 250) {
                System.out.println("info string Tactical Surprise Detected: " + move.toUci());
                bestScore = quickScore;
                bestMove = move;
            }
        }

        return bestMove;
    }

    private List<Move> filterTopKByPolicy(Board board, List<Move> moves, int k) {
        if (moves.isEmpty()) return moves;

        float[] policyArray = nnEvaluator.predictPolicy(board);

        List<Move> sorted = new ArrayList<>(moves);
        sorted.sort((a, b) -> Float.compare(
            nnEvaluator.getMovePolicyScore(b, policyArray),
            nnEvaluator.getMovePolicyScore(a, policyArray)
        ));

        return sorted.subList(0, Math.min(k, sorted.size()));
    }

    private int deepSearch(Board board, int depth, int alpha, int beta) {
        if (depth == 0) {
            return quiescenceSearch(board, alpha, beta);
        }

        List<Move> moves = filterTopKByPolicy(board, board.getLegalMoves(), BEAM_WIDTH);
        if (moves.isEmpty()) return evaluatePosition(board);

        for (Move move : moves) {
            board.makeMove(move);
            int score = -deepSearch(board, depth - 1, -beta, -alpha);
            board.undoMove(move);

            if (score >= beta) return beta; // Alpha-Beta Cutoff
            if (score > alpha) alpha = score;
        }
        return alpha;
    }

    private int quiescenceSearch(Board board, int alpha, int beta) {
        int standPat = evaluatePosition(board);
        if (standPat >= beta) return beta;
        if (alpha < standPat) alpha = standPat;

        List<Move> captureMoves = board.getCaptureMoves();
        for (Move move : captureMoves) {
            board.makeMove(move);
            int score = -quiescenceSearch(board, -beta, -alpha);
            board.undoMove(move);

            if (score >= beta) return beta;
            if (score > alpha) alpha = score;
        }
        return alpha;

    }

    /**
     * Int8 연산 스케일 반영 정적 평가 결합
     */
    private int evaluatePosition(Board board) {
        // Int8에서 계산되어 Int32 Accumulator로 스케일링된 Centipawn (-1260 ~ +1260, ±12700)
        int nnCentipawns = nnEvaluator.predictValue(board);

        // 체크메이트 수순은 가중치 섞지 않고 최우선 반환
        if (Math.abs(nnCentipawns) >= 12700) {
            return nnCentipawns;
        }

        // 신경망 연산(70%) + 기물 가치 연산(30%)
        return (int) (nnCentipawns * 0.7 + board.evaluateStatic() * 0.3);
    }
}
