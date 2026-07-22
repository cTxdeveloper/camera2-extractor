package com.example.camera2extractor;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int PERMISSION_CODE = 100;
    private TextView status;
    private boolean dataExtracted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        status = new TextView(this);
        status.setText("Initializing...");
        setContentView(status);

        // Log to internal file
        log("App started.");

        // Check permissions
        if (allPermissionsGranted()) {
            log("Permissions already granted.");
            extractData();
        } else {
            log("Permissions not granted, requesting...");
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, PERMISSION_CODE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!dataExtracted) {
            log("onResume – re-checking permissions.");
            if (allPermissionsGranted()) {
                log("Permissions granted (onResume).");
                extractData();
            } else {
                log("Permissions still not granted (onResume).");
                // Show a message to the user
                Toast.makeText(this, "Permissions not granted. Please grant them in Settings.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean allPermissionsGranted() {
        boolean camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean storage = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        String msg = "Permission check: Camera=" + camera + ", Storage=" + storage;
        log(msg);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        return camera && storage;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        log("onRequestPermissionsResult called.");
        if (requestCode == PERMISSION_CODE) {
            if (allPermissionsGranted()) {
                log("Permissions granted after request.");
                extractData();
            } else {
                log("Permissions denied after request.");
                status.setText("Permissions denied");
                Toast.makeText(this, "Permissions required. Please grant them in Settings.", Toast.LENGTH_LONG).show();
                // Check if permanently denied
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
                    log("User denied camera permission, but can ask again.");
                } else {
                    log("User permanently denied camera permission. Go to settings.");
                }
            }
        }
    }

    private void extractData() {
        if (dataExtracted) {
            log("Data already extracted, skipping.");
            return;
        }
        dataExtracted = true;
        status.setText("Extracting camera data...");
        log("Starting data extraction.");

        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String[] ids = manager.getCameraIdList();
            log("Camera IDs: " + ids.length);
            Map<String, Object> allData = new HashMap<>();
            Map<String, Object> cameras = new HashMap<>();

            for (String id : ids) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                Map<String, Object> info = new HashMap<>();

                try { info.put("LENS_FACING", chars.get(CameraCharacteristics.LENS_FACING) == 0 ? "front" : "back"); } catch (Exception ignored) {}
                try { info.put("SENSOR_INFO_PHYSICAL_SIZE", chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)); } catch (Exception ignored) {}
                try { info.put("SENSOR_INFO_PIXEL_ARRAY_SIZE", chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)); } catch (Exception ignored) {}
                try { info.put("SENSOR_INFO_ACTIVE_ARRAY_SIZE", chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)); } catch (Exception ignored) {}
                try { info.put("COLOR_FILTER_ARRANGEMENT", chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)); } catch (Exception ignored) {}
                try { info.put("SENSITIVITY_RANGE", chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)); } catch (Exception ignored) {}
                try { info.put("EXPOSURE_TIME_RANGE", chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)); } catch (Exception ignored) {}
                try { info.put("LENS_DISTORTION", chars.get(CameraCharacteristics.LENS_DISTORTION)); } catch (Exception ignored) {}
                try { info.put("APERTURES", chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)); } catch (Exception ignored) {}
                try { info.put("FOCAL_LENGTHS", chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)); } catch (Exception ignored) {}
                try { info.put("HYPERFOCAL_DISTANCE", chars.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)); } catch (Exception ignored) {}
                try { info.put("MIN_FOCUS_DISTANCE", chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)); } catch (Exception ignored) {}
                try { info.put("HARDWARE_LEVEL", chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)); } catch (Exception ignored) {}
                try { info.put("FLASH_AVAILABLE", chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)); } catch (Exception ignored) {}
                try { info.put("SENSOR_ORIENTATION", chars.get(CameraCharacteristics.SENSOR_ORIENTATION)); } catch (Exception ignored) {}

                cameras.put("camera_" + id, info);
            }

            allData.put("cameras", cameras);
            allData.put("device", Map.of(
                    "model", Build.MODEL,
                    "manufacturer", Build.MANUFACTURER,
                    "android", Build.VERSION.RELEASE,
                    "sdk", Build.VERSION.SDK_INT
            ));

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String json = gson.toJson(allData);
            // Save to external storage
            File out = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "camera_static_data.json");
            try (FileWriter fw = new FileWriter(out)) {
                fw.write(json);
            }
            // Also save to internal storage as fallback
            File internalOut = new File(getFilesDir(), "camera_static_data.json");
            try (FileWriter fw = new FileWriter(internalOut)) {
                fw.write(json);
            }

            status.setText("Data saved to:\n" + out.getAbsolutePath() + "\nand\n" + internalOut.getAbsolutePath());
            Toast.makeText(this, "JSON saved!", Toast.LENGTH_LONG).show();
            log("Data saved successfully.");
        } catch (CameraAccessException | IOException e) {
            String errorMsg = "Error: " + e.getMessage();
            status.setText(errorMsg);
            Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show();
            logError(e);
        } catch (Exception e) {
            String errorMsg = "Unexpected error: " + e.getMessage();
            status.setText(errorMsg);
            Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show();
            logError(e);
        }
    }

    private void log(String msg) {
        try {
            File logFile = new File(getFilesDir(), "app.log");
            try (FileWriter fw = new FileWriter(logFile, true)) {
                fw.write(java.time.LocalDateTime.now() + " - " + msg + "\n");
            }
        } catch (Exception ignored) {}
    }

    private void logError(Throwable t) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            t.printStackTrace(pw);
            String stack = sw.toString();
            File logFile = new File(getFilesDir(), "error.log");
            try (FileWriter fw = new FileWriter(logFile)) {
                fw.write(stack);
            }
        } catch (Exception ignored) {}
    }
}
