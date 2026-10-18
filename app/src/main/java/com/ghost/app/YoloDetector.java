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
    public static float lastMaxConf = 0.0f; // Guarda a confiança para o debug
    
    public YoloDetector(Context context) {
        try {
            MappedByteBuffer model = loadModelFile(context, "best.tflite");
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(4);
            interpreter = new Interpreter(model, options);
            
            int[] shape = interpreter.getInputTensor(0).shape();
            if (shape[1] == 3) { // Formato BCHW (Channel, Height, Width)
                INPUT_SIZE = shape[2];
            } else { // Formato BHWC (Batch, Height, Width, Channel)
                INPUT_SIZE = shape[1];
            }
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

        int[] shape = interpreter.getInputTensor(0).shape();
        if (shape[1] == 3) {
            // Formato CHW (usado pelo Roboflow)
            for (int c = 0; c < 3; c++) {
                for (int i = 0; i < INPUT_SIZE * INPUT_SIZE; i++) {
                    int p = pixels[i];
                    int val = (c == 0) ? (p >> 16 & 0xFF) : (c == 1) ? (p >> 8 & 0xFF) : (p & 0xFF);
                    input.putFloat(val / 255.0f);
                }
            }
        } else {
            // Formato HWC
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

        float highestConf = 0.0f;
        float bestX = 0, bestY = 0, bestW = 0, bestH = 0;

        int numBoxes = transposed ? outputShape[1] : outputShape[2];
        for (int i = 0; i < numBoxes; i++) {
            float conf = transposed ? output[0][i][4] : output[0][4][i];
            if (conf > 0.05f && conf > highestConf) {
                highestConf = conf;
                bestX = transposed ? output[0][i][0] : output[0][0][i];
                bestY = transposed ? output[0][i][1] : output[0][1][i];
                bestW = transposed ? output[0][i][2] : output[0][2][i];
                bestH = transposed ? output[0][i][3] : output[0][3][i];
            }
        }
        
        lastMaxConf = highestConf; // Atualiza a confiança para o Overlay

        if (highestConf > 0.05f) {
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
