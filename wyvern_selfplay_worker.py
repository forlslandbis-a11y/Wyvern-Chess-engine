"""
self-play 병렬 워커 모듈.

Kaggle/Jupyter 노트북의 __main__은 실제 .py 파일이 아니라 셀 코드가
즉석 실행되는 방식이라, multiprocessing의 spawn 방식 워커가 새 인터프리터에서
__main__을 다시 import하면 여기 정의된 함수/클래스를 찾지 못해
"AttributeError: Can't get attribute '_self_play_worker' on <module '__main__'>"
에러가 남 (fork는 부모 메모리를 그대로 복사해 문제없지만 spawn은 새로 import함).

해결: self-play 병렬 처리에 필요한 모든 함수/클래스를 이 별도 .py 파일로
분리하고, 3번 셀에서는 `import wyvern_selfplay_worker as wsw` 형태로 불러와
`wsw.self_play_parallel(...)`을 호출한다. 워커 프로세스는 정상적인 모듈
import 경로로 이 파일을 찾을 수 있으므로 spawn 방식이 정상 동작한다.
"""

import os
import random
import subprocess
import threading
import queue
import time
import multiprocessing as mp

import numpy as np
import torch
import chess
import chess.polyglot


def board_to_tensor(board: chess.Board) -> torch.Tensor:
    tensor = np.zeros((12, 8, 8), dtype=np.float32)
    piece_map = {
        chess.PAWN: 0, chess.KNIGHT: 1, chess.BISHOP: 2,
        chess.ROOK: 3, chess.QUEEN: 4, chess.KING: 5
    }
    for square, piece in board.piece_map().items():
        row = 7 - (square // 8)
        col = square % 8
        channel = piece_map[piece.piece_type] + (0 if piece.color == chess.WHITE else 6)
        tensor[channel, row, col] = 1.0

    if board.turn == chess.BLACK:
        tensor = np.flip(tensor, axis=(1, 2)).copy()
    return torch.from_numpy(tensor)


def move_to_index(move: chess.Move) -> int:
    return (move.from_square * 64 + move.to_square) % 1968


class WyvernEngineProcess:
    """
    JVM을 한 번만 띄워서 계속 재사용하는 상주 프로세스 래퍼.
    UCI 표준 프로토콜(position ... / go / bestmove ...)로 통신하므로
    자바 쪽 UCI.java를 그대로 사용 - 별도 브릿지 클래스 불필요.

    별도 리더 스레드로 stdout을 큐에 흘려보내고, get_best_move()는 큐에서
    타임아웃을 두고 기다림. readline()을 직접 블로킹 호출하면 자바 쪽이
    응답을 못 줄 때 프로세스 전체가 무기한 멈출 수 있어 이 방식으로 회피.
    """
    def __init__(self, jar_path, timeout=10):
        self.jar_path = jar_path
        self.timeout = timeout
        self._consecutive_timeouts = 0
        self._start_process()

    def _start_process(self):
        self.proc = subprocess.Popen(
            ["java", "-jar", self.jar_path],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,  # line-buffered
        )
        self._lock = threading.Lock()
        self._out_queue = queue.Queue()
        self._reader_thread = threading.Thread(
            target=self._reader_loop, daemon=True
        )
        self._reader_thread.start()
        self._send("uci")
        self._read_until("uciok", timeout=self.timeout)

    def _reader_loop(self):
        try:
            for line in self.proc.stdout:
                self._out_queue.put(line.strip())
        except Exception:
            pass

    def _send(self, line: str):
        self.proc.stdin.write(line + "\n")
        self.proc.stdin.flush()

    def _read_until(self, token_prefix: str, timeout: float, max_lines: int = 200):
        lines = []
        deadline = time.time() + timeout
        for _ in range(max_lines):
            remaining = deadline - time.time()
            if remaining <= 0:
                raise TimeoutError(f"'{token_prefix}' 응답을 {timeout}초 내에 받지 못함")
            try:
                line = self._out_queue.get(timeout=remaining)
            except queue.Empty:
                raise TimeoutError(f"'{token_prefix}' 응답을 {timeout}초 내에 받지 못함")
            lines.append(line)
            if line.startswith(token_prefix):
                return lines
        return lines

    def get_best_move(self, fen: str, depth: int = 4, search_timeout: float = 15.0):
        RESTART_THRESHOLD = 5

        if self.proc.poll() is not None:
            self._restart()
            self._consecutive_timeouts = 0

        with self._lock:
            try:
                self._send(f"position fen {fen}")
                self._send(f"go depth {depth}")
                lines = self._read_until("bestmove", timeout=search_timeout)
                self._consecutive_timeouts = 0
                for line in lines:
                    if line.startswith("bestmove"):
                        parts = line.split()
                        if len(parts) >= 2 and parts[1] != "0000":
                            return chess.Move.from_uci(parts[1])
                return None
            except TimeoutError:
                self._consecutive_timeouts += 1
                if self._consecutive_timeouts >= RESTART_THRESHOLD:
                    print(f"⚠️ 연속 {self._consecutive_timeouts}회 타임아웃 - 엔진을 재시작합니다.")
                    self._restart()
                    self._consecutive_timeouts = 0
                return None
            except Exception:
                return None

    def _restart(self):
        print("⚠️ 자바 엔진 프로세스가 죽은 것으로 감지되어 재시작합니다.")
        try:
            self.proc.kill()
        except Exception:
            pass
        self._start_process()

    def close(self):
        try:
            self._send("quit")
            self.proc.wait(timeout=5)
        except Exception:
            self.proc.kill()


class _NullEngine:
    """자바 엔진 컴파일/기동 실패 시 self_play_game이 죽지 않도록 하는 폴백"""
    def get_best_move(self, fen: str, depth: int = 4):
        return None


def select_opening_move(board: chess.Board, book_path: str, temperature: float = 0.8):
    if not os.path.exists(book_path):
        return None

    try:
        with chess.polyglot.open_reader(book_path) as reader:
            entries = list(reader.find_all(board))
            if not entries:
                return None

            legal_moves = set(board.legal_moves)
            valid_entries = [e for e in entries if e.move in legal_moves]
            if not valid_entries:
                return None

            moves = [e.move for e in valid_entries]
            weights = np.array([e.weight for e in valid_entries], dtype=np.float64)

            if np.sum(weights) <= 0:
                return random.choice(moves)

            if temperature != 1.0 and temperature > 0:
                log_weights = np.log(weights + 1e-8) / temperature
                exp_weights = np.exp(log_weights - np.max(log_weights))
                probs = exp_weights / np.sum(exp_weights)
            else:
                probs = weights / np.sum(weights)

            chosen_idx = np.random.choice(len(moves), p=probs)
            return moves[chosen_idx]

    except Exception:
        return None


def self_play_game(engine, book_path, max_moves=80, depth=4):
    board = chess.Board()
    states, policies, values = [], [], []
    turns = []

    while not board.is_game_over() and len(states) < max_moves:
        legal_moves = list(board.legal_moves)
        if not legal_moves:
            break

        chosen_move = None

        if len(states) < 12 and random.random() < 0.8:
            chosen_move = select_opening_move(board, book_path=book_path, temperature=0.8)

        if chosen_move is None:
            fen = board.fen()
            best_move = engine.get_best_move(fen, depth=depth)

            if best_move and best_move in legal_moves:
                chosen_move = best_move
            else:
                chosen_move = random.choice(legal_moves)

        states.append(board_to_tensor(board))
        policies.append(move_to_index(chosen_move))
        turns.append(board.turn)

        board.push(chosen_move)

    result = board.result()
    winner = 1.0 if result == "1-0" else (-1.0 if result == "0-1" else 0.0)

    for turn in turns:
        v = winner if turn == chess.WHITE else -winner
        values.append([v])

    return states, policies, values


def _self_play_worker(args):
    """
    멀티프로세싱 워커: 자기 자신의 JVM을 한 번만 띄우고, 배정된 게임
    개수만큼 순차로 self-play를 진행해 결과를 모아 반환.
    jar_path가 None이면(자바 엔진 빌드 실패) opening book + 랜덤 수로 폴백.
    """
    jar_path, num_games, depth, book_path = args

    if jar_path:
        engine = WyvernEngineProcess(jar_path=jar_path)
    else:
        engine = _NullEngine()

    results = []
    try:
        for _ in range(num_games):
            results.append(self_play_game(engine, book_path=book_path, depth=depth))
    finally:
        if jar_path:
            engine.close()
    return results


def self_play_parallel(jar_path, total_games, book_path, depth=4, num_workers=None):
    """
    total_games개의 self-play 게임을 num_workers개의 프로세스(각자 JVM 보유)로
    나눠 병렬 실행. num_workers 기본값은 CPU 코어 수.
    jar_path가 None이면 모든 워커가 opening book + 랜덤 수로 폴백.
    """
    if num_workers is None:
        num_workers = max(1, os.cpu_count() or 4)
    num_workers = min(num_workers, total_games)

    base, remainder = divmod(total_games, num_workers)
    per_worker_counts = [base + (1 if i < remainder else 0) for i in range(num_workers)]
    per_worker_counts = [c for c in per_worker_counts if c > 0]

    worker_args = [(jar_path, count, depth, book_path) for count in per_worker_counts]

    all_results = []
    ctx = mp.get_context("spawn")
    with ctx.Pool(processes=len(worker_args)) as pool:
        for worker_games in pool.imap_unordered(_self_play_worker, worker_args):
            all_results.extend(worker_games)
    return all_results
