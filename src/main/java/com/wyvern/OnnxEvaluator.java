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

    public float getMovePolicyScore(Move move, float[] policyArray) {
        int index = move.toPolicyIndex();
        if (index >= 0 && index < policyArray.length) {
            return policyArray[index];
        }
        return 0.0f;
    }

    private float[][][][] boardToTensor(Board board) {
        float[][][][] tensor = new float[1][12][8][8];
        // Python의 board_to_tensor() 구조에 맞춰 최소 텐서 형태 할당
        return tensor;
    }
}
