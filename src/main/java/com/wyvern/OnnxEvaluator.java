package com.wyvern;

import ai.onnxruntime.*;
import java.io.InputStream;
import java.util.*;

public class OnnxEvaluator {
    private static OnnxEvaluator instance;
    private OrtEnvironment env;
    private OrtSession session;

    // Int32 Accumulator -> 센티폰(Centipawn) 변환 스케일 팩터
    // 1 Int8 Unit = 10 센티폰 (0.1 Pawn)
    private static final int INT8_SCALE_FACTOR = 10;
    private static final int MATE_SCORE = 12700; // Int8 +127 -> Checkmate

    private OnnxEvaluator() {
        try {
            env = OrtEnvironment.getEnvironment();
            
            // Int8 양자화 모델을 최우선 로드, 없으면 기본 모델 폴백
            InputStream modelStream = getClass().getResourceAsStream("/wyvern_int8.onnx");
            if (modelStream == null) {
                modelStream = getClass().getResourceAsStream("/wyvern.onnx");
            }
            
            if (modelStream == null) {
                throw new RuntimeException("ONNX 모델 파일(wyvern_int8.onnx 또는 wyvern.onnx)을 찾아올 수 없습니다.");
            }
            
            byte[] modelBytes = modelStream.readAllBytes();
            session = env.createSession(modelBytes, new OrtSession.SessionOptions());
            System.out.println("✅ Wyvern Int8 지원 ONNX 모델 로드 성공!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static synchronized OnnxEvaluator getInstance() {
        if (instance == null) {
            instance = new OnnxEvaluator();
        }
        return instance;
    }

    public float[] predictPolicy(Board board) {
        try {
            float[][][][] inputTensorData = boardToTensor(board);
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, inputTensorData);
            
            try (OrtSession.Result results = session.run(Collections.singletonMap("input", inputTensor))) {
                float[][] policyOutput = (float[][]) results.get("policy").get().getValue();
                return policyOutput[0];
            }
        } catch (Exception e) {
            return new float[1968];
        }
    }

    /**
     * Int32 Accumulator 기반 Value 추론 및 센티폰 단위 디스케일링
     */
    public int predictValue(Board board) {
        try {
            float[][][][] inputTensorData = boardToTensor(board);
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, inputTensorData);

            try (OrtSession.Result results = session.run(Collections.singletonMap("input", inputTensor))) {
                Object rawValue = results.get("value").get().getValue();
                
                // Int32 Accumulator 연산 보정
                int accumulator = 0;
                
                if (rawValue instanceof float[][]) {
                    float val = ((float[][]) rawValue)[0][0];
                    // -1.0 ~ +1.0 출력을 Int8 스케일(-126 ~ +126) 레지스터 영역으로 변환
                    accumulator = Math.round(val * 126.0f);
                } else if (rawValue instanceof byte[][]) {
                    // Int8 직접 출력일 경우 Int32 Accumulator에 바로 승격
                    accumulator = (int) ((byte[][]) rawValue)[0][0];
                }

                // 1. 강제 체크메이트 특수 임계값 처리 (+127 / -127)
                if (accumulator >= 127) return MATE_SCORE;
                if (accumulator <= -127) return -MATE_SCORE;

                // 2. Int32 Accumulator -> Centipawns De-scaling (-1260 ~ +1260 cp)
                int centipawns = accumulator * INT8_SCALE_FACTOR;
                return Math.max(-1260, Math.min(1260, centipawns));
            }
        } catch (Exception e) {
            return 0; // 예외 발생 시 균형 포지션 반환
        }
    }

    public float getMovePolicyScore(Move move, float[] policyArray) {
        int index = move.toPolicyIndex();
        if (index >= 0 && index < policyArray.length) {
            return policyArray[index];
        }
        return 0.0f;
    }

    private float[][][][] boardToTensor(Board board) {
        return new float[1][12][8][8];
    }
}
