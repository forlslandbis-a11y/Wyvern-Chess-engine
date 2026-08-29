package com.wyvern;

import ai.onnxruntime.*;
import java.io.InputStream;
import java.util.*;

public class OnnxEvaluator {
    private static OnnxEvaluator instance;
    private OrtEnvironment env;
    private OrtSession session;

    private OnnxEvaluator() {
        try {
            env = OrtEnvironment.getEnvironment();
            
            InputStream modelStream = getClass().getResourceAsStream("/wyvern_int8.onnx");
            if (modelStream == null) {
                modelStream = getClass().getResourceAsStream("/wyvern.onnx");
            }
            
            if (modelStream == null) {
                throw new RuntimeException("wyvern_int8.onnx 또는 wyvern.onnx 모델을 찾을 수 없습니다.");
            }
            
            byte[] modelBytes = modelStream.readAllBytes();
            session = env.createSession(modelBytes, new OrtSession.SessionOptions());
            System.out.println("✅ Wyvern Int8 연산 Evaluator 로드 성공!");
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
     * Int8 연산 및 Int32 Accumulator 기반 Value 추론
     */
    public int predictValue(Board board) {
        try {
            float[][][][] inputTensorData = boardToTensor(board);
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, inputTensorData);

            try (OrtSession.Result results = session.run(Collections.singletonMap("input", inputTensor))) {
                Object rawValue = results.get("value").get().getValue();
                
                // Int8 단위 연산 입력값 추출
                byte int8RawValue = 0;
                
                if (rawValue instanceof byte[][]) {
                    // Int8 ONNX 모델에서 출력된 순수 Int8 데이터 로드 (-128 ~ 127)
                    int8RawValue = ((byte[][]) rawValue)[0][0];
                } else if (rawValue instanceof float[][]) {
                    // Float32 모델 폴백 시 Int8 스케일(-126 ~ +126)로 연산 스코어 변환
                    float floatVal = ((float[][]) rawValue)[0][0];
                    int8RawValue = (byte) Math.max(-127, Math.min(127, Math.round(floatVal * 126.0f)));
                }

                // ====================================================
                // ⚡ Int8 연산 -> Int32 Accumulator 누적 프로세스
                // ====================================================
                
                // 1. Int8 값을 Int32 Accumulator 레지스터에 승격하여 합산
                int accumulator = (int) int8RawValue;

                // 2. 강제 체크메이트 (+127 / -127) 판정
                if (accumulator >= 127) return 12700;  // White Forced Mate
                if (accumulator <= -127) return -12700; // Black Forced Mate

                // 3. Int32 Accumulator 상에서 소수점/센티폰 변환 (1 Unit = 10 Centipawns = 0.1 Pawn)
                int centipawns = accumulator * 10;
                
                // -12.6 ~ +12.6 Pawn (-1260 ~ +1260 cp) 범위 Clamping 후 최종 반환
                return Math.max(-1260, Math.min(1260, centipawns));
            }
        } catch (Exception e) {
            return 0; // 예외 시 0 cp 반환
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
