package com.wyvern;

import java.util.*;

public class SearchEngine {
    private static final int BEAM_WIDTH = 4;
    private static final int INF = 100000;

    private final OnnxEvaluator nnEvaluator = OnnxEvaluator.getInstance();

    // ===== 프로파일링 계측용 카운터 =====
    // stdout은 UCI 프로토콜 전용이라 오염되면 안 되므로 반드시 stderr로 출력.
    // 매 노드마다 출력하면 그 자체가 새로운 오버헤드가 되므로, 탐색 1회
    // (searchBestMove 한 번 호출) 종료 시 요약만 출력한다.
    private static final boolean PROFILE_ENABLED =
        "1".equals(System.getenv("WYVERN_PROFILE"));

    private long profNodesVisited = 0;
    private long profOnnxCalls = 0;
    private long profOnnxNanos = 0;
    private long profQuiescenceCalls = 0;

    private void resetProfileCounters() {
        profNodesVisited = 0;
        profOnnxCalls = 0;
        profOnnxNanos = 0;
        profQuiescenceCalls = 0;
    }

    private void printProfileSummary(long totalNanos) {
        if (!PROFILE_ENABLED) return;
        double totalMs = totalNanos / 1_000_000.0;
        double onnxMs = profOnnxNanos / 1_000_000.0;
        double onnxPct = totalMs > 0 ? (onnxMs / totalMs * 100.0) : 0.0;
        System.err.printf(
            "info string [PROFILE] total=%.2fms nodes=%d onnxCalls=%d onnxTime=%.2fms(%.1f%%) quiesceCalls=%d avgOnnxCall=%.3fms%n",
            totalMs, profNodesVisited, profOnnxCalls, onnxMs, onnxPct, profQuiescenceCalls,
            profOnnxCalls > 0 ? onnxMs / profOnnxCalls : 0.0
        );
        System.err.flush();
    }

    public Move searchBestMove(Board board, int depth) {
        resetProfileCounters();
        long searchStart = PROFILE_ENABLED ? System.nanoTime() : 0;

        List<Move> legalMoves = board.getLegalMoves();
        if (legalMoves.isEmpty()) return null;

        List<Move> topBeamMoves = filterTopKByPolicy(board, legalMoves, BEAM_WIDTH);

        Move bestMove = null;
        int bestScore = -INF;
        int alpha = -INF;
        int beta = INF;

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

        List<Move> discardedMoves = new ArrayList<>(legalMoves);
        discardedMoves.removeAll(topBeamMoves);

        for (Move move : discardedMoves) {
            board.makeMove(move);
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

        if (PROFILE_ENABLED) {
            long searchEnd = System.nanoTime();
            printProfileSummary(searchEnd - searchStart);
        }

        return bestMove;
    }

    private List<Move> filterTopKByPolicy(Board board, List<Move> moves, int k) {
        if (moves.size() <= k) return moves;

        long t0 = PROFILE_ENABLED ? System.nanoTime() : 0;
        float[] policyArray = nnEvaluator.predictPolicy(board);
        if (PROFILE_ENABLED) {
            profOnnxCalls++;
            profOnnxNanos += (System.nanoTime() - t0);
        }

        List<Move> sorted = new ArrayList<>(moves);
        sorted.sort((a, b) -> Float.compare(
            nnEvaluator.getMovePolicyScore(b, policyArray),
            nnEvaluator.getMovePolicyScore(a, policyArray)
        ));

        return sorted.subList(0, Math.min(k, sorted.size()));
    }

    private int deepSearch(Board board, int depth, int alpha, int beta) {
        if (PROFILE_ENABLED) profNodesVisited++;

        if (depth <= 0) {
            return quiescenceSearch(board, alpha, beta);
        }

        List<Move> moves = board.getLegalMoves();
        if (moves.isEmpty()) {
            if (board.isInCheck()) return -INF + 100;
            return 0;
        }

        moves.sort((a, b) -> Integer.compare(b.getCapturePriority(), a.getCapturePriority()));

        int moveLimit = Math.min(moves.size(), BEAM_WIDTH);

        for (int i = 0; i < moveLimit; i++) {
            Move move = moves.get(i);
            board.makeMove(move);
            int score = -deepSearch(board, depth - 1, -beta, -alpha);
            board.undoMove(move);

            if (score >= beta) return beta;
            if (score > alpha) alpha = score;
        }
        return alpha;
    }

    private int quiescenceSearch(Board board, int alpha, int beta) {
        if (PROFILE_ENABLED) {
            profNodesVisited++;
            profQuiescenceCalls++;
        }

        int standPat = evaluatePosition(board);
        if (standPat >= beta) return beta;
        if (alpha < standPat) alpha = standPat;

        List<Move> captureMoves = board.getCaptureMoves();
        captureMoves.sort((a, b) -> Integer.compare(b.getCapturePriority(), a.getCapturePriority()));

        for (Move move : captureMoves) {
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
        long t0 = PROFILE_ENABLED ? System.nanoTime() : 0;
        int nnCentipawns = nnEvaluator.predictValue(board);
        if (PROFILE_ENABLED) {
            profOnnxCalls++;
            profOnnxNanos += (System.nanoTime() - t0);
        }

        if (Math.abs(nnCentipawns) >= 12700) {
            return nnCentipawns;
        }

        return (int) (nnCentipawns * 0.7 + board.evaluateStatic() * 0.3);
    }
}
