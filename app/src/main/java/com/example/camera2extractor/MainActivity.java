package com.example.camera2extractor;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

                // ---- LENS FACING ----
                try { info.put("LENS_FACING", chars.get(CameraCharacteristics.LENS_FACING) == 0 ? "front" : "back"); } catch (Exception ignored) {}

                // ---- SENSOR INFO PHYSICAL SIZE ----
                try {
                    SizeF size = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                    if (size != null) {
                        info.put("SENSOR_INFO_PHYSICAL_SIZE_WIDTH_MM", size.getWidth());
                        info.put("SENSOR_INFO_PHYSICAL_SIZE_HEIGHT_MM", size.getHeight());
                    }
                } catch (Exception ignored) {}

                // ---- SENSOR INFO PIXEL ARRAY SIZE ----
                try {
                    Size size = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                    if (size != null) {
                        info.put("SENSOR_INFO_PIXEL_ARRAY_SIZE_WIDTH", size.getWidth());
                        info.put("SENSOR_INFO_PIXEL_ARRAY_SIZE_HEIGHT", size.getHeight());
                    }
                } catch (Exception ignored) {}

                // ---- SENSOR INFO ACTIVE ARRAY SIZE ----
                try {
                    android.graphics.Rect rect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                    if (rect != null) {
                        info.put("SENSOR_INFO_ACTIVE_ARRAY_SIZE_LEFT", rect.left);
                        info.put("SENSOR_INFO_ACTIVE_ARRAY_SIZE_TOP", rect.top);
                        info.put("SENSOR_INFO_ACTIVE_ARRAY_SIZE_RIGHT", rect.right);
                        info.put("SENSOR_INFO_ACTIVE_ARRAY_SIZE_BOTTOM", rect.bottom);
                    }
                } catch (Exception ignored) {}

                // ---- COLOR FILTER ARRAY (Bayer) ----
                try {
                    Integer cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
                    if (cfa != null) {
                        String[] cfaNames = {"RGGB", "GRBG", "GBRG", "BGGR", "RGBIR"};
                        info.put("SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NAME", (cfa >= 0 && cfa < cfaNames.length) ? cfaNames[cfa] : "UNKNOWN");
                        info.put("SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_VALUE", cfa);
                    }
                } catch (Exception ignored) {}

                // ---- SENSITIVITY RANGE (ISO) ----
                try {
                    Range<Integer> range = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                    if (range != null) {
                        info.put("SENSOR_INFO_SENSITIVITY_RANGE_MIN", range.getLower());
                        info.put("SENSOR_INFO_SENSITIVITY_RANGE_MAX", range.getUpper());
                    }
                } catch (Exception ignored) {}

                // ---- EXPOSURE TIME RANGE ----
                try {
                    Range<Long> range = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                    if (range != null) {
                        info.put("SENSOR_INFO_EXPOSURE_TIME_RANGE_MIN_NS", range.getLower());
                        info.put("SENSOR_INFO_EXPOSURE_TIME_RANGE_MAX_NS", range.getUpper());
                    }
                } catch (Exception ignored) {}

                // ---- FRAME DURATION RANGE ----
                try {
                    Range<Long> range = chars.get(CameraCharacteristics.SENSOR_INFO_FRAME_DURATION_RANGE);
                    if (range != null) {
                        info.put("SENSOR_INFO_FRAME_DURATION_RANGE_MIN_NS", range.getLower());
                        info.put("SENSOR_INFO_FRAME_DURATION_RANGE_MAX_NS", range.getUpper());
                    }
                } catch (Exception ignored) {}

                // ---- BLACK LEVEL PATTERN ----
                try {
                    BlackLevelPattern blp = chars.get(CameraCharacteristics.SENSOR_INFO_BLACK_LEVEL_PATTERN);
                    if (blp != null) {
                        int[] offsets = blp.getOffsets();
                        info.put("SENSOR_INFO_BLACK_LEVEL_PATTERN_R", offsets[0]);
                        info.put("SENSOR_INFO_BLACK_LEVEL_PATTERN_GEVEN", offsets[1]);
                        info.put("SENSOR_INFO_BLACK_LEVEL_PATTERN_GODD", offsets[2]);
                        info.put("SENSOR_INFO_BLACK_LEVEL_PATTERN_B", offsets[3]);
                    }
                } catch (Exception ignored) {}

                // ---- LENS DISTORTION ----
                try {
                    float[] dist = chars.get(CameraCharacteristics.LENS_DISTORTION);
                    if (dist != null) info.put("LENS_DISTORTION", dist);
                } catch (Exception ignored) {}

                // ---- LENS AVAILABLE APERTURES ----
                try {
                    float[] apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
                    if (apertures != null) info.put("LENS_INFO_AVAILABLE_APERTURES", apertures);
                } catch (Exception ignored) {}

                // ---- LENS AVAILABLE FOCAL LENGTHS ----
                try {
                    float[] fls = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    if (fls != null) info.put("LENS_INFO_AVAILABLE_FOCAL_LENGTHS", fls);
                } catch (Exception ignored) {}

                // ---- LENS HYPERFOCAL DISTANCE ----
                try {
                    Float hyp = chars.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE);
                    if (hyp != null) info.put("LENS_INFO_HYPERFOCAL_DISTANCE", hyp);
                } catch (Exception ignored) {}

                // ---- LENS MIN FOCUS DISTANCE ----
                try {
                    Float min = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                    if (min != null) info.put("LENS_INFO_MINIMUM_FOCUS_DISTANCE", min);
                } catch (Exception ignored) {}

                // ---- HARDWARE LEVEL ----
                try {
                    Integer level = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                    if (level != null) {
                        String[] levels = {"LIMITED", "FULL", "LEGACY", "LEVEL_3"};
                        info.put("INFO_SUPPORTED_HARDWARE_LEVEL_NAME", (level >= 0 && level < levels.length) ? levels[level] : "UNKNOWN");
                        info.put("INFO_SUPPORTED_HARDWARE_LEVEL_VALUE", level);
                    }
                } catch (Exception ignored) {}

                // ---- FLASH AVAILABLE ----
                try {
                    Boolean flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (flash != null) info.put("FLASH_INFO_AVAILABLE", flash);
                } catch (Exception ignored) {}

                // ---- SENSOR ORIENTATION ----
                try {
                    Integer orient = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    if (orient != null) info.put("SENSOR_ORIENTATION", orient);
                } catch (Exception ignored) {}

                // ---- STREAM CONFIGURATION MAP (resolutions + RAW) ----
                try {
                    StreamConfigurationMap config = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (config != null) {
                        // JPEG resolutions
                        Size[] jpegSizes = config.getOutputSizes(ImageFormat.JPEG);
                        if (jpegSizes != null) {
                            List<Map<String, Integer>> jpegResolutions = new ArrayList<>();
                            for (Size s : jpegSizes) {
                                Map<String, Integer> res = new HashMap<>();
                                res.put("width", s.getWidth());
                                res.put("height", s.getHeight());
                                jpegResolutions.add(res);
                            }
                            info.put("JPEG_RESOLUTIONS", jpegResolutions);
                        }
                        // RAW support
                        Size[] rawSizes = config.getOutputSizes(ImageFormat.RAW_SENSOR);
                        info.put("RAW_SUPPORTED", rawSizes != null && rawSizes.length > 0);
                        if (rawSizes != null && rawSizes.length > 0) {
                            List<Map<String, Integer>> rawResolutions = new ArrayList<>();
                            for (Size s : rawSizes) {
                                Map<String, Integer> res = new HashMap<>();
                                res.put("width", s.getWidth());
                                res.put("height", s.getHeight());
                                rawResolutions.add(res);
                            }
                            info.put("RAW_RESOLUTIONS", rawResolutions);
                        }
                    }
                } catch (Exception ignored) {}

                // ---- CAPABILITIES ----
                try {
                    int[] caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                    if (caps != null) {
                        info.put("REQUEST_AVAILABLE_CAPABILITIES", caps);
                    }
                } catch (Exception ignored) {}

                // ---- Put this camera's data ----
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

            // Save to internal storage
            File internalFile = new File(getFilesDir(), "camera_static_data.json");
            try (FileWriter fw = new FileWriter(internalFile)) {
                fw.write(json);
            }

            status.setText("Data saved to:\n" + internalFile.getAbsolutePath() + "\n\nTap to share.");
            Toast.makeText(this, "JSON saved!", Toast.LENGTH_LONG).show();

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
