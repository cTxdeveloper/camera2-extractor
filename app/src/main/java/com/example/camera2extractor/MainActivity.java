package com.example.camera2extractor;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            extractData();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERMISSION_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                extractData();
            } else {
                status.setText("Camera permission required");
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void extractData() {
        if (dataExtracted) return;
        dataExtracted = true;
        status.setText("Extracting camera data...");

        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String[] ids = manager.getCameraIdList();
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

            // Save to internal storage – no permission needed
            File internalFile = new File(getFilesDir(), "camera_static_data.json");
            try (FileWriter fw = new FileWriter(internalFile)) {
                fw.write(json);
            }

            // Also attempt to save to Downloads if possible (no explicit permission required for Android 10+ using legacy mode, but we already removed storage permission)
            // We'll just not save externally to avoid permission issues.

            status.setText("Data saved to:\n" + internalFile.getAbsolutePath() + "\n\nTap to share.");
            Toast.makeText(this, "JSON saved!", Toast.LENGTH_LONG).show();

            // Make the TextView clickable to share
            status.setOnClickListener(v -> shareFile(internalFile));

        } catch (CameraAccessException | IOException e) {
            status.setText("Error: " + e.getMessage());
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            logError(e);
        } catch (Exception e) {
            status.setText("Error: " + e.getMessage());
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            logError(e);
        }
    }

    private void shareFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".provider",
                    file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/json");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Share JSON"));
        } catch (Exception e) {
            Toast.makeText(this, "Share failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
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
