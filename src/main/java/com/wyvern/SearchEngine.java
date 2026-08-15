package com.wyvern;

import java.util.*;

public class SearchEngine {
    private static final int BEAM_WIDTH = 4; // 1단 연산: 상위 K개 후보 수 추출
    private static final int INF = 100000;

    // 🌟 ONNX 신경망 추론 엔진 연결
    private final OnnxEvaluator nnEvaluator = OnnxEvaluator.getInstance();

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

    // [1단] Policy Filtering (ONNX 모델에서 실제 Policy 배열을 받아와 정렬)
    private List<Move> filterTopKByPolicy(Board board, List<Move> moves, int k) {
        if (moves.isEmpty()) return moves;

        // 🌟 ONNX 추론: 현재 체스판의 Policy 로짓/확률 배열 추출
        float[] policyArray = nnEvaluator.predictPolicy(board);

        List<Move> sorted = new ArrayList<>(moves);
        // ONNX Policy Score 기준 내림차순 정렬
        sorted.sort((a, b) -> Float.compare(
            nnEvaluator.getMovePolicyScore(b, policyArray),
            nnEvaluator.getMovePolicyScore(a, policyArray)
        ));

        return sorted.subList(0, Math.min(k, sorted.size()));
    }

    // [2단] 깊은 수읽기 (PGAB-BFS)
    private int deepSearch(Board board, int depth, int alpha, int beta) {
        if (depth == 0) {
            // [3단] 말단 노드에서 기물 교환 정리 (Quiescence Search)
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

    // [3단] Quiescence Search (정적 캡처 연산)
    private int quiescenceSearch(Board board, int alpha, int beta) {
        int standPat = evaluatePosition(board);
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

    // 🌟 [통합 평가 함수] ONNX 신경망 승률 판단(Value) + 전통 정적 기물 평가 하이브리드
    private int evaluatePosition(Board board) {
        // ONNX Value 출력값(-1.0 ~ +1.0)을 체스 엔진 센티폰(Centipawn, -1000 ~ +1000) 점수로 스케일링
        float nnValue = nnEvaluator.predictValue(board);
        int nnScore = (int) (nnValue * 1000);

        // 신경망의 직관(Value) 70% + 전통 기물 밸런스(evaluateStatic) 30% 보정
        return (int) (nnScore * 0.7 + board.evaluateStatic() * 0.3);
    }
}
