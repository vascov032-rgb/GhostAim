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

public class YoloDetector {
    private Interpreter interpreter;
    private int INPUT_SIZE = 640;
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
        float[][][] output = new float[1][outputShape[1]][outputShape[2]];
        
        try {
            interpreter.run(input, output);
        } catch (Exception e) {
            lastMaxConf = -99.0f; // Sinaliza erro
            return null;
        }

        boolean isTransposed = (outputShape[1] < outputShape[2]);
        int numBoxes = isTransposed ? outputShape[2] : outputShape[1];

        float maxConf = 0.0f;
        float bestX = 0, bestY = 0, bestW = 0, bestH = 0;

        for (int i = 0; i < numBoxes; i++) {
            float conf = isTransposed ? output[0][4][i] : output[0][i][4];
            if (conf > maxConf) {
                maxConf = conf;
                bestX = isTransposed ? output[0][0][i] : output[0][i][0];
                bestY = isTransposed ? output[0][1][i] : output[0][i][1];
                bestW = isTransposed ? output[0][2][i] : output[0][i][2];
                bestH = isTransposed ? output[0][3][i] : output[0][i][3];
            }
        }

        lastMaxConf = maxConf;

        if (maxConf > 0.05f) {
            float scaleX = bitmap.getWidth() / (float) INPUT_SIZE;
            float scaleY = bitmap.getHeight() / (float) INPUT_SIZE;
            float left = (bestX - bestW / 2) * scaleX;
            float top = (bestY - bestH / 2) * scaleY;
            float right = (bestX + bestW / 2) * scaleX;
            float bottom = (bestY + bestH / 2) * scaleY;
            return new RectF(left, top, right, bottom);
        }
        return null;
    }
}
