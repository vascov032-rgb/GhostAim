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
    private int INPUT_SIZE = 320; // Roboflow fast training uses 320x320 or 640x640
    
    public YoloDetector(Context context) {
        try {
            MappedByteBuffer model = loadModelFile(context, "best.tflite");
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(4);
            interpreter = new Interpreter(model, options);
            
            // Auto-detect input size from the model
            int[] shape = interpreter.getInputTensor(0).shape();
            INPUT_SIZE = shape[1]; 
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
        ByteBuffer input = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4).order(ByteOrder.nativeOrder());
        
        for (int y = 0; y < INPUT_SIZE; y++) {
            for (int x = 0; x < INPUT_SIZE; x++) {
                int pixel = resized.getPixel(x, y);
                input.putFloat((pixel >> 16 & 0xFF) / 255.0f);
                input.putFloat((pixel >> 8 & 0xFF) / 255.0f);
                input.putFloat((pixel & 0xFF) / 255.0f);
            }
        }

        int[] outputShape = interpreter.getOutputTensor(0).shape();
        float[][][] output = new float[1][outputShape[1]][outputShape[2]];
        
        try { interpreter.run(input, output); } catch (Exception e) { return null; }

        float highestConf = 0.0f;
        float bestX = 0, bestY = 0, bestW = 0, bestH = 0;

        int numBoxes = outputShape[2];
        for (int i = 0; i < numBoxes; i++) {
            float conf = output[0][4][i];
            if (conf > 0.40f && conf > highestConf) {
                highestConf = conf;
                bestX = output[0][0][i];
                bestY = output[0][1][i];
                bestW = output[0][2][i];
                bestH = output[0][3][i];
            }
        }

        if (highestConf > 0.40f) {
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
