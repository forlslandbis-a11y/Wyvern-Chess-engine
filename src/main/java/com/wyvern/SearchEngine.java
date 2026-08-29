package com.wyvern;

import java.util.*;

public class SearchEngine {
    private static final int BEAM_WIDTH = 4; 
    private static final int INF = 100000;    

    private final OnnxEvaluator nnEvaluator = OnnxEvaluator.getInstance();

    public Move searchBestMove(Board board, int depth) {
        List<Move> legalMoves = board.getLegalMoves();
        if (legalMoves.isEmpty()) return null;

        // [1단 연산] 루트 노드에서는 Policy로 상위 K개 후보 필터링
        List<Move> topBeamMoves = filterTopKByPolicy(board, legalMoves, BEAM_WIDTH);

        Move bestMove = null;
        int bestScore = -INF;
        int alpha = -INF;
        int beta = INF;

        // [2단 연산] 선택된 Top-K 핵심 수 Alpha-Beta 수직 탐색
        for (Move move : topBeamMoves) {
            board.makeMove(move);
            int score = -deepSearch(board, depth - 1, -beta, -alpha);
            board.undoMove(move);

            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }
            if (score > alpha) {
                alpha = score;
            }
        }

        // [3단 연산: Safety Sweep] Policy 탈락 수 검증 (Alpha-Beta 윈도우 정상 전달)
        List<Move> discardedMoves = new ArrayList<>(legalMoves);
        discardedMoves.removeAll(topBeamMoves);

        for (Move move : discardedMoves) {
            board.makeMove(move);
            // 전체 윈도우 대신 현재 알파 기준으로 컷오프 여부만 빠르게 확인
            int quickScore = -quiescenceSearch(board, -alpha - 1, -alpha);
            board.undoMove(move);

            if (quickScore > alpha) {
                System.out.println("info string Tactical Surprise Detected: " + move.toUci());
                board.makeMove(move);
                int fullScore = -deepSearch(board, depth - 1, -beta, -alpha);
                board.undoMove(move);

                if (fullScore > bestScore) {
                    bestScore = fullScore;
                    bestMove = move;
                    alpha = fullScore;
                }
            }
        }

        return bestMove;
    }

    private List<Move> filterTopKByPolicy(Board board, List<Move> moves, int k) {
        if (moves.size() <= k) return moves;

        float[] policyArray = nnEvaluator.predictPolicy(board);

        List<Move> sorted = new ArrayList<>(moves);
        sorted.sort((a, b) -> Float.compare(
            nnEvaluator.getMovePolicyScore(b, policyArray),
            nnEvaluator.getMovePolicyScore(a, policyArray)
        ));

        return sorted.subList(0, Math.min(k, sorted.size()));
    }

    private int deepSearch(Board board, int depth, int alpha, int beta) {
        if (depth <= 0) {
            return quiescenceSearch(board, alpha, beta);
        }

        List<Move> moves = board.getLegalMoves();
        if (moves.isEmpty()) {
            if (board.isInCheck()) return -INF + 100; // 체크메이트
            return 0; // 스테일메이트 (무승부)
        }

        // 💡 깊은 노드에서는 ONNX 재호출 대신 기물 캡처 우선(MVV-LVA) 단순 정렬로 대체하여 속도 10배 향상
        moves.sort((a, b) -> Integer.compare(b.getCapturePriority(), a.getCapturePriority()));

        // 깊이가 얕아질수록 Beam Width를 조금씩 좁힘
        int moveLimit = Math.min(moves.size(), BEAM_WIDTH);

        for (int i = 0; i < moveLimit; i++) {
            Move move = moves.get(i);
            board.makeMove(move);
            int score = -deepSearch(board, depth - 1, -beta, -alpha);
            board.undoMove(move);

            if (score >= beta) return beta; // Cutoff
            if (score > alpha) alpha = score;
        }
        return alpha;
    }

    private int quiescenceSearch(Board board, int alpha, int beta) {
        int standPat = evaluatePosition(board);
        if (standPat >= beta) return beta;
        if (alpha < standPat) alpha = standPat;

        List<Move> captureMoves = board.getCaptureMoves();
        // 캡처 수 MVV-LVA 정렬
        captureMoves.sort((a, b) -> Integer.compare(b.getCapturePriority(), a.getCapturePriority()));

        for (Move move : captureMoves) {
            // Delta Pruning: 잡아서 얻는 이득으로도 alpha를 못 넘기면 가지치기
            if (standPat + move.getCapturedPieceValue() + 200 < alpha) continue;

            board.makeMove(move);
            int score = -quiescenceSearch(board, -beta, -alpha);
            board.undoMove(move);

            if (score >= beta) return beta;
            if (score > alpha) alpha = score;
        }
        return alpha;
    }

    private int evaluatePosition(Board board) {
        int nnCentipawns = nnEvaluator.predictValue(board);

        if (Math.abs(nnCentipawns) >= 12700) {
            return nnCentipawns;
        }

        return (int) (nnCentipawns * 0.7 + board.evaluateStatic() * 0.3);
    }
}
