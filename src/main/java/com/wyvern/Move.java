package com.wyvern;

import java.util.*;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;

/**
 * chesslib(com.github.bhlangonijr.chesslib.Board)를 감싸는 wrapper.
 * 기존 SearchEngine/UCI/OnnxEvaluator가 참조하는 인터페이스(getLegalMoves,
 * makeMove, undoMove, setupPosition 등)는 그대로 유지하고, 내부 구현만
 * 더미 스텁에서 실제 체스 규칙(합법수 생성, 체크 판정, make/undo)으로 교체.
 *
 * 이전 버전은 getLegalMoves()가 4개 수를 하드코딩 반환하고 makeMove/undoMove가
 * 빈 함수였기 때문에, 이 클래스를 쓰는 self-play가 실제로는 매번 같은 시작
 * 국면에서 4개 중 하나를 고르는 것에 불과했음 - 이 버전으로 그 문제가 해결됨.
 */
public class Board {
    private final com.github.bhlangonijr.chesslib.Board inner;

    public Board() {
        this.inner = new com.github.bhlangonijr.chesslib.Board();
    }

    public List<Move> getLegalMoves() {
        // chesslib 1.3.7의 generateLegalMoves()는 MoveList가 아니라
        // List<com.github.bhlangonijr.chesslib.move.Move>를 반환함
        // (버전 간 API 차이 - 이전 버전은 MoveList로 잘못 가정했었음)
        List<com.github.bhlangonijr.chesslib.move.Move> legalMoves = MoveGenerator.generateLegalMoves(inner);
        List<Move> result = new ArrayList<>(legalMoves.size());
        for (com.github.bhlangonijr.chesslib.move.Move m : legalMoves) {
            boolean capture = inner.getPiece(m.getTo()) != com.github.bhlangonijr.chesslib.Piece.NONE;
            int capturedValue = capture ? pieceValue(inner.getPiece(m.getTo())) : 0;
            result.add(new Move(moveToUci(m), capture, 0, capturedValue));
        }
        return result;
    }

    public List<Move> getCaptureMoves() {
        List<Move> all = getLegalMoves();
        List<Move> captures = new ArrayList<>();
        for (Move mv : all) {
            if (mv.isCapture()) captures.add(mv);
        }
        return captures;
    }

    public void makeMove(Move move) {
        com.github.bhlangonijr.chesslib.move.Move chessLibMove = uciToMove(move.toUci());
        inner.doMove(chessLibMove);
    }

    public void undoMove(Move move) {
        // chesslib는 자체 이력 스택으로 undo하므로 어떤 수였는지는 필요 없지만
        // 인터페이스 호환을 위해 파라미터는 유지
        inner.undoMove();
    }

    public void setupPosition(String uciCommand) {
        // "position startpos [moves ...]" 또는 "position fen <FEN> [moves ...]" 파싱
        String[] tokens = uciCommand.trim().split("\\s+");
        int idx = 0;
        if (tokens.length == 0) return;

        if (!tokens[0].equals("position")) return;
        idx = 1;

        if (idx < tokens.length && tokens[idx].equals("startpos")) {
            inner.loadFromFen(com.github.bhlangonijr.chesslib.Constants.startStandardFENPosition);
            idx++;
        } else if (idx < tokens.length && tokens[idx].equals("fen")) {
            idx++;
            StringBuilder fenBuilder = new StringBuilder();
            // FEN은 공백 포함 6필드이고 그 뒤에 "moves"가 올 수 있음
            while (idx < tokens.length && !tokens[idx].equals("moves")) {
                if (fenBuilder.length() > 0) fenBuilder.append(' ');
                fenBuilder.append(tokens[idx]);
                idx++;
            }
            inner.loadFromFen(fenBuilder.toString());
        }

        if (idx < tokens.length && tokens[idx].equals("moves")) {
            idx++;
            while (idx < tokens.length) {
                String uci = tokens[idx];
                try {
                    com.github.bhlangonijr.chesslib.move.Move mv = uciToMove(uci);
                    inner.doMove(mv);
                } catch (Exception ignored) {
                    // 잘못된 수 문자열은 무시하고 계속 진행
                }
                idx++;
            }
        }
    }

    public boolean isInCheck() {
        return inner.isKingAttacked();
    }

    public int evaluateStatic() {
        // 간단한 기물 가치 합산 (Int8 신경망 평가 실패 시 폴백용)
        int score = 0;
        for (Square sq : Square.values()) {
            if (sq == Square.NONE) continue;
            com.github.bhlangonijr.chesslib.Piece piece = inner.getPiece(sq);
            if (piece == com.github.bhlangonijr.chesslib.Piece.NONE) continue;
            int value = pieceValue(piece);
            score += (piece.getPieceSide() == Side.WHITE) ? value : -value;
        }
        return (inner.getSideToMove() == Side.WHITE) ? score : -score;
    }

    /** 학습/추론 파이프라인이 텐서 변환 시 참조할 수 있도록 내부 chesslib 보드 노출 */
    public com.github.bhlangonijr.chesslib.Board raw() {
        return inner;
    }

    private static int pieceValue(com.github.bhlangonijr.chesslib.Piece piece) {
        switch (piece.getPieceType()) {
            case PAWN: return 100;
            case KNIGHT: return 320;
            case BISHOP: return 330;
            case ROOK: return 500;
            case QUEEN: return 900;
            case KING: return 0; // 킹은 캡처 대상이 아니므로 평가에서 제외
            default: return 0;
        }
    }

    private static String moveToUci(com.github.bhlangonijr.chesslib.move.Move m) {
        String uci = m.getFrom().toString().toLowerCase() + m.getTo().toString().toLowerCase();
        if (m.getPromotion() != null && m.getPromotion() != com.github.bhlangonijr.chesslib.Piece.NONE) {
            char promoChar = m.getPromotion().getSanSymbol().toLowerCase().charAt(0);
            uci += promoChar;
        }
        return uci;
    }

    private com.github.bhlangonijr.chesslib.move.Move uciToMove(String uci) {
        if (uci == null || uci.length() < 4) {
            throw new IllegalArgumentException("잘못된 UCI 수 문자열: " + uci);
        }
        Square from = Square.fromValue(uci.substring(0, 2).toUpperCase());
        Square to = Square.fromValue(uci.substring(2, 4).toUpperCase());

        com.github.bhlangonijr.chesslib.Piece promotion = com.github.bhlangonijr.chesslib.Piece.NONE;
        if (uci.length() >= 5) {
            Side sideToMove = inner.getSideToMove();
            char promoChar = Character.toLowerCase(uci.charAt(4));
            promotion = resolvePromotionPiece(promoChar, sideToMove);
        }

        return new com.github.bhlangonijr.chesslib.move.Move(from, to, promotion);
    }

    private static com.github.bhlangonijr.chesslib.Piece resolvePromotionPiece(char c, Side side) {
        boolean white = (side == Side.WHITE);
        switch (c) {
            case 'q': return white ? com.github.bhlangonijr.chesslib.Piece.WHITE_QUEEN : com.github.bhlangonijr.chesslib.Piece.BLACK_QUEEN;
            case 'r': return white ? com.github.bhlangonijr.chesslib.Piece.WHITE_ROOK : com.github.bhlangonijr.chesslib.Piece.BLACK_ROOK;
            case 'b': return white ? com.github.bhlangonijr.chesslib.Piece.WHITE_BISHOP : com.github.bhlangonijr.chesslib.Piece.BLACK_BISHOP;
            case 'n': return white ? com.github.bhlangonijr.chesslib.Piece.WHITE_KNIGHT : com.github.bhlangonijr.chesslib.Piece.BLACK_KNIGHT;
            default: return com.github.bhlangonijr.chesslib.Piece.NONE;
        }
    }
}
