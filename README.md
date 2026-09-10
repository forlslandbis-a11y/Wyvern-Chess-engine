# Wyvern Chess Engine

A self-play chess engine built around a policy/value network and an alpha-beta search, in the spirit of AlphaZero. The system is split across two languages: Python handles training and orchestration, Java handles move search.

## How it fits together

Python and Java talk over a small UCI-like protocol (`newgame` / `move` / `go depth N`) instead of the standard `position fen ...` command. This matters more than it sounds like it should — self-play games run for dozens of moves each, and re-parsing a full FEN string on every single move adds up fast across thousands of games. Keeping the board state alive on the Java side and just pushing one move at a time cut a meaningful chunk of overhead.

The network itself is small on purpose: 2 residual blocks, 32 channels. A policy head outputs over 1968 move classes (from-square/to-square encoding), a value head predicts game outcome in [-1, 1]. Search takes the network's policy to narrow the legal moves down to a beam of 4, then runs alpha-beta with quiescence search on top so it doesn't blunder tactics right past the horizon.

Chess rules — legal move generation, check detection, FEN handling — come from `chesslib` rather than a hand-rolled implementation. Not worth reinventing.

## Training loop

1. **Optional supervised pretraining.** Before self-play starts, the network can be warm-started on real games — Lichess and Chess.com PGNs filtered to 2500+ ELO, on the order of 21M games / 1.8B positions. This isn't required but it saves a lot of wall-clock time versus starting from random weights.
2. **Self-play.** Python spawns one worker process per CPU core, each with its own JVM, and runs games in parallel. Every position gets logged to a replay buffer as (board tensor, move played, game outcome).
3. **Training step.** Policy loss is cross-entropy, value loss is MSE, plus an entropy term to keep the policy from collapsing too early. Updated weights feed back into the next round of self-play.
4. **Export.** Trained weights get converted to ONNX, run through graph preprocessing (conv/batchnorm fusion), then quantized to int8 for faster inference on CPU (AVX2/VNNI) or GPU (CUDA EP).

## Where it runs

Currently on Kaggle notebooks (4 vCPUs). Checkpoints and source get pushed to GitHub at the end of each session so training picks up where it left off rather than starting over.

That 4-core ceiling is the main bottleneck right now — self-play throughput scales with core count, and there's not much room to push it further on the current setup. Tried over-provisioning workers beyond the core count once, expecting I/O wait time to hide the extra concurrency; instead the JVMs fought each other for CPU during startup and everything got slower. So the constraint is real, not just theoretical, and a higher-core-count instance would translate directly into more self-play games per hour.

## Stack

- **Training:** PyTorch, ONNX Runtime (quantization)
- **Search engine:** Java 17, Maven, chesslib
- **Parallelism:** Python multiprocessing (spawn), one persistent JVM per worker
- **Game data:** Lichess/Chess.com PGNs, zstandard-compressed, streamed rather than fully decompressed to disk
- **Packaging:** Maven Shade (fat jar), GitHub Releases

## Current limitations

- Search depth (4 ply, beam width 4) is a speed compromise, not a target — it'll widen as the network gets stronger and doesn't need as much search to back it up.
- Self-play parallelism is capped by physical core count. More workers than cores makes things worse, not better, at least with JVM-per-worker.
