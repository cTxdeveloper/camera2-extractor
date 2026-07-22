package com.example.camera2extractor;

import android.Manifest;
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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_CODE = 100;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        status = new TextView(this);
        status.setText("Initializing...");
        setContentView(status);

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, PERMISSION_CODE);
            return;
        }

        new Handler(Looper.getMainLooper()).postDelayed(this::extractData, 500);
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_CODE && allPermissionsGranted()) {
            extractData();
        } else {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show();
        }
    }

    private void extractData() {
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
                try { info.put("BLACK_LEVEL_PATTERN", chars.get(CameraCharacteristics.SENSOR_INFO_BLACK_LEVEL_PATTERN).getOffsets()); } catch (Exception ignored) {}
                try { info.put("SENSITIVITY_RANGE", chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)); } catch (Exception ignored) {}
                try { info.put("EXPOSURE_TIME_RANGE", chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)); } catch (Exception ignored) {}
                try { info.put("FRAME_DURATION_RANGE", chars.get(CameraCharacteristics.SENSOR_INFO_FRAME_DURATION_RANGE)); } catch (Exception ignored) {}
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
            File out = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "camera_static_data.json");
            try (FileWriter fw = new FileWriter(out)) {
                fw.write(json);
            }

            status.setText("Data saved to:\n" + out.getAbsolutePath());
            Toast.makeText(this, "JSON saved!", Toast.LENGTH_LONG).show();
        } catch (CameraAccessException | IOException e) {
            status.setText("Error: " + e.getMessage());
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
