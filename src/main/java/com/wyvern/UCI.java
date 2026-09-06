package com.wyvern;

import java.util.Scanner;

public class UCI {
    private static final String ENGINE_NAME = "Wyvern";
    private static final String ENGINE_VERSION = "1.0.0";
    private static final String AUTHOR = "Wyvern Dev Team";
    private static final int DEFAULT_DEPTH = 8;

    private final Board board = new Board();
    private final SearchEngine searchEngine = new SearchEngine();

    public void start() {
        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();

            if (line.equals("uci")) {
                System.out.println("id name " + ENGINE_NAME + " " + ENGINE_VERSION);
                System.out.println("id author " + AUTHOR);
                System.out.println("uciok");
                System.out.flush();
            } else if (line.equals("isready")) {
                System.out.println("readyok");
                System.out.flush();
            } else if (line.startsWith("position")) {
                // 하위 호환: 매번 FEN 전체를 파싱해 국면을 통째로 재구성.
                // self-play처럼 같은 게임이 이어지는 상황에서는 이 재구성
                // 비용(문자열 파싱 + 보드 풀 리셋)이 매 수마다 반복되어
                // 낭비가 큼 - 새 프로토콜(newgame/move)을 대신 사용할 것.
                board.setupPosition(line);
            } else if (line.equals("newgame")) {
                // 게임 시작 시 1회만 호출 - 시작 국면으로 리셋
                board.setupPosition("position startpos");
                System.out.println("newgameok");
                System.out.flush();
            } else if (line.startsWith("move ")) {
                // 상태 유지형 진행: 직전에 결정된 수 하나만 보드에 적용.
                // FEN을 매번 재파싱하지 않고 doMove로 점진적으로 진행되므로
                // 게임이 길어질수록 "position fen ..." 방식 대비 상태 재구성
                // 비용이 사라짐.
                String uci = line.substring(5).trim();
                try {
                    Move move = new Move(uci, false, 0);
                    board.makeMove(move);
                    System.out.println("moveok");
                } catch (Exception e) {
                    System.out.println("moveerr " + e.getMessage());
                }
                System.out.flush();
            } else if (line.startsWith("go")) {
                // "go depth N" 형태로 오면 N을 사용, 없으면 기본값 사용
                int depth = parseDepth(line, DEFAULT_DEPTH);
                Move bestMove = searchEngine.searchBestMove(board, depth);
                System.out.println("bestmove " + (bestMove != null ? bestMove.toUci() : "0000"));
                System.out.flush();
            } else if (line.equals("quit")) {
                break;
            }
        }
    }

    private int parseDepth(String goLine, int fallback) {
        String[] tokens = goLine.split("\\s+");
        for (int i = 0; i < tokens.length - 1; i++) {
            if (tokens[i].equals("depth")) {
                try {
                    return Integer.parseInt(tokens[i + 1]);
                } catch (NumberFormatException e) {
                    return fallback;
                }
            }
        }
        return fallback;
    }
}
