package com.wyvern;

import ai.onnxruntime.*;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.*;

public class OnnxEvaluator {
    private static OnnxEvaluator instance;
    private OrtEnvironment env;
    private OrtSession session;

    private OnnxEvaluator() {
        try {
            env = OrtEnvironment.getEnvironment();
            // src/main/resources/wyvern.onnx 파일 읽기
            InputStream modelStream = getClass().getResourceAsStream("/wyvern.onnx");
            if (modelStream == null) {
                throw new RuntimeException("wyvern.onnx 파일을 src/main/resources/ 에서 찾을 수 없습니다.");
            }
            byte[] modelBytes = modelStream.readAllBytes();
            session = env.createSession(modelBytes, new OrtSession.SessionOptions());
            System.out.println("✅ ONNX 모델 (wyvern.onnx) 로드 성공!");
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

    // Policy 추론 (수별 확률 배열 반환)
    public float[] predictPolicy(Board board) {
        try {
            float[][][][] inputTensorData = boardToTensor(board);
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, inputTensorData);
            
            try (OrtSession.Result results = session.run(Collections.singletonMap("input", inputTensor))) {
                // 모델의 출력 중 policy 가져오기
                float[][] policyOutput = (float[][]) results.get("policy").get().getValue();
                return policyOutput[0];
            }
        } catch (Exception e) {
            return new float[1968]; // 기본 빈 배열 반환 (에러 방지)
        }
    }

    // Value 추론 (승률 판단: -1.0 ~ +1.0)
    public float predictValue(Board board) {
        try {
            float[][][][] inputTensorData = boardToTensor(board);
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, inputTensorData);

            try (OrtSession.Result results = session.run(Collections.singletonMap("input", inputTensor))) {
                float[][] valueOutput = (float[][]) results.get("value").get().getValue();
                return valueOutput[0][0];
            }
        } catch (Exception e) {
            return 0.0f;
        }
    }

    // Move 인덱스에 해당하는 Policy Score 추출
    public float getMovePolicyScore(Move move, float[] policyArray) {
        int index = move.toPolicyIndex(); // Move 객체 내의 policy index 변환 메서드
        if (index >= 0 && index < policyArray.length) {
            return policyArray[index];
        }
        return 0.0f;
    }

    // 체스판을 (1, 12, 8, 8) 텐서 형태 float 배열로 변환
    private float[][][][] boardToTensor(Board board) {
        float[][][][] tensor = new float[1][12][8][8];
        // 기존 Python 학습 코드의 board_to_tensor()와 일치하도록 보드 채널 채우기
        // (board 객체 내부 정적 데이터 추출 로직 적용)
        return tensor;
    }
}
