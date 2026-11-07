package com.example.customcam;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.hardware.Camera;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class CameraActivity extends Activity implements SurfaceHolder.Callback {

    static final String[] SIZE_NAMES = {"Small", "Medium", "Large"};
    static final int[] SIZE_DP = {56, 76, 100};
    static final String[] COLOR_NAMES = {"Red", "White", "Green", "Blue", "Yellow"};
    static final int[] COLORS = {0xFFE53935, 0xFFFFFFFF, 0xFF43A047, 0xFF1E88E5, 0xFFFDD835};
    static final String[] SHAPE_NAMES = {"Round", "Square"};
    static final String[] SIDE_NAMES = {"Right", "Left"};
    static final String[] FORMAT_NAMES = {"JPEG", "JPEG + RAW", "RAW only"};
    static final int[] QUALITIES = {60, 75, 90, 100};

    private Camera camera;
    private SurfaceHolder holder;
    private ShutterView shutter;
    private FrameLayout root;
    private SharedPreferences prefs;
    private boolean previewing;
    private volatile boolean busy;

    static final String[] BAR_LABELS = {"ISO", "WB", "Focus", "EV", "Flash", "Scene"};
    static final String[] BAR_KEYS = {"iso", "wb", "focus", "ev", "flash", "scene"};
    private LinearLayout bar;
    private FocusView focusView;
    private final Handler handler = new Handler();
    private boolean focusing;
    private boolean noAfToastShown;
    private OrientationEventListener orientListener;
    private int deviceOrientation = 0;
    private HorizontalScrollView barScroll;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("customcam", 0);

        root = new FrameLayout(this);
        SurfaceView sv = new SurfaceView(this);
        holder = sv.getHolder();
        holder.addCallback(this);
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS); // required before Android 3.0
        root.addView(sv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.FILL_PARENT, FrameLayout.LayoutParams.FILL_PARENT));
        sv.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_UP) focusOnly(e.getX(), e.getY());
                return true;
            }
        });
        focusView = new FocusView(this);
        root.addView(focusView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.FILL_PARENT, FrameLayout.LayoutParams.FILL_PARENT));

        shutter = new ShutterView(this);
        shutter.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                capture();
            }
        });
        root.addView(shutter);

        Button menu = new Button(this);
        menu.setText("Menu");
        menu.setTextSize(12);
        menu.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showSettings();
            }
        });
        root.addView(menu, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT));

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        barScroll = new HorizontalScrollView(this);
        barScroll.setBackgroundColor(0xAA000000);
        barScroll.setHorizontalScrollBarEnabled(false);
        barScroll.addView(bar);
        root.addView(barScroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.FILL_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        orientListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                deviceOrientation = ((o + 45) / 90 * 90) % 360;
            }
        };

        setContentView(root);
        applyShutterStyle();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (orientListener.canDetectOrientation()) orientListener.enable();
    }

    @Override
    protected void onPause() {
        super.onPause();
        orientListener.disable();
    }

    // Preview rotation so the picture is upright whichever way the phone is held
    private int displayOrientation() {
        int deg = 0;
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90: deg = 90; break;
            case Surface.ROTATION_180: deg = 180; break;
            case Surface.ROTATION_270: deg = 270; break;
        }
        return (90 - deg + 360) % 360;
    }

    // Rotation stored in the saved JPEG (EXIF) so viewers show it upright
    private int photoRotation() {
        return (90 + deviceOrientation + prefs.getInt("rotfix", 0) * 90) % 360;
    }

    // Box in the middle of the preview: white = focusing, green = locked, red = failed
    static class FocusView extends View {
        private final Paint paint = new Paint();
        private int state; // 0 hidden, 1 focusing, 2 ok, 3 failed
        private float px = -1, py = -1;

        void setPoint(float x, float y) {
            px = x;
            py = y;
        }

        FocusView(android.content.Context c) {
            super(c);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3);
        }

        void setState(int s) {
            state = s;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (state == 0) return;
            paint.setColor(state == 2 ? 0xFF43A047 : state == 3 ? 0xFFE53935 : 0xFFFFFFFF);
            float cx = px >= 0 ? px : getWidth() / 2f;
            float cy = py >= 0 ? py : getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) / 7f;
            c.drawRect(cx - r, cy - r, cx + r, cy + r, paint);
        }
    }

    private final Runnable hideFocus = new Runnable() {
        public void run() {
            focusView.setState(0);
        }
    };

    private boolean supportsAutoFocus(Camera.Parameters p) {
        List<String> fm = p.getSupportedFocusModes();
        return fm != null && (fm.contains(Camera.Parameters.FOCUS_MODE_AUTO)
                || fm.contains(Camera.Parameters.FOCUS_MODE_MACRO));
    }

    private boolean noPointToastShown;

    // Ask the driver to focus at a screen point. Android 2.2 has no such API, so this only
    // works if the phone's driver happens to support the newer one; otherwise returns false.
    private boolean setFocusPoint(Camera.Parameters p, float x, float y) {
        try {
            int w = focusView.getWidth(), h = focusView.getHeight();
            if (w == 0 || h == 0) return false;
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(displayOrientation());
            m.postScale(w / 2000f, h / 2000f);
            m.postTranslate(w / 2f, h / 2f);
            android.graphics.Matrix inv = new android.graphics.Matrix();
            if (!m.invert(inv)) return false;
            float[] pt = {x, y};
            inv.mapPoints(pt);
            int cx = (int) pt[0], cy = (int) pt[1];
            android.graphics.Rect r = new android.graphics.Rect(
                    Math.max(-1000, cx - 100), Math.max(-1000, cy - 100),
                    Math.min(1000, cx + 100), Math.min(1000, cy + 100));
            Class<?> areaCls = Class.forName("android.hardware.Camera$Area");
            Object area = areaCls.getConstructor(android.graphics.Rect.class, int.class)
                    .newInstance(r, 1000);
            List<Object> list = new ArrayList<Object>();
            list.add(area);
            Camera.Parameters.class.getMethod("setFocusAreas", List.class).invoke(p, list);
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    // Tap on the preview: autofocus (at the tapped point if the driver allows, else the centre)
    private void focusOnly(float x, float y) {
        if (camera == null || busy || !previewing || focusing) return;
        try {
            Camera.Parameters p = camera.getParameters();
            if (!supportsAutoFocus(p)) {
                if (!noAfToastShown) {
                    noAfToastShown = true;
                    Toast.makeText(this, "This camera reports no autofocus (fixed focus)",
                            Toast.LENGTH_LONG).show();
                }
                return;
            }
            String fmode = p.getFocusMode();
            if (!Camera.Parameters.FOCUS_MODE_AUTO.equals(fmode)
                    && !Camera.Parameters.FOCUS_MODE_MACRO.equals(fmode)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }
            boolean pointed = setFocusPoint(p, x, y);
            camera.setParameters(p);
            if (pointed) {
                focusView.setPoint(x, y);
            } else {
                focusView.setPoint(-1, -1);
                if (!noPointToastShown) {
                    noPointToastShown = true;
                    Toast.makeText(this, "This phone can't focus on a tapped point; using the centre",
                            Toast.LENGTH_LONG).show();
                }
            }
            focusing = true;
            handler.removeCallbacks(hideFocus);
            focusView.setState(1);
            camera.cancelAutoFocus();
            camera.autoFocus(new Camera.AutoFocusCallback() {
                public void onAutoFocus(boolean ok, Camera c) {
                    focusing = false;
                    focusView.setState(ok ? 2 : 3);
                    handler.postDelayed(hideFocus, 900);
                }
            });
        } catch (Exception e) {
            focusing = false;
            focusView.setState(0);
        }
    }

    private void showParams() {
        if (camera == null) return;
        String text = camera.getParameters().flatten().replace(";", "\n");
        new AlertDialog.Builder(this)
                .setTitle("Camera parameters")
                .setMessage(text)
                .setPositiveButton("Close", null)
                .show();
    }

    // ---------------- shutter button ----------------
    static class ShutterView extends View {
        private final Paint paint = new Paint();
        private int color = 0xFFE53935;
        private boolean square;

        ShutterView(android.content.Context c) {
            super(c);
            paint.setAntiAlias(true);
            setClickable(true);
        }

        void setStyle(int color, boolean square) {
            this.color = color;
            this.square = square;
            invalidate();
        }

        @Override
        public void setPressed(boolean p) {
            super.setPressed(p);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(cx, cy);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(r * 0.12f);
            paint.setColor(0xFFFFFFFF);
            c.drawCircle(cx, cy, r * 0.92f, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            float ir = r * (isPressed() ? 0.55f : 0.72f);
            if (square) {
                c.drawRect(cx - ir, cy - ir, cx + ir, cy + ir, paint);
            } else {
                c.drawCircle(cx, cy, ir, paint);
            }
        }
    }

    private void applyShutterStyle() {
        float density = getResources().getDisplayMetrics().density;
        int px = (int) (SIZE_DP[prefs.getInt("size", 1)] * density + 0.5f);
        int margin = (int) (8 * density + 0.5f);
        int side = prefs.getInt("side", 0) == 1 ? Gravity.LEFT : Gravity.RIGHT;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px, px,
                side | Gravity.CENTER_VERTICAL);
        lp.setMargins(margin, margin, margin, margin);
        shutter.setLayoutParams(lp);
        shutter.setStyle(COLORS[prefs.getInt("color", 0)], prefs.getInt("shape", 0) == 1);
    }

    // ---------------- camera lifecycle ----------------
    public void surfaceCreated(SurfaceHolder h) {
        try {
            camera = Camera.open();
            camera.setPreviewDisplay(holder);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open camera", Toast.LENGTH_LONG).show();
            closeCamera();
        }
    }

    public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
        startPreview();
    }

    public void surfaceDestroyed(SurfaceHolder h) {
        closeCamera();
    }

    private void closeCamera() {
        if (camera != null) {
            try {
                camera.stopPreview();
            } catch (Exception ignored) {
            }
            camera.release();
            camera = null;
        }
        previewing = false;
        busy = false;
    }

    private void startPreview() {
        if (camera == null) return;
        try {
            focusing = false;
            if (previewing) camera.stopPreview();
            configure();
            camera.setDisplayOrientation(displayOrientation());
            camera.startPreview();
            previewing = true;
            busy = false;
            refreshBar();
        } catch (Exception e) {
            Toast.makeText(this, "Preview failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void configure() {
        try {
            Camera.Parameters p = camera.getParameters();

            // picture size: saved choice, else the largest
            List<Camera.Size> pics = p.getSupportedPictureSizes();
            String want = prefs.getString("picsize", null);
            Camera.Size pick = null;
            if (pics != null) {
                for (Camera.Size s : pics) {
                    if (want != null && want.equals(s.width + "x" + s.height)) {
                        pick = s;
                        break;
                    }
                }
                if (pick == null) {
                    for (Camera.Size s : pics) {
                        if (pick == null || s.width * s.height > pick.width * pick.height) pick = s;
                    }
                }
            }
            if (pick != null) p.setPictureSize(pick.width, pick.height);

            // preview size: biggest 4:3 up to 640 wide (also the raw YUV size)
            List<Camera.Size> prevs = p.getSupportedPreviewSizes();
            Camera.Size pv = null;
            if (prevs != null) {
                for (Camera.Size s : prevs) {
                    if (s.width <= 640 && s.width * 3 == s.height * 4
                            && (pv == null || s.width > pv.width)) pv = s;
                }
                if (pv == null) {
                    for (Camera.Size s : prevs) {
                        if (s.width <= 640 && (pv == null || s.width > pv.width)) pv = s;
                    }
                }
                if (pv == null && prevs.size() > 0) pv = prevs.get(0);
            }
            if (pv != null) p.setPreviewSize(pv.width, pv.height);

            p.setJpegQuality(QUALITIES[prefs.getInt("q", 2)]);
            p.setRotation(photoRotation());

            String fx = prefs.getString("fx", null);
            List<String> fxs = p.getSupportedColorEffects();
            if (fx != null && fxs != null && fxs.contains(fx)) p.setColorEffect(fx);

            List<String> fm = p.getSupportedFocusModes();
            if (fm != null && fm.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }
            int[] order = {5, 0, 1, 2, 3, 4}; // scene first: it can override the others
            for (int k = 0; k < order.length; k++) {
                int i = order[k];
                String v = prefs.getString(BAR_KEYS[i], null);
                List<String> opts = getOptions(p, i);
                if (v != null && opts != null && opts.contains(v)) applySetting(p, i, v);
            }
            camera.setParameters(p);
        } catch (Exception ignored) {
            // some drivers reject certain parameters; keep going with defaults
        }
    }

    // ---------------- capture ----------------
    private void capture() {
        if (camera == null || busy || !previewing) return;
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            Toast.makeText(this, "SD card not available", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        final int fmt = prefs.getInt("fmt", 0);
        try {
            Camera.Parameters rp = camera.getParameters();
            rp.setRotation(photoRotation());
            camera.setParameters(rp);
        } catch (Exception ignored) {
        }
        try {
            String fmode = camera.getParameters().getFocusMode();
            if (Camera.Parameters.FOCUS_MODE_AUTO.equals(fmode)
                    || Camera.Parameters.FOCUS_MODE_MACRO.equals(fmode)) {
                final boolean[] done = {false};
                final Runnable go = new Runnable() {
                    public void run() {
                        if (done[0]) return;
                        done[0] = true;
                        focusing = false;
                        shoot(fmt);
                        handler.postDelayed(hideFocus, 700);
                    }
                };
                final Runnable timeout = new Runnable() {
                    public void run() {
                        if (camera != null) {
                            try {
                                camera.cancelAutoFocus();
                            } catch (Exception ignored) {
                            }
                        }
                        focusView.setState(3);
                        go.run();
                    }
                };
                focusing = true;
                handler.removeCallbacks(hideFocus);
                focusView.setPoint(-1, -1);
                focusView.setState(1);
                camera.cancelAutoFocus();
                handler.postDelayed(timeout, 3000);
                camera.autoFocus(new Camera.AutoFocusCallback() {
                    public void onAutoFocus(boolean ok, Camera c) {
                        handler.removeCallbacks(timeout);
                        focusView.setState(ok ? 2 : 3);
                        go.run();
                    }
                });
            } else {
                shoot(fmt);
            }
        } catch (Exception e) {
            focusing = false;
            busy = false;
        }
    }

    private void shoot(final int fmt) {
        if (camera == null) {
            busy = false;
            return;
        }
        final String base = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        try {
            if (fmt == 0) {
                takeJpeg(base, true, false);
                return;
            }
            final Camera.Size ps = camera.getParameters().getPreviewSize();
            camera.setOneShotPreviewCallback(new Camera.PreviewCallback() {
                public void onPreviewFrame(byte[] data, Camera c) {
                    if (data != null) {
                        saveAsync(base + "_" + ps.width + "x" + ps.height + "_NV21.raw", data);
                    }
                    takeJpeg(base, fmt == 1, true);
                }
            });
        } catch (Exception e) {
            busy = false;
        }
    }

    private void takeJpeg(final String base, final boolean saveJpeg, final boolean wantRaw) {
        try {
            Camera.PictureCallback rawCb = null;
            if (wantRaw) {
                rawCb = new Camera.PictureCallback() {
                    public void onPictureTaken(byte[] d, Camera c) {
                        if (d != null) saveAsync(base + "_sensor.raw", d);
                    }
                };
            }
            camera.takePicture(null, rawCb, null, new Camera.PictureCallback() {
                public void onPictureTaken(byte[] d, Camera c) {
                    if (saveJpeg && d != null) saveAsync(base + ".jpg", d);
                    try {
                        c.startPreview();
                    } catch (Exception ignored) {
                    }
                    busy = false;
                }
            });
        } catch (Exception e) {
            busy = false;
        }
    }

    private void saveAsync(final String name, final byte[] data) {
        new Thread(new Runnable() {
            public void run() {
                File dir = new File(Environment.getExternalStorageDirectory(), "DCIM/CustomCam");
                dir.mkdirs();
                final File f = new File(dir, name);
                try {
                    FileOutputStream out = new FileOutputStream(f);
                    out.write(data);
                    out.close();
                    if (name.endsWith(".jpg")) {
                        MediaScannerConnection.scanFile(CameraActivity.this,
                                new String[]{f.getAbsolutePath()}, null, null);
                    }
                    runOnUiThread(new Runnable() {
                        public void run() {
                            Toast.makeText(CameraActivity.this, "Saved " + f.getName(),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            Toast.makeText(CameraActivity.this, "Save failed",
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }

    // ---------------- settings ----------------
    private void showSettings() {
        if (camera == null) return;
        Camera.Parameters p = camera.getParameters();
        String curSize = p.getPictureSize().width + "x" + p.getPictureSize().height;
        String curWb = p.getWhiteBalance() == null ? "n/a" : p.getWhiteBalance();
        String curFx = p.getColorEffect() == null ? "n/a" : p.getColorEffect();
        String[] items = {
                "Shutter size: " + SIZE_NAMES[prefs.getInt("size", 1)],
                "Shutter color: " + COLOR_NAMES[prefs.getInt("color", 0)],
                "Shutter shape: " + SHAPE_NAMES[prefs.getInt("shape", 0)],
                "Shutter side: " + SIDE_NAMES[prefs.getInt("side", 0)],
                "Save format: " + FORMAT_NAMES[prefs.getInt("fmt", 0)],
                "Picture size: " + curSize,
                "White balance: " + curWb,
                "Color effect: " + curFx,
                "JPEG quality: " + QUALITIES[prefs.getInt("q", 2)],
                "Photo rotation fix: " + (prefs.getInt("rotfix", 0) * 90) + " deg",
                "Show camera parameters"
        };
        new AlertDialog.Builder(this)
                .setTitle("Settings (tap to change)")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 10) {
                            showParams();
                        } else {
                            changeSetting(which);
                            showSettings();
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void changeSetting(int which) {
        SharedPreferences.Editor e = prefs.edit();
        boolean reconfigure = false;
        Camera.Parameters p = camera.getParameters();
        switch (which) {
            case 0:
                e.putInt("size", (prefs.getInt("size", 1) + 1) % SIZE_NAMES.length);
                break;
            case 1:
                e.putInt("color", (prefs.getInt("color", 0) + 1) % COLORS.length);
                break;
            case 2:
                e.putInt("shape", (prefs.getInt("shape", 0) + 1) % 2);
                break;
            case 3:
                e.putInt("side", (prefs.getInt("side", 0) + 1) % 2);
                break;
            case 4:
                e.putInt("fmt", (prefs.getInt("fmt", 0) + 1) % FORMAT_NAMES.length);
                break;
            case 5: {
                List<String> sizes = new ArrayList<String>();
                List<Camera.Size> ps = p.getSupportedPictureSizes();
                if (ps != null) {
                    for (Camera.Size s : ps) sizes.add(s.width + "x" + s.height);
                }
                String n = nextOf(sizes, p.getPictureSize().width + "x" + p.getPictureSize().height);
                if (n != null) e.putString("picsize", n);
                reconfigure = true;
                break;
            }
            case 6: {
                String n = nextOf(p.getSupportedWhiteBalance(), p.getWhiteBalance());
                if (n != null) e.putString("wb", n);
                reconfigure = true;
                break;
            }
            case 7: {
                String n = nextOf(p.getSupportedColorEffects(), p.getColorEffect());
                if (n != null) e.putString("fx", n);
                reconfigure = true;
                break;
            }
            case 8:
                e.putInt("q", (prefs.getInt("q", 2) + 1) % QUALITIES.length);
                reconfigure = true;
                break;
            case 9:
                e.putInt("rotfix", (prefs.getInt("rotfix", 0) + 1) % 4);
                break;
        }
        e.commit();
        if (which <= 3) applyShutterStyle();
        if (reconfigure) startPreview();
    }

    private String nextOf(List<String> list, String cur) {
        if (list == null || list.size() == 0) return null;
        int i = list.indexOf(cur);
        return list.get((i + 1) % list.size());
    }

    // ---------------- quick settings bar ----------------
    private String isoKey(Camera.Parameters p) {
        String[] keys = {"iso", "iso-speed"};
        for (int k = 0; k < keys.length; k++) {
            if (p.get(keys[k] + "-values") != null) return keys[k];
        }
        return null;
    }

    private List<String> splitValues(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        String[] parts = s.split(",");
        for (int k = 0; k < parts.length; k++) {
            String t = parts[k].trim();
            if (t.length() > 0) out.add(t);
        }
        return out;
    }

    private List<String> getOptions(Camera.Parameters p, int i) {
        switch (i) {
            case 0: {
                String key = isoKey(p);
                return key == null ? null : splitValues(p.get(key + "-values"));
            }
            case 1:
                return p.getSupportedWhiteBalance();
            case 2:
                return p.getSupportedFocusModes();
            case 3: {
                int min = p.getMinExposureCompensation();
                int max = p.getMaxExposureCompensation();
                if (max <= min) return null;
                List<String> out = new ArrayList<String>();
                for (int v = min; v <= max; v++) out.add(String.valueOf(v));
                return out;
            }
            case 4: {
                List<String> fl = p.getSupportedFlashModes();
                if (fl == null || fl.size() < 2) {
                    fl = new ArrayList<String>();
                    fl.add("off");
                    fl.add("auto");
                    fl.add("on");
                    fl.add("torch");
                }
                return fl;
            }
            case 5:
                return p.getSupportedSceneModes();
        }
        return null;
    }

    private String getCurrent(Camera.Parameters p, int i) {
        switch (i) {
            case 0: {
                String key = isoKey(p);
                String v = key == null ? null : p.get(key);
                return v == null ? "auto" : v;
            }
            case 1:
                return p.getWhiteBalance();
            case 2:
                return p.getFocusMode();
            case 3:
                return String.valueOf(p.getExposureCompensation());
            case 4: {
                String fv = p.getFlashMode();
                return fv == null ? "off" : fv;
            }
            case 5:
                return p.getSceneMode();
        }
        return null;
    }

    private void applySetting(Camera.Parameters p, int i, String v) {
        try {
            switch (i) {
                case 0: {
                    String key = isoKey(p);
                    if (key != null) p.set(key, v);
                    break;
                }
                case 1:
                    p.setWhiteBalance(v);
                    break;
                case 2:
                    p.setFocusMode(v);
                    break;
                case 3:
                    p.setExposureCompensation(Integer.parseInt(v));
                    break;
                case 4:
                    p.setFlashMode(v);
                    break;
                case 5:
                    p.setSceneMode(v);
                    break;
            }
        } catch (Exception ignored) {
        }
    }

    private String pretty(int i, String v) {
        if (v == null) return "-";
        if (i == 3) {
            int n = Integer.parseInt(v);
            return n > 0 ? "+" + n : String.valueOf(n);
        }
        return v.replace("_", " ");
    }

    private void refreshBar() {
        if (camera == null) return;
        bar.removeAllViews();
        Camera.Parameters p = camera.getParameters();
        float d = getResources().getDisplayMetrics().density;
        int shown = 0;
        for (int k = 0; k < BAR_LABELS.length; k++) {
            final int i = k;
            List<String> opts = getOptions(p, i);
            if (opts == null || opts.size() < 2) continue; // hide what this phone can't change
            String cur = pretty(i, getCurrent(p, i));
            if (cur.length() > 10) cur = cur.substring(0, 10);
            Button b = new Button(this);
            b.setText(BAR_LABELS[i] + "\n" + cur);
            b.setTextSize(11);
            b.setPadding((int) (6 * d), 0, (int) (6 * d), 0);
            b.setMinWidth((int) (56 * d));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    showPicker(i);
                }
            });
            bar.addView(b, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            shown++;
        }
        barScroll.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
    }

    private void showPicker(final int i) {
        if (camera == null) return;
        Camera.Parameters p = camera.getParameters();
        final List<String> opts = getOptions(p, i);
        if (opts == null) return;
        String[] labels = new String[opts.size()];
        for (int k = 0; k < labels.length; k++) labels[k] = pretty(i, opts.get(k));
        int sel = opts.indexOf(getCurrent(p, i));
        new AlertDialog.Builder(this)
                .setTitle(BAR_LABELS[i])
                .setSingleChoiceItems(labels, sel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        prefs.edit().putString(BAR_KEYS[i], opts.get(which)).commit();
                        d.dismiss();
                        startPreview();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent ev) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return true; // consumed so the system volume UI doesn't pop up
        }
        return super.onKeyUp(keyCode, ev);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent ev) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_CAMERA || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (ev.getRepeatCount() == 0) capture();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showSettings();
            return true;
        }
        return super.onKeyDown(keyCode, ev);
    }
}
