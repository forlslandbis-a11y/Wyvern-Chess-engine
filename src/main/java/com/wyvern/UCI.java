package com.wyvern;

import java.util.Scanner;

public class UCI {
    private static final String ENGINE_NAME = "Wyvern";
    private static final String ENGINE_VERSION = "1.0.0";
    private static final String AUTHOR = "Wyvern Dev Team";

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
            } else if (line.equals("isready")) {
                System.out.println("readyok");
            } else if (line.startsWith("position")) {
                board.setupPosition(line);
            } else if (line.startsWith("go")) {
                // 탐색 수행 (기본 깊이 8)
                Move bestMove = searchEngine.searchBestMove(board, 8);
                System.out.println("bestmove " + (bestMove != null ? bestMove.toUci() : "0000"));
            } else if (line.equals("quit")) {
                break;
            }
        }
    }
}
