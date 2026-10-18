package com.ghost.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import org.tensorflow.lite.Interpreter;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class YoloDetector {
    private Interpreter interpreter;
    private int INPUT_SIZE = 640;
    private static final float CONFIDENCE_THRESHOLD = 0.30f; // Limiar de confiança
    private static final float NMS_THRESHOLD = 0.45f; // Limiar para o NMS
    public static float lastMaxConf = 0.0f;

    public YoloDetector(Context context) {
        try {
            MappedByteBuffer model = loadModelFile(context, "best.tflite");
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(4);
            interpreter = new Interpreter(model, options);
            
            int[] shape = interpreter.getInputTensor(0).shape();
            INPUT_SIZE = shape[1] == 3 ? shape[2] : shape[1];
        } catch (Exception e) { e.printStackTrace(); }
    }

    private MappedByteBuffer loadModelFile(Context context, String modelPath) throws IOException {
        FileInputStream inputStream = new FileInputStream(context.getAssets().openFd(modelPath).getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = context.getAssets().openFd(modelPath).getStartOffset();
        long declaredLength = context.getAssets().openFd(modelPath).getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    public RectF detect(Bitmap bitmap) {
        if (interpreter == null) return null;

        Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);
        ByteBuffer input = ByteBuffer.allocateDirect(4 * 3 * INPUT_SIZE * INPUT_SIZE).order(ByteOrder.nativeOrder());
        
        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);

        int[] inputShape = interpreter.getInputTensor(0).shape();
        if (inputShape[1] == 3) {
            for (int c = 0; c < 3; c++) {
                for (int i = 0; i < INPUT_SIZE * INPUT_SIZE; i++) {
                    int p = pixels[i];
                    int val = (c == 0) ? (p >> 16 & 0xFF) : (c == 1) ? (p >> 8 & 0xFF) : (p & 0xFF);
                    input.putFloat(val / 255.0f);
                }
            }
        } else {
            for (int i = 0; i < INPUT_SIZE * INPUT_SIZE; i++) {
                int p = pixels[i];
                input.putFloat((p >> 16 & 0xFF) / 255.0f);
                input.putFloat((p >> 8 & 0xFF) / 255.0f);
                input.putFloat((p & 0xFF) / 255.0f);
            }
        }

        int[] outputShape = interpreter.getOutputTensor(0).shape();
        float[][][] output;
        boolean transposed = false;
        
        if (outputShape[1] < outputShape[2]) {
            output = new float[1][outputShape[1]][outputShape[2]];
        } else {
            output = new float[1][outputShape[2]][outputShape[1]];
            transposed = true;
        }
        
        try { interpreter.run(input, output); } catch (Exception e) { return null; }

        List<Detection> detections = new ArrayList<>();
        int numBoxes = transposed ? outputShape[1] : outputShape[2];
        
        for (int i = 0; i < numBoxes; i++) {
            float conf = transposed ? output[0][i][4] : output[0][4][i];
            if (conf > CONFIDENCE_THRESHOLD) {
                float cx = transposed ? output[0][i][0] : output[0][0][i];
                float cy = transposed ? output[0][i][1] : output[0][1][i];
                float w = transposed ? output[0][i][2] : output[0][2][i];
                float h = transposed ? output[0][i][3] : output[0][3][i];
                detections.add(new Detection(cx, cy, w, h, conf));
            }
        }

        if (detections.isEmpty()) {
            lastMaxConf = 0.0f;
            return null;
        }

        // Aplicar NMS
        List<Detection> finalDetections = applyNMS(detections);
        if (finalDetections.isEmpty()) {
            lastMaxConf = 0.0f;
            return null;
        }

        // Pegar a detecção com maior confiança
        Detection best = finalDetections.get(0);
        lastMaxConf = best.conf;

        float scaleX = bitmap.getWidth() / (float) INPUT_SIZE;
        float scaleY = bitmap.getHeight() / (float) INPUT_SIZE;
        float left = (best.cx - best.w / 2) * scaleX;
        float top = (best.cy - best.h / 2) * scaleY;
        float right = (best.cx + best.w / 2) * scaleX;
        float bottom = (best.cy + best.h / 2) * scaleY;
        return new RectF(left, top, right, bottom);
    }

    private List<Detection> applyNMS(List<Detection> detections) {
        Collections.sort(detections, (a, b) -> Float.compare(b.conf, a.conf));
        List<Detection> result = new ArrayList<>();
        while (!detections.isEmpty()) {
            Detection best = detections.remove(0);
            result.add(best);
            detections.removeIf(d -> iou(best, d) > NMS_THRESHOLD);
        }
        return result;
    }

    private float iou(Detection a, Detection b) {
        float x1 = Math.max(a.cx - a.w / 2, b.cx - b.w / 2);
        float y1 = Math.max(a.cy - a.h / 2, b.cy - b.h / 2);
        float x2 = Math.min(a.cx + a.w / 2, b.cx + b.w / 2);
        float y2 = Math.min(a.cy + a.h / 2, b.cy + b.h / 2);
        float intersection = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        float areaA = a.w * a.h;
        float areaB = b.w * b.h;
        return intersection / (areaA + areaB - intersection);
    }

    private static class Detection {
        float cx, cy, w, h, conf;
        Detection(float cx, float cy, float w, float h, float conf) {
            this.cx = cx; this.cy = cy; this.w = w; this.h = h; this.conf = conf;
        }
    }
}
