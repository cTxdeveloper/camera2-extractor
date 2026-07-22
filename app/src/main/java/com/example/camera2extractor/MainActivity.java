package com.example.camera2extractor;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements LifecycleOwner, SensorEventListener {
    private static final int PERMISSION_CODE = 100;
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    private TextView status;
    private PreviewView previewView;
    private ImageCapture imageCapture;
    private Camera camera;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private FusedLocationProviderClient fusedLocationClient;
    private SensorManager sensorManager;
    private Sensor accelerometer, gyroscope, magnetometer, lightSensor, pressureSensor;
    private Map<String, Object> sensorReadings = new HashMap<>();
    private Location lastLocation = null;

    private Map<String, Object> allData = new HashMap<>();
    private boolean dataExtracted = false;
    private boolean cameraReady = false;
    private LifecycleRegistry lifecycleRegistry;

    @Override
    public Lifecycle getLifecycle() {
        if (lifecycleRegistry == null) {
            lifecycleRegistry = new LifecycleRegistry(this);
        }
        return lifecycleRegistry;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        previewView = findViewById(R.id.previewView);
        status.setText("Initializing...");

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_CODE);
            return;
        }

        // Initialize location client (only if permissions granted)
        try {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        } catch (Exception e) {
            status.setText("Location services unavailable");
            fusedLocationClient = null;
        }

        // Sensors
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        registerSensors();

        // Location
        getLocation();

        // Camera
        cameraExecutor = Executors.newSingleThreadExecutor();
        startCamera();

        // Lifecycle registry
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START);
    }

    @Override
    protected void onResume() {
        super.onResume();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
        registerSensors();
        getLocation(); // safe call
    }

    @Override
    protected void onPause() {
        super.onPause();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
        if (sensorManager != null) sensorManager.unregisterListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }

    private boolean allPermissionsGranted() {
        for (String perm : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_CODE && allPermissionsGranted()) {
            recreate();
        } else {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show();
            status.setText("Permissions denied");
        }
    }

    private void registerSensors() {
        if (sensorManager == null) return;
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST);
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST);
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_FASTEST);
        if (lightSensor != null) sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        if (pressureSensor != null) sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        String name = "";
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                name = "accelerometer";
                sensorReadings.put(name, Map.of("x", event.values[0], "y", event.values[1], "z", event.values[2]));
                break;
            case Sensor.TYPE_GYROSCOPE:
                name = "gyroscope";
                sensorReadings.put(name, Map.of("x", event.values[0], "y", event.values[1], "z", event.values[2]));
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                name = "magnetometer";
                sensorReadings.put(name, Map.of("x", event.values[0], "y", event.values[1], "z", event.values[2]));
                break;
            case Sensor.TYPE_LIGHT:
                sensorReadings.put("light_lux", event.values[0]);
                break;
            case Sensor.TYPE_PRESSURE:
                sensorReadings.put("pressure_hpa", event.values[0]);
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void getLocation() {
        if (fusedLocationClient == null) {
            // Location client not available
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // No permission, skip
            return;
        }
        fusedLocationClient.getLastLocation().addOnSuccessListener(loc -> {
            if (loc != null) lastLocation = loc;
        }).addOnFailureListener(e -> {
            // Ignore failure
        });
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> provider = ProcessCameraProvider.getInstance(this);
        provider.addListener(() -> {
            try {
                cameraProvider = provider.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                CameraSelector selector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);
                cameraReady = true;
                status.setText("Camera ready. Capturing in 3s...");

                // Auto-capture after 3 seconds
                previewView.postDelayed(this::capturePhoto, 3000);

            } catch (ExecutionException | InterruptedException e) {
                status.setText("Camera error: " + e.getMessage());
                Toast.makeText(this, "Camera init failed", Toast.LENGTH_LONG).show();
                logError(e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void capturePhoto() {
        if (!cameraReady || imageCapture == null) {
            status.setText("Camera not ready");
            return;
        }
        status.setText("Capturing...");
        File photo = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "captured_" + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions opts = new ImageCapture.OutputFileOptions.Builder(photo).build();

        imageCapture.takePicture(opts, cameraExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                status.setText("Image saved. Gathering data...");
                gatherAllData(photo);
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                status.setText("Capture error: " + exception.getMessage());
                Toast.makeText(MainActivity.this, "Capture failed", Toast.LENGTH_LONG).show();
                logError(exception);
            }
        });
    }

    private void gatherAllData(File photoFile) {
        allData.clear();
        dataExtracted = true;

        // 1. Static camera characteristics (without problematic keys)
        Map<String, Object> staticData = getStaticCameraInfo();
        allData.put("static_camera", staticData);

        // 2. Dynamic metadata from EXIF
        Map<String, Object> dynamicData = new HashMap<>();
        try {
            ExifInterface exif = new ExifInterface(photoFile.getAbsolutePath());
            dynamicData.put("exif_iso", exif.getAttributeInt(ExifInterface.TAG_ISO, 0));
            dynamicData.put("exif_exposure_time", exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME));
            dynamicData.put("exif_f_number", exif.getAttribute(ExifInterface.TAG_F_NUMBER));
            dynamicData.put("exif_focal_length", exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH));
            dynamicData.put("exif_datetime", exif.getAttribute(ExifInterface.TAG_DATETIME));
            dynamicData.put("exif_make", exif.getAttribute(ExifInterface.TAG_MAKE));
            dynamicData.put("exif_model", exif.getAttribute(ExifInterface.TAG_MODEL));
            dynamicData.put("exif_orientation", exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0));
            dynamicData.put("exif_image_width", exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0));
            dynamicData.put("exif_image_height", exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0));
            dynamicData.put("exif_flash", exif.getAttribute(ExifInterface.TAG_FLASH));
            dynamicData.put("exif_white_balance", exif.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, 0));
        } catch (IOException e) {
            dynamicData.put("exif_error", e.getMessage());
        }
        allData.put("dynamic_metadata", dynamicData);

        // 3. Sensors
        allData.put("sensors", new HashMap<>(sensorReadings));

        // 4. Location
        if (lastLocation != null) {
            Map<String, Object> loc = new HashMap<>();
            loc.put("latitude", lastLocation.getLatitude());
            loc.put("longitude", lastLocation.getLongitude());
            loc.put("altitude", lastLocation.getAltitude());
            loc.put("accuracy", lastLocation.getAccuracy());
            loc.put("bearing", lastLocation.getBearing());
            loc.put("speed", lastLocation.getSpeed());
            loc.put("provider", lastLocation.getProvider());
            loc.put("time", lastLocation.getTime());
            allData.put("location", loc);
        } else {
            allData.put("location", "Not available");
        }

        // 5. File info
        Map<String, Object> fileInfo = new HashMap<>();
        fileInfo.put("file_name", photoFile.getName());
        fileInfo.put("file_size_bytes", photoFile.length());
        fileInfo.put("can_read", photoFile.canRead());
        fileInfo.put("can_write", photoFile.canWrite());
        fileInfo.put("last_modified", new Date(photoFile.lastModified()).toString());
        allData.put("file_info", fileInfo);

        // 6. Device info
        allData.put("device", Map.of(
                "model", Build.MODEL,
                "manufacturer", Build.MANUFACTURER,
                "android", Build.VERSION.RELEASE,
                "sdk", Build.VERSION.SDK_INT
        ));

        // Save JSON
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String json = gson.toJson(allData);
            File out = new File(getFilesDir(), "camera_full_data.json");
            try (FileWriter fw = new FileWriter(out)) {
                fw.write(json);
            }
            status.setText("Full data saved to:\n" + out.getAbsolutePath() + "\n\nTap to share.");
            Toast.makeText(this, "Full JSON saved!", Toast.LENGTH_LONG).show();
            status.setOnClickListener(v -> shareFile(out));
        } catch (IOException e) {
            status.setText("Error saving JSON: " + e.getMessage());
            logError(e);
        }
    }

    private Map<String, Object> getStaticCameraInfo() {
        Map<String, Object> result = new HashMap<>();
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String[] ids = manager.getCameraIdList();
            for (String id : ids) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                Map<String, Object> info = new HashMap<>();

                // Lens facing
                try { info.put("LENS_FACING", chars.get(CameraCharacteristics.LENS_FACING) == 0 ? "front" : "back"); } catch (Exception ignored) {}
                // Physical size
                try { SizeF size = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                    if (size != null) { info.put("SENSOR_INFO_PHYSICAL_SIZE_WIDTH_MM", size.getWidth());
                        info.put("SENSOR_INFO_PHYSICAL_SIZE_HEIGHT_MM", size.getHeight()); } } catch (Exception ignored) {}
                // Pixel array
                try { Size size = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                    if (size != null) { info.put("SENSOR_INFO_PIXEL_ARRAY_SIZE_WIDTH", size.getWidth());
                        info.put("SENSOR_INFO_PIXEL_ARRAY_SIZE_HEIGHT", size.getHeight()); } } catch (Exception ignored) {}
                // Active array
                try { android.graphics.Rect rect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                    if (rect != null) { info.put("SENSOR_INFO_ACTIVE_ARRAY_SIZE_LEFT", rect.left);
                        info.put("SENSOR_INFO_ACTIVE_ARRAY_SIZE_TOP", rect.top);
                        info.put("SENSOR_INFO_ACTIVE_ARRAY_SIZE_RIGHT", rect.right);
                        info.put("SENSOR_INFO_ACTIVE_ARRAY_SIZE_BOTTOM", rect.bottom); } } catch (Exception ignored) {}
                // CFA
                try { Integer cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
                    if (cfa != null) { String[] cfaNames = {"RGGB","GRBG","GBRG","BGGR","RGBIR"};
                        info.put("SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NAME", (cfa>=0 && cfa<cfaNames.length)?cfaNames[cfa]:"UNKNOWN");
                        info.put("SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_VALUE", cfa); } } catch (Exception ignored) {}
                // Sensitivity range
                try { Range<Integer> range = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                    if (range != null) { info.put("SENSOR_INFO_SENSITIVITY_RANGE_MIN", range.getLower());
                        info.put("SENSOR_INFO_SENSITIVITY_RANGE_MAX", range.getUpper()); } } catch (Exception ignored) {}
                // Exposure time range
                try { Range<Long> range = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                    if (range != null) { info.put("SENSOR_INFO_EXPOSURE_TIME_RANGE_MIN_NS", range.getLower());
                        info.put("SENSOR_INFO_EXPOSURE_TIME_RANGE_MAX_NS", range.getUpper()); } } catch (Exception ignored) {}
                // Distortion
                try { float[] dist = chars.get(CameraCharacteristics.LENS_DISTORTION);
                    if (dist != null) info.put("LENS_DISTORTION", dist); } catch (Exception ignored) {}
                // Apertures
                try { float[] apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
                    if (apertures != null) info.put("LENS_INFO_AVAILABLE_APERTURES", apertures); } catch (Exception ignored) {}
                // Focal lengths
                try { float[] fls = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    if (fls != null) info.put("LENS_INFO_AVAILABLE_FOCAL_LENGTHS", fls); } catch (Exception ignored) {}
                // Hyperfocal
                try { Float hyp = chars.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE);
                    if (hyp != null) info.put("LENS_INFO_HYPERFOCAL_DISTANCE", hyp); } catch (Exception ignored) {}
                // Min focus
                try { Float min = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                    if (min != null) info.put("LENS_INFO_MINIMUM_FOCUS_DISTANCE", min); } catch (Exception ignored) {}
                // Hardware level
                try { Integer level = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                    if (level != null) { String[] levels = {"LIMITED","FULL","LEGACY","LEVEL_3"};
                        info.put("INFO_SUPPORTED_HARDWARE_LEVEL_NAME", (level>=0 && level<levels.length)?levels[level]:"UNKNOWN");
                        info.put("INFO_SUPPORTED_HARDWARE_LEVEL_VALUE", level); } } catch (Exception ignored) {}
                // Flash
                try { Boolean flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (flash != null) info.put("FLASH_INFO_AVAILABLE", flash); } catch (Exception ignored) {}
                // Orientation
                try { Integer orient = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    if (orient != null) info.put("SENSOR_ORIENTATION", orient); } catch (Exception ignored) {}
                // Resolutions & RAW
                try { StreamConfigurationMap config = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (config != null) {
                        Size[] jpegSizes = config.getOutputSizes(ImageFormat.JPEG);
                        if (jpegSizes != null) {
                            List<Map<String, Integer>> res = new ArrayList<>();
                            for (Size s : jpegSizes) { Map<String, Integer> r = new HashMap<>();
                                r.put("width", s.getWidth()); r.put("height", s.getHeight()); res.add(r); }
                            info.put("JPEG_RESOLUTIONS", res);
                        }
                        Size[] rawSizes = config.getOutputSizes(ImageFormat.RAW_SENSOR);
                        info.put("RAW_SUPPORTED", rawSizes != null && rawSizes.length > 0);
                        if (rawSizes != null && rawSizes.length > 0) {
                            List<Map<String, Integer>> res = new ArrayList<>();
                            for (Size s : rawSizes) { Map<String, Integer> r = new HashMap<>();
                                r.put("width", s.getWidth()); r.put("height", s.getHeight()); res.add(r); }
                            info.put("RAW_RESOLUTIONS", res);
                        }
                    } } catch (Exception ignored) {}
                // Capabilities
                try { int[] caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                    if (caps != null) info.put("REQUEST_AVAILABLE_CAPABILITIES", caps); } catch (Exception ignored) {}

                result.put("camera_" + id, info);
            }
        } catch (CameraAccessException e) {
            result.put("error", e.getMessage());
        }
        return result;
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
