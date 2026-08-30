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
                board.setupPosition(line);
            } else if (line.startsWith("go")) {
                // "go depth N" 형태로 오면 N을 사용, 없으면 기본값 사용
                // (Kaggle self-play가 매 수마다 depth를 지정해서 보내므로 필요)
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
