package com.example.accidentdetection;
import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Collections;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

public class AccidentClassifier {
private static final String TAG = "AccidentClassifier";
private static final String MODEL_FILE = "driver_behavior_model.onnx";
private static final String MODEL_DATA_FILE = "driver_behavior_model.onnx.data";

// Threshold fallback values
private static final float ACCEL_THRESHOLD = 20.0f; // m/s²
private static final float GYRO_THRESHOLD = 5.0f;   // rad/s

private OrtEnvironment env;
private OrtSession session;
private boolean mlAvailable = false;
private Context context;

public AccidentClassifier(Context context) {
    this.context = context;
    try {
        Log.i(TAG, "=== Starting ML Model Load ===");
        Log.d(TAG, "Attempting to load ONNX model: " + MODEL_FILE);

        // Check if ONNX Runtime classes are available
        try {
            Class.forName("ai.onnxruntime.OrtEnvironment");
            Log.d(TAG, "✓ ONNX Runtime library is available");
        } catch (ClassNotFoundException e) {
            throw new Exception("ONNX Runtime library not found! Did you sync Gradle?", e);
        }

        // Initialize ONNX Runtime environment
        Log.d(TAG, "Creating ONNX environment...");
        env = OrtEnvironment.getEnvironment();
        Log.d(TAG, "✓ ONNX environment created");

        // List all assets to verify they're in the APK
        try {
            String[] allAssets = context.getAssets().list("");
            Log.d(TAG, "All assets in APK: " + String.join(", ", allAssets));

            // Check specifically for our model files
            boolean hasMainModel = false;
            boolean hasDataFile = false;
            for (String asset : allAssets) {
                if (asset.equals(MODEL_FILE)) hasMainModel = true;
                if (asset.contains(".data") || asset.contains(".onnx.data")) hasDataFile = true;
            }
            Log.d(TAG, "Model file present: " + hasMainModel + ", Data file present: " + hasDataFile);
        } catch (IOException e) {
            Log.w(TAG, "Could not list assets: " + e.getMessage());
        }

        // Try loading model directly from assets first (works if data is embedded)
        Log.d(TAG, "Attempting direct load from assets...");
        try {
            byte[] modelBytes = loadAssetAsBytes(MODEL_FILE);
            Log.d(TAG, "Loaded model bytes: " + modelBytes.length);
            session = env.createSession(modelBytes);
            mlAvailable = true;
            Log.i(TAG, "🎉 ONNX MODEL LOADED SUCCESSFULLY (direct from assets)! 🎉");
        } catch (Exception directLoadError) {
            Log.w(TAG, "Direct load failed: " + directLoadError.getMessage());
            Log.d(TAG, "Trying file-based load for external data support...");

            // Fall back to file-based loading for external data
            File modelFile = extractAssetToFile(MODEL_FILE);

            // Try to extract data file if it exists
            File dataFile = null;
            try {
                dataFile = extractAssetToFile(MODEL_DATA_FILE);
                Log.d(TAG, "✓ Data file extracted: " + dataFile.getAbsolutePath() + " (" + dataFile.length() + " bytes)");
            } catch (IOException dataError) {
                Log.w(TAG, "Could not extract data file (may not exist): " + dataError.getMessage());
            }

            Log.d(TAG, "✓ Model extracted to: " + modelFile.getAbsolutePath() + " (" + modelFile.length() + " bytes)");

            // Create ONNX session from file path
            Log.d(TAG, "Creating ONNX session from file...");
            session = env.createSession(modelFile.getAbsolutePath());
            mlAvailable = true;
            Log.i(TAG, "🎉 ONNX MODEL LOADED SUCCESSFULLY (from file)! 🎉");
        }
        Log.d(TAG, "Model inputs: " + session.getInputNames());
        Log.d(TAG, "Model outputs: " + session.getOutputNames());
        Log.i(TAG, "=== ML Model Load Complete ===");

    } catch (OrtException e) {
        Log.e(TAG, "❌ ONNX Runtime error: " + e.getMessage(), e);
        Log.e(TAG, "Error code: " + e.getClass().getSimpleName());
        mlAvailable = false;
    } catch (IOException e) {
        Log.e(TAG, "❌ Failed to load model file from assets: " + e.getMessage(), e);
        Log.e(TAG, "Check that " + MODEL_FILE + " and " + MODEL_DATA_FILE + " exist in app/src/main/assets/");
        mlAvailable = false;
    } catch (NoClassDefFoundError e) {
        Log.e(TAG, "❌ ONNX Runtime classes not found: " + e.getMessage(), e);
        Log.e(TAG, "SOLUTION: Sync Gradle (File → Sync Project with Gradle Files)");
        mlAvailable = false;
    } catch (Exception e) {
        Log.e(TAG, "❌ Unexpected error loading model: " + e.getMessage(), e);
        e.printStackTrace();
        mlAvailable = false;
    }

    if (!mlAvailable) {
        Log.w(TAG, "⚠️ ML model not available - using threshold-based fallback detection");
        Log.w(TAG, "Fallback thresholds: Accel>" + ACCEL_THRESHOLD + " m/s², Gyro>" + GYRO_THRESHOLD + " rad/s");
    }
}

/**
 * Load an asset file as a byte array
 */
private byte[] loadAssetAsBytes(String assetFileName) throws IOException {
    InputStream is = null;
    try {
        is = context.getAssets().open(assetFileName);
        byte[] buffer = new byte[is.available()];
        is.read(buffer);
        return buffer;
    } finally {
        if (is != null) {
            try {
                is.close();
            } catch (IOException e) {
                Log.w(TAG, "Failed to close input stream: " + e.getMessage());
            }
        }
    }
}

/**
 * Extract an asset file to internal storage so ONNX Runtime can access it
 * and resolve relative paths to external data files
 */
private File extractAssetToFile(String assetFileName) throws IOException {
    File outputFile = new File(context.getFilesDir(), assetFileName);

    // Always re-extract to ensure files are up-to-date and not corrupted
    // This is important after app updates or if previous extraction failed
    if (outputFile.exists()) {
        long existingSize = outputFile.length();
        Log.d(TAG, "Existing model file found (" + existingSize + " bytes): " + outputFile.getAbsolutePath());

        // Delete and re-extract to ensure fresh copy
        if (outputFile.delete()) {
            Log.d(TAG, "Deleted existing file to ensure fresh extraction");
        } else {
            Log.w(TAG, "Could not delete existing file, will overwrite");
        }
    }

    InputStream is = null;
    FileOutputStream fos = null;
    try {
        Log.d(TAG, "Extracting asset: " + assetFileName);
        is = context.getAssets().open(assetFileName);
        fos = new FileOutputStream(outputFile);

        byte[] buffer = new byte[8192];
        int bytesRead;
        int totalBytes = 0;
        while ((bytesRead = is.read(buffer)) != -1) {
            fos.write(buffer, 0, bytesRead);
            totalBytes += bytesRead;
        }
        fos.flush();

        Log.d(TAG, "✓ Extracted " + totalBytes + " bytes to " + outputFile.getAbsolutePath());
        return outputFile;

    } catch (IOException e) {
        Log.e(TAG, "❌ Failed to extract asset: " + assetFileName, e);

        // List available assets to help debug
        try {
            String[] assets = context.getAssets().list("");
            Log.d(TAG, "Available assets in root: " + String.join(", ", assets));
        } catch (IOException listEx) {
            Log.e(TAG, "Cannot list assets: " + listEx.getMessage());
        }

        throw e;
    } finally {
        if (is != null) {
            try {
                is.close();
            } catch (IOException e) {
                Log.w(TAG, "Failed to close input stream: " + e.getMessage());
            }
        }
        if (fos != null) {
            try {
                fos.close();
            } catch (IOException e) {
                Log.w(TAG, "Failed to close output stream: " + e.getMessage());
            }
        }
    }
}

/**
 * Predicts accident probability based on sensor values
 * @param accel Acceleration magnitude (m/s²)
 * @param gyro Gyroscope magnitude (rad/s)
 * @return Probability (0.0 to 1.0)
 */
public float predict(float accel, float gyro) {
    if (mlAvailable && session != null) {
        try {
            return predictWithML(accel, gyro);
        } catch (Exception e) {
            Log.e(TAG, "ML prediction failed, falling back to threshold: " + e.getMessage(), e);
            return predictWithThreshold(accel, gyro);
        }
    } else {
        return predictWithThreshold(accel, gyro);
    }
}

private float predictWithML(float accel, float gyro) throws OrtException {
    // Prepare input tensor (assuming model expects [1, 2] shape: batch_size=1, features=2)
    float[] inputData = new float[]{accel, gyro};
    long[] shape = new long[]{1, 2};

    OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape);

    try {
        // Run inference
        OrtSession.Result results = session.run(
                Collections.singletonMap(session.getInputNames().iterator().next(), inputTensor)
        );

        // Get output (assuming single output with probability)
        float[][] output = (float[][]) results.get(0).getValue();
        float probability = output[0][0];

        Log.v(TAG, "ML prediction - Accel: " + accel + ", Gyro: " + gyro + " -> Probability: " + probability);

        results.close();
        return probability;

    } finally {
        inputTensor.close();
    }
}

private float predictWithThreshold(float accel, float gyro) {
    float probability = (accel > ACCEL_THRESHOLD || gyro > GYRO_THRESHOLD) ? 0.8f : 0.1f;

    if (probability >= 0.7f) {
        Log.d(TAG, "Threshold detection - Accel: " + accel + ", Gyro: " + gyro + " -> HIGH RISK");
    }

    return probability;
}

/**
 * @return true if ML model is loaded and ready
 */
public boolean isMlAvailable() {
    return mlAvailable;
}

/**
 * Clean up resources
 */
public void close() {
    try {
        if (session != null) {
            session.close();
            Log.d(TAG, "ONNX session closed");
        }
        if (env != null) {
            // Note: Don't close the global environment
            env = null;
        }
    } catch (OrtException e) {
        Log.e(TAG, "Error closing ONNX session: " + e.getMessage());
    }
}
}