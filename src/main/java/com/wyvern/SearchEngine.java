package com.wyvern;

import java.util.*;

public class SearchEngine {
    private static final int BEAM_WIDTH = 4; // 1단 연산: 상위 K개 후보 수 추출
    private static final int INF = 100000;

    /**
     * 와이번 핵심 메인 탐색 엔트리 포인트
     */
    public Move searchBestMove(Board board, int depth) {
        List<Move> legalMoves = board.getLegalMoves();
        if (legalMoves.isEmpty()) return null;

        // [1단 연산] 신경망 Policy 점수로 상위 K개 수(Beam) 필터링
        List<Move> topBeamMoves = filterTopKByPolicy(board, legalMoves, BEAM_WIDTH);

        Move bestMove = null;
        int bestScore = -INF;

        // [2단 연산] 선택된 Top-K 후보 줄기 수직 파고들기 (PGAB-BFS)
        for (Move move : topBeamMoves) {
            board.makeMove(move);
            int score = -deepSearch(board, depth - 1, -INF, INF);
            board.undoMove(move);

            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }
        }

        // [방점: Safety Sweep] 버려진 나머지 수들 전수 훑어보기 (지뢰/대박 수 체크)
        List<Move> discardedMoves = new ArrayList<>(legalMoves);
        discardedMoves.removeAll(topBeamMoves);

        for (Move move : discardedMoves) {
            board.makeMove(move);
            // 얕고 빠르게 전술적 역전 가능성 스캔 (Depth 1~2 수준)
            int quickScore = -quiescenceSearch(board, -INF, INF);
            board.undoMove(move);

            // 신경망이 놓친 미친 전술(퀸 잡기, 체크메이트 등) 발견 시 역전
            if (quickScore > bestScore + 300) { // 300 = 폰 3개 이상급 전술 이득
                System.out.println("info string Tactical Surprise Detected: " + move.toUci());
                bestScore = quickScore;
                bestMove = move;
            }
        }

        return bestMove;
    }

    // [1단] Policy Filtering (신경망 점수 기준 정렬 후 상위 K개 추려내기)
    private List<Move> filterTopKByPolicy(Board board, List<Move> moves, int k) {
        List<Move> sorted = new ArrayList<>(moves);
        // Policy Score 기준 내림차순 정렬
        sorted.sort((a, b) -> Integer.compare(b.getPolicyScore(), a.getPolicyScore()));
        return sorted.subList(0, Math.min(k, sorted.size()));
    }

    // [2단] 깊은 수읽기 (PGAB-BFS)
    private int deepSearch(Board board, int depth, int alpha, int beta) {
        if (depth == 0) {
            // [3단] 말단 노드에서 기물 교환 정리 (Quiescence Search)
            return quiescenceSearch(board, alpha, beta);
        }

        List<Move> moves = filterTopKByPolicy(board, board.getLegalMoves(), BEAM_WIDTH);
        if (moves.isEmpty()) return board.evaluateStatic();

        for (Move move : moves) {
            board.makeMove(move);
            int score = -deepSearch(board, depth - 1, -beta, -alpha);
            board.undoMove(move);

            if (score >= beta) return beta; // Alpha-Beta Cutoff
            if (score > alpha) alpha = score;
        }
        return alpha;
    }

    // [3단] Quiescence Search (정적 캡처 연산)
    private int quiescenceSearch(Board board, int alpha, int beta) {
        int standPat = board.evaluateStatic();
        if (standPat >= beta) return beta;
        if (alpha < standPat) alpha = standPat;

        // 폰/기물 잡기(Captures) 수만 계속 연산해서 난전 정리
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
}
