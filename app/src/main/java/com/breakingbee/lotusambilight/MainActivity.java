package com.breakingbee.lotusambilight;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.Toast;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 110;
    private static final int REQUEST_SCREEN_CAPTURE = 111;
    private static final UUID LOTUS_SERVICE =
            UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID LOTUS_RGB_CHARACTERISTIC =
            UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, BluetoothDevice> discoveredDevices = new LinkedHashMap<>();

    private TextView statusView;
    private TextView selectedView;
    private TextView colorView;
    private LinearLayout deviceList;
    private BluetoothLeScanner scanner;
    private BluetoothDevice selectedDevice;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rgbCharacteristic;
    private boolean scanning;
    private boolean connected;

    private HandlerThread captureThread;
    private Handler captureHandler;
    private MediaProjection projection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private float smoothR;
    private float smoothG;
    private float smoothB;
    private volatile long lastLedWriteMs;
    private volatile long lastUiUpdateMs;
    private volatile boolean firstCaptureFrameReceived;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        captureThread = new HandlerThread("LotusAmbilightCapture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        buildUi();
        setStatus("Ready. Turn on the LED strip, then scan.");
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(20), dp(28), dp(24));
        root.setBackgroundColor(Color.rgb(16, 20, 29));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("LOTUS AMBILIGHT");
        title.setTextColor(Color.WHITE);
        title.setTextSize(25);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Mi TV Stick 4K  •  Lotus Lantern BLE  •  Screen colour sync");
        subtitle.setTextColor(Color.LTGRAY);
        subtitle.setTextSize(14);
        addGap(root, 5);
        root.addView(subtitle);

        addGap(root, 18);
        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(190, 205, 225));
        statusView.setTextSize(16);
        root.addView(statusView);

        addGap(root, 12);
        Button scanButton = button("1. Scan for Lotus Lantern");
        scanButton.setOnClickListener(v -> startScan());
        root.addView(scanButton);

        selectedView = new TextView(this);
        selectedView.setTextColor(Color.WHITE);
        selectedView.setTextSize(15);
        selectedView.setText("Selected device: none");
        addGap(root, 8);
        root.addView(selectedView);

        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        root.addView(deviceList);

        Button connectButton = button("2. Connect to selected strip");
        connectButton.setOnClickListener(v -> connectSelected());
        addGap(root, 8);
        root.addView(connectButton);

        addGap(root, 14);
        TextView testLabel = section("Bluetooth colour test");
        root.addView(testLabel);
        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"RED", "GREEN", "BLUE", "WHITE"};
        int[][] colours = {{255, 0, 0}, {0, 255, 0}, {0, 0, 255}, {255, 255, 255}};
        for (int i = 0; i < labels.length; i++) {
            final int[] rgb = colours[i];
            Button b = button(labels[i]);
            b.setOnClickListener(v -> sendRgb(rgb[0], rgb[1], rgb[2], true));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            testRow.addView(b, lp);
        }
        root.addView(testRow);

        addGap(root, 16);
        root.addView(section("Screen colour sync"));
        colorView = new TextView(this);
        colorView.setText("Waiting for screen capture");
        colorView.setTextColor(Color.WHITE);
        colorView.setTextSize(17);
        colorView.setGravity(Gravity.CENTER);
        colorView.setBackgroundColor(Color.rgb(35, 42, 54));
        LinearLayout.LayoutParams colorLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        colorLp.setMargins(0, dp(8), 0, dp(8));
        root.addView(colorView, colorLp);

        Button startButton = button("3. Start screen sync");
        startButton.setOnClickListener(v -> requestScreenCapture());
        root.addView(startButton);

        Button stopButton = button("Stop screen sync");
        stopButton.setOnClickListener(v -> stopScreenCapture());
        addGap(root, 6);
        root.addView(stopButton);

        addGap(root, 14);
        TextView note = new TextView(this);
        note.setText("Note: Android will ask you to allow screen capture. Some protected streaming apps may provide a black frame. Keep this app running during this first test.");
        note.setTextColor(Color.rgb(165, 174, 190));
        note.setTextSize(13);
        root.addView(note);

        setContentView(scroll);
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        return b;
    }

    private TextView section(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(18);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private void addGap(LinearLayout parent, int heightDp) {
        View gap = new View(this);
        parent.addView(gap, new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void setStatus(String value) {
        runOnUiThread(() -> {
            if (statusView != null) statusView.setText(value);
        });
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            }, REQUEST_BLUETOOTH_PERMISSIONS);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_BLUETOOTH_PERMISSIONS);
        }
    }

    private void startScan() {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions();
            return;
        }
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            setStatus("Bluetooth is unavailable or switched off. Enable Bluetooth and retry.");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            setStatus("BLE scanner is unavailable on this device.");
            return;
        }

        discoveredDevices.clear();
        deviceList.removeAllViews();
        selectedDevice = null;
        selectedView.setText("Selected device: none");
        try {
            scanner.startScan(scanCallback);
            scanning = true;
            setStatus("Scanning for nearby Lotus Lantern / BLEDOM lights for 12 seconds…");
            mainHandler.postDelayed(this::stopScan, 12000);
        } catch (SecurityException e) {
            setStatus("Bluetooth permission was not granted: " + e.getMessage());
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null) return;
            String name = null;
            if (result.getScanRecord() != null) name = result.getScanRecord().getDeviceName();
            if (name == null || name.trim().isEmpty()) {
                try {
                    name = device.getName();
                } catch (SecurityException ignored) {
                    name = null;
                }
            }
            if (name == null || name.trim().isEmpty() || !looksLikeLed(name)) return;

            final String deviceName = name;
            final String address = device.getAddress();
            runOnUiThread(() -> addDiscoveredDevice(device, deviceName, address));
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            setStatus("BLE scan failed (code " + errorCode + "). Try again.");
        }
    };

    private boolean looksLikeLed(String name) {
        String n = name.toUpperCase(Locale.ROOT);
        return n.contains("ELK") || n.contains("BLEDOM") || n.contains("LOTUS")
                || n.startsWith("XSL-") || n.startsWith("CLK-")
                || n.contains("LED") || n.contains("RGB") || n.contains("STRIP")
                || n.contains("LIGHT");
    }

    private void addDiscoveredDevice(BluetoothDevice device, String name, String address) {
        if (discoveredDevices.containsKey(address)) return;
        discoveredDevices.put(address, device);
        if (selectedDevice == null) {
            selectedDevice = device;
            selectedView.setText("Selected device: " + name + " (" + address + ")");
        }
        Button item = button(name + "  •  " + address);
        item.setOnClickListener(v -> {
            selectedDevice = device;
            selectedView.setText("Selected device: " + name + " (" + address + ")");
        });
        deviceList.addView(item);
        setStatus("Found " + discoveredDevices.size() + " possible LED controller(s). Select yours, then connect.");
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        try {
            if (scanner != null && hasBluetoothPermissions()) scanner.stopScan(scanCallback);
        } catch (SecurityException ignored) {
            // Permission may have been revoked while scanning.
        }
        if (discoveredDevices.isEmpty()) {
            setStatus("No matching LED device found. Make sure the strip is powered and close the Lotus Lantern phone app, then scan again.");
        } else {
            setStatus("Scan complete. Select your strip and press Connect.");
        }
    }

    private void connectSelected() {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions();
            return;
        }
        if (selectedDevice == null) {
            setStatus("Scan first and select your Lotus Lantern strip.");
            return;
        }
        try {
            if (gatt != null) {
                gatt.close();
                gatt = null;
            }
            connected = false;
            rgbCharacteristic = null;
            setStatus("Connecting to " + selectedDevice.getAddress() + "…");
            gatt = selectedDevice.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            setStatus("Bluetooth connect permission was denied: " + e.getMessage());
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt bluetoothGatt, int status, int newState) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                setStatus("Connected. Discovering Lotus Lantern service…");
                try {
                    bluetoothGatt.discoverServices();
                } catch (SecurityException e) {
                    setStatus("Cannot discover BLE services: " + e.getMessage());
                }
            } else {
                connected = false;
                rgbCharacteristic = null;
                setStatus("Bluetooth disconnected (status " + status + "). Press Connect to retry.");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt bluetoothGatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setStatus("Service discovery failed (status " + status + ").");
                return;
            }
            BluetoothGattService service = bluetoothGatt.getService(LOTUS_SERVICE);
            if (service == null) {
                setStatus("Connected, but service FFF0 was not found. This controller may use a different protocol.");
                return;
            }
            rgbCharacteristic = service.getCharacteristic(LOTUS_RGB_CHARACTERISTIC);
            if (rgbCharacteristic == null) {
                setStatus("Service FFF0 found, but write characteristic FFF3 is missing.");
                return;
            }
            connected = true;
            setStatus("Lotus Lantern connected: FFF0 / FFF3 ready. Try the colour buttons.");
        }
    };

    private void sendRgb(int r, int g, int b, boolean notify) {
        if (!connected || gatt == null || rgbCharacteristic == null) {
            if (notify) setStatus("Connect to the Lotus Lantern strip first.");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!notify && now - lastLedWriteMs < 220) return;
        lastLedWriteMs = now;

        byte[] frame = new byte[]{
                (byte) 0x7E, 0x07, 0x05, 0x03,
                (byte) clamp(r), (byte) clamp(g), (byte) clamp(b),
                0x10, (byte) 0xEF
        };
        int properties = rgbCharacteristic.getProperties();
        int writeType = (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        try {
            boolean accepted;
            if (Build.VERSION.SDK_INT >= 33) {
                int result = gatt.writeCharacteristic(rgbCharacteristic, frame, writeType);
                accepted = result == BluetoothStatusCodes.SUCCESS;
            } else {
                rgbCharacteristic.setWriteType(writeType);
                rgbCharacteristic.setValue(frame);
                accepted = gatt.writeCharacteristic(rgbCharacteristic);
            }
            if (notify) {
                String hex = String.format(Locale.ROOT, "#%02X%02X%02X", clamp(r), clamp(g), clamp(b));
                setStatus(accepted ? "Sent " + hex + " to Lotus Lantern." : "Bluetooth rejected the colour write.");
            }
        } catch (SecurityException e) {
            setStatus("Bluetooth write permission was denied: " + e.getMessage());
        }
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private void requestScreenCapture() {
        if (projection != null) {
            setStatus("Screen capture is already active. Press Home and open your video app.");
            return;
        }
        MediaProjectionManager manager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            setStatus("Android screen capture is unavailable on this device.");
            return;
        }
        try {
            setStatus("Waiting for Android permission… choose \\"Start now\\" on the system prompt.");
            Toast.makeText(this, "Approve screen capture, then return to Lotus Ambilight.", Toast.LENGTH_LONG).show();
            Log.i("LotusAmbilight", "Launching MediaProjection permission prompt");
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
        } catch (RuntimeException e) {
            Log.e("LotusAmbilight", "Could not launch capture permission prompt", e);
            setStatus("Could not open screen-capture prompt: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SCREEN_CAPTURE) return;
        Log.i("LotusAmbilight", "MediaProjection result received: resultCode=" + resultCode
                + ", dataPresent=" + (data != null));
        if (resultCode != RESULT_OK || data == null) {
            setStatus("Screen-capture permission was cancelled or not granted.");
            Toast.makeText(this, "Screen capture was not allowed.", Toast.LENGTH_LONG).show();
            return;
        }
        MediaProjectionManager manager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            setStatus("Android MediaProjection service is unavailable.");
            return;
        }
        try {
            setStatus("Permission accepted. Starting screen capture…");
            projection = manager.getMediaProjection(resultCode, data);
            if (projection == null) {
                setStatus("Android returned no MediaProjection. Check screen-capture permission.");
                return;
            }
            beginScreenCapture();
        } catch (RuntimeException e) {
            Log.e("LotusAmbilight", "Could not initialize MediaProjection", e);
            releaseCaptureResources(true);
            setStatus("Could not start screen capture: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            Toast.makeText(this, "Screen capture failed. Check Lotus Ambilight status.", Toast.LENGTH_LONG).show();
        }
    }

    private void beginScreenCapture() {
        if (projection == null) {
            setStatus("Screen capture failed: no MediaProjection token.");
            return;
        }
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            setStatus("Could not read display dimensions.");
            return;
        }

        float scale = Math.min(640f / metrics.widthPixels, 360f / metrics.heightPixels);
        int width = Math.max(2, Math.round(metrics.widthPixels * scale));
        int height = Math.max(2, Math.round(metrics.heightPixels * scale));

        try {
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
            imageReader.setOnImageAvailableListener(this::handleScreenFrame, captureHandler);
        } catch (RuntimeException e) {
            Log.e("LotusAmbilight", "Could not create ImageReader", e);
            releaseCaptureResources(true);
            setStatus("Could not create screen reader: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            return;
        }

        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                runOnUiThread(() -> {
                    releaseCaptureResources(false);
                    setStatus("Android stopped screen capture.");
                });
            }
        }, mainHandler);

        try {
            virtualDisplay = projection.createVirtualDisplay(
                    "LotusAmbilight",
                    width,
                    height,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    captureHandler
            );
        } catch (RuntimeException e) {
            Log.e("LotusAmbilight", "createVirtualDisplay failed", e);
            releaseCaptureResources(true);
            setStatus("Could not start display capture: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            return;
        }

        if (virtualDisplay == null) {
            releaseCaptureResources(true);
            setStatus("Android did not create the screen-capture display.");
            return;
        }
        smoothR = smoothG = smoothB = 0;
        lastLedWriteMs = 0;
        lastUiUpdateMs = 0;
        firstCaptureFrameReceived = false;
        setStatus("Capture started. Waiting for the first screen frame…");
        Log.i("LotusAmbilight", "Virtual display started at " + width + "x" + height);
        mainHandler.postDelayed(() -> {
            if (projection != null && !firstCaptureFrameReceived) {
                setStatus("Capture is active, but Android has not delivered frames yet. Check logcat; protected video may be blank.");
            }
        }, 3000);
    }

    private void handleScreenFrame(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length == 0) return;

            ByteBuffer buffer = planes[0].getBuffer();
            int rowStride = planes[0].getRowStride();
            int pixelStride = planes[0].getPixelStride();
            int width = image.getWidth();
            int height = image.getHeight();
            int stepX = Math.max(1, width / 24);
            int stepY = Math.max(1, height / 14);
            long red = 0, green = 0, blue = 0, count = 0;

            for (int y = stepY / 2; y < height; y += stepY) {
                for (int x = stepX / 2; x < width; x += stepX) {
                    int offset = y * rowStride + x * pixelStride;
                    if (offset + 2 >= buffer.limit()) continue;
                    red += buffer.get(offset) & 0xFF;
                    green += buffer.get(offset + 1) & 0xFF;
                    blue += buffer.get(offset + 2) & 0xFF;
                    count++;
                }
            }
            if (count == 0) return;

            int r = (int) (red / count);
            int g = (int) (green / count);
            int b = (int) (blue / count);
            smoothR += (r - smoothR) * 0.24f;
            smoothG += (g - smoothG) * 0.24f;
            smoothB += (b - smoothB) * 0.24f;
            int sr = clamp(Math.round(smoothR));
            int sg = clamp(Math.round(smoothG));
            int sb = clamp(Math.round(smoothB));

            firstCaptureFrameReceived = true;
            long now = SystemClock.elapsedRealtime();
            if (connected && now - lastLedWriteMs >= 220) {
                // sendRgb() owns lastLedWriteMs. Do not set it before posting,
                // otherwise sendRgb() sees a just-written timestamp and rejects
                // every capture-derived colour as too soon.
                mainHandler.post(() -> sendRgb(sr, sg, sb, false));
            }
            if (now - lastUiUpdateMs >= 500) {
                lastUiUpdateMs = now;
                String hex = String.format(Locale.ROOT, "#%02X%02X%02X", sr, sg, sb);
                int previewColor = Color.rgb(sr, sg, sb);
                mainHandler.post(() -> {
                    if (colorView != null) {
                        colorView.setText(hex);
                        colorView.setBackgroundColor(previewColor);
                    }
                    if (projection != null && firstCaptureFrameReceived) {
                        setStatus(connected
                                ? "Screen frames arriving • LEDs following " + hex
                                : "Screen frames arriving • connect the LED strip to sync colours");
                    }
                });
            }
        } catch (IllegalStateException ignored) {
            // The reader may close while a frame is being delivered.
        } finally {
            if (image != null) image.close();
        }
    }

    private void stopScreenCapture() {
        releaseCaptureResources(true);
        setStatus("Screen sync stopped.");
    }

    private void releaseCaptureResources(boolean stopProjection) {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = null;
        }
        MediaProjection oldProjection = projection;
        projection = null;
        firstCaptureFrameReceived = false;
        if (stopProjection && oldProjection != null) {
            oldProjection.stop();
        }
        if (colorView != null) {
            colorView.setText("Screen sync stopped");
            colorView.setBackgroundColor(Color.rgb(35, 42, 54));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            boolean granted = grantResults.length > 0;
            for (int result : grantResults) granted &= result == PackageManager.PERMISSION_GRANTED;
            setStatus(granted ? "Bluetooth permission granted. Press Scan again."
                    : "Bluetooth permission is required to find and control the LED strip.");
        }
    }

    @Override
    protected void onDestroy() {
        stopScan();
        releaseCaptureResources(true);
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (SecurityException ignored) {
                // App is closing.
            }
            gatt = null;
        }
        if (captureThread != null) captureThread.quitSafely();
        super.onDestroy();
    }
}
