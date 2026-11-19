package com.kefirman9000.logolift;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_FOLDER = 1;
    private static final int REQ_PICK = 2;
    private static final String REPO = "kefirman9000/logo-lift-viewer";

    private static final int DOCK_BG = 0xF016181B;
    private static final int INK = 0xFFE9EBEE;
    private static final int MUTED = 0xFF8B929A;
    private static final int ACCENT = 0xFFFFB020;
    private static final int BTN_BG = 0xFF24282D;
    private static final int BTN_LINE = 0xFF353A40;

    private SharedPreferences prefs;
    private LogoView view;
    private LinearLayout dock;
    private TextView counter;
    private TextView bgBtn;
    private TextView invBtn;
    private TextView flipBtn;
    private TextView orderBtn;

    /** One image plus what we need to sort it. Kept in the same order the viewer shows them. */
    private static class Item {
        final Uri uri; final String name; final long modified;
        Item(Uri u, String n, long m) { uri = u; name = n == null ? "" : n; modified = m; }
    }
    private final ArrayList<Item> items = new ArrayList<>();
    private static final String[] ORDER_LABELS = {"A \u2192 Z", "Z \u2192 A", "Newest", "Oldest", "Shuffle"};
    private static final String[] ORDER_NAMES = {"A to Z", "Z to A", "newest first", "oldest first", "random"};
    private SeekBar posBar;
    private SeekBar sizeBar;
    private boolean dockShown = true;
    private boolean fromFolder = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setDecorFitsSystemWindows(false);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setAttributes(lp);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        // If anything (a call, a notification, the volume panel, a rotation) brings the
        // status bar / nav bar / taskbar back while we're on screen, tuck them away again.
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            if (ins.isVisible(WindowInsets.Type.statusBars()) || ins.isVisible(WindowInsets.Type.navigationBars())) {
                scheduleHide(1500);
            }
            return v.onApplyWindowInsets(ins);
        });

        view = new LogoView(this);
        view.setPosFrac(prefs.getInt("pos", 22) / 100f);
        view.setSizeFrac(prefs.getInt("size", 70) / 100f);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        dock = buildDock();
        root.addView(dock, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(root);
        hideSystemBars();
        applyBg();
        applyInvert();
        applyFlip();

        view.setListener(new LogoView.Listener() {
            @Override public void onTap() { toggleDock(); }
            @Override public void onIndexChanged(int i) {
                updateCounter();
                if (fromFolder && i < items.size()) prefs.edit().putString("cur", items.get(i).uri.toString()).apply();
            }
        });
        updateCounter();

        if (!handleShare(getIntent())) restoreFolder();

        view.postDelayed(() -> checkForUpdate(false), 1500);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        scheduleHide(500); // again after the resume animation; some phones re-show bars mid-transition
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (view != null) view.removeCallbacks(hideRunnable);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
            scheduleHide(500);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // we handle rotation ourselves (Flip), so the window isn't recreated and the bars can come back
        hideSystemBars();
        scheduleHide(500);
    }

    @Override
    public void onMultiWindowModeChanged(boolean inMultiWindow, Configuration newConfig) {
        super.onMultiWindowModeChanged(inMultiWindow, newConfig);
        hideSystemBars();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { view.step(1); return true; }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { view.step(-1); return true; }
        return super.onKeyDown(keyCode, event);
    }

    private final Runnable hideRunnable = this::hideSystemBars;

    private void scheduleHide(long delayMs) {
        if (view == null) return;
        view.removeCallbacks(hideRunnable);
        view.postDelayed(hideRunnable, delayMs);
    }

    /** Full immersive mode: hides the status bar, nav bar and (on tablets/foldables) the taskbar. */
    private void hideSystemBars() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            c.hide(WindowInsets.Type.systemBars());
        }
    }

    // ---------- dock ----------

    private LinearLayout buildDock() {
        LinearLayout d = new LinearLayout(this);
        d.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(DOCK_BG);
        float r = dp(18);
        panel.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        d.setBackground(panel);
        d.setClickable(true); // don't let taps fall through to the image
        d.setOnApplyWindowInsetsListener((v, ins) -> {
            Insets n = ins.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars() | WindowInsets.Type.displayCutout());
            v.setPadding(dp(14) + n.left, dp(6), dp(14) + n.right, dp(12) + n.bottom);
            return ins;
        });

        // header: image counter on the left, hide on the right
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        counter = new TextView(this);
        counter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        counter.setTypeface(android.graphics.Typeface.MONOSPACE);
        counter.setTextColor(MUTED);
        head.addView(counter, new LinearLayout.LayoutParams(0, -2, 1));
        TextView hide = new TextView(this);
        hide.setText("Hide \u25BE");
        hide.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hide.setTextColor(MUTED);
        hide.setGravity(Gravity.CENTER);
        hide.setPadding(dp(10), 0, dp(4), 0);
        hide.setOnClickListener(v -> toggleDock());
        head.addView(hide, new LinearLayout.LayoutParams(-2, dp(32)));
        d.addView(head);

        LinearLayout posRow = sliderRow("Height", 6, 85, prefs.getInt("pos", 22), val -> {
            view.setPosFrac(val / 100f);
            prefs.edit().putInt("pos", val).apply();
        });
        posBar = (SeekBar) posRow.getTag();
        d.addView(posRow);
        LinearLayout sizeRow = sliderRow("Size", 15, 100, prefs.getInt("size", 70), val -> {
            view.setSizeFrac(val / 100f);
            prefs.edit().putInt("size", val).apply();
        });
        sizeBar = (SeekBar) sizeRow.getTag();
        d.addView(sizeRow);

        // row 1: where images come from, plus updates
        LinearLayout r1 = row();
        TextView folder = button("Folder");
        folder.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER));
        TextView pick = button("Images");
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, REQ_PICK);
        });
        orderBtn = button(ORDER_LABELS[order()]);
        orderBtn.setOnClickListener(v -> cycleOrder());
        TextView upd = button("Update");
        upd.setOnClickListener(v -> checkForUpdate(true));
        r1.addView(folder, weight());
        r1.addView(pick, weight());
        r1.addView(orderBtn, weight());
        r1.addView(upd, weight());
        d.addView(r1);

        // row 2: display toggles (highlighted when on)
        LinearLayout r2 = row();
        flipBtn = button("Flip");
        flipBtn.setOnClickListener(v -> {
            boolean on = !prefs.getBoolean("flip", false);
            prefs.edit().putBoolean("flip", on).apply();
            applyFlip();
        });
        invBtn = button("Invert");
        invBtn.setOnClickListener(v -> {
            boolean on = !prefs.getBoolean("invert", false);
            prefs.edit().putBoolean("invert", on).apply();
            applyInvert();
        });
        bgBtn = button("");
        bgBtn.setOnClickListener(v -> {
            boolean white = !prefs.getBoolean("white", false);
            prefs.edit().putBoolean("white", white).apply();
            applyBg();
        });
        r2.addView(flipBtn, weight());
        r2.addView(invBtn, weight());
        TextView reset = button("Reset");
        reset.setOnClickListener(v -> resetSettings());
        r2.addView(bgBtn, weight());
        r2.addView(reset, weight());
        d.addView(r2);
        return d;
    }

    interface IntCb { void on(int v); }

    private LinearLayout sliderRow(String label, int min, int max, int value, IntCb cb) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(36));

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(MUTED);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        r.addView(l, new LinearLayout.LayoutParams(dp(52), -2));

        SeekBar sb = new SeekBar(this);
        sb.setMin(min);
        sb.setMax(max);
        sb.setProgress(value);
        sb.setProgressTintList(ColorStateList.valueOf(ACCENT));
        sb.setThumbTintList(ColorStateList.valueOf(ACCENT));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF3A3F45));
        r.addView(sb, new LinearLayout.LayoutParams(0, -2, 1));
        r.setTag(sb);

        TextView out = new TextView(this);
        out.setText(value + "%");
        out.setTextColor(INK);
        out.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        out.setTypeface(android.graphics.Typeface.MONOSPACE);
        out.setGravity(Gravity.END);
        r.addView(out, new LinearLayout.LayoutParams(dp(40), -2));

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                out.setText(p + "%");
                cb.on(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        return r;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(8), 0, 0);
        return r;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(38), 1);
        p.setMargins(dp(4), 0, dp(4), 0);
        return p;
    }

    /** Every dock button looks the same: a flat rounded pill, same size, same text. */
    private TextView button(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setClickable(true);
        b.setFocusable(true);
        styleButton(b, false);
        return b;
    }

    private void styleButton(TextView b, boolean on) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(10));
        shape.setColor(on ? 0x33FFB020 : BTN_BG);
        shape.setStroke(dp(1), on ? ACCENT : BTN_LINE);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null));
        b.setTextColor(on ? ACCENT : INK);
    }

    /** Puts Height, Size, Flip, Invert and the background back to how a fresh install starts. */
    private void resetSettings() {
        boolean wasFlipped = prefs.getBoolean("flip", false);
        prefs.edit().putBoolean("flip", false).putBoolean("invert", false).putBoolean("white", false).apply();
        posBar.setProgress(22);   // the slider listeners save these and move the image
        sizeBar.setProgress(70);
        applyBg();
        applyInvert();
        if (wasFlipped) applyFlip();
        toast("Settings reset");
    }

    private void applyBg() {
        boolean white = prefs.getBoolean("white", false);
        int c = white ? Color.WHITE : Color.BLACK;
        view.setBg(c);
        getWindow().getDecorView().setBackgroundColor(view.shownBg());
        bgBtn.setText(white ? "White BG" : "Black BG");
        styleButton(bgBtn, white);
    }

    /** Upside-down mode: turns the whole app 180° so a camera notch ends up at the bottom. */
    private void applyFlip() {
        boolean on = prefs.getBoolean("flip", false);
        setRequestedOrientation(on ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                                   : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        styleButton(flipBtn, on);
        // when flipped, tuck the dock away so the screen is clear; tap the image to bring it back
        if (on && dockShown) dock.post(this::toggleDock);
    }

    private void applyInvert() {
        boolean on = prefs.getBoolean("invert", false);
        view.setInvert(on);
        getWindow().getDecorView().setBackgroundColor(view.shownBg());
        styleButton(invBtn, on);
    }

    private void toggleDock() {
        dockShown = !dockShown;
        dock.animate().translationY(dockShown ? 0 : dock.getHeight()).setDuration(200).start();
    }

    private void updateCounter() {
        int n = view.count();
        counter.setText(n == 0 ? "No images" : (view.getIndex() + 1) + " / " + n);
    }

    // ---------- loading images ----------

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_FOLDER && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) { }
            prefs.edit().putString("tree", tree.toString()).remove("cur").apply();
            loadTree(tree, null);
        } else if (req == REQ_PICK) {
            ArrayList<Uri> list = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) list.add(clip.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                list.add(data.getData());
            }
            loadUris(list);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean handleShare(Intent intent) {
        if (intent == null) return false;
        ArrayList<Uri> list = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) list.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> us = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (us != null) list.addAll(us);
        }
        if (list.isEmpty()) return false;
        loadUris(list);
        return true;
    }

    private void restoreFolder() {
        String t = prefs.getString("tree", null);
        if (t != null) {
            String cur = prefs.getString("cur", null);
            loadTree(Uri.parse(t), cur == null ? null : Uri.parse(cur));
        }
    }

    private void loadTree(Uri tree, Uri keep) {
        final ContentResolver cr = getContentResolver();
        new Thread(() -> {
            final ArrayList<Item> found = new ArrayList<>();
            String err = null;
            try {
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                try (Cursor c = cr.query(children, new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
                    while (c != null && c.moveToNext()) {
                        String mime = c.getString(2);
                        if (mime != null && mime.startsWith("image/")) {
                            found.add(new Item(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                                    c.getString(1), c.isNull(3) ? 0 : c.getLong(3)));
                        }
                    }
                }
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String error = err;
            runOnUiThread(() -> {
                if (error != null) {
                    toast("Couldn't open that folder. Tap Open folder to choose it again.");
                    return;
                }
                if (found.isEmpty()) toast("No images in that folder");
                fromFolder = true;
                showItems(found, keep);
            });
        }).start();
    }

    /** Picked or shared images: look up each one's name and date so they can be sorted too. */
    private void loadUris(List<Uri> uris) {
        final ContentResolver cr = getContentResolver();
        new Thread(() -> {
            final ArrayList<Item> found = new ArrayList<>();
            for (Uri u : uris) {
                String name = u.getLastPathSegment();
                long mod = 0;
                try (Cursor c = cr.query(u, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (n >= 0 && !c.isNull(n)) name = c.getString(n);
                        int m = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED); // ms
                        int ms = c.getColumnIndex("date_modified");                               // seconds (gallery shares)
                        if (m >= 0 && !c.isNull(m)) mod = c.getLong(m);
                        else if (ms >= 0 && !c.isNull(ms)) mod = c.getLong(ms) * 1000L;
                    }
                } catch (Exception ignored) { }
                found.add(new Item(u, name, mod));
            }
            runOnUiThread(() -> {
                fromFolder = false;
                showItems(found, null);
            });
        }).start();
    }

    private int order() { return prefs.getInt("order", 0); }

    /** Replaces the image list, sorts it by the chosen order, and stays on {@code keep} if it's there. */
    private void showItems(List<Item> list, Uri keep) {
        items.clear();
        items.addAll(list);
        sortItems();
        int start = 0;
        if (keep != null) {
            for (int i = 0; i < items.size(); i++) if (items.get(i).uri.equals(keep)) { start = i; break; }
        }
        pushToView(start);
    }

    private void sortItems() {
        switch (order()) {
            case 1: Collections.sort(items, (a, b) -> naturalCompare(b.name, a.name)); break;
            case 2: Collections.sort(items, (a, b) -> a.modified != b.modified ? Long.compare(b.modified, a.modified) : naturalCompare(a.name, b.name)); break;
            case 3: Collections.sort(items, (a, b) -> a.modified != b.modified ? Long.compare(a.modified, b.modified) : naturalCompare(a.name, b.name)); break;
            case 4: Collections.shuffle(items); break;
            default: Collections.sort(items, (a, b) -> naturalCompare(a.name, b.name));
        }
    }

    private void pushToView(int start) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (Item it : items) uris.add(it.uri);
        view.setImages(uris, start);
    }

    /** Order button: A-Z, Z-A, newest, oldest, shuffle. Keeps showing the same image. */
    private void cycleOrder() {
        int o = (order() + 1) % ORDER_LABELS.length;
        prefs.edit().putInt("order", o).apply();
        orderBtn.setText(ORDER_LABELS[o]);
        if (!items.isEmpty()) {
            Uri cur = items.get(Math.min(view.getIndex(), items.size() - 1)).uri;
            sortItems();
            int start = 0;
            if (o != 4) { // when shuffling, start from the top of the new mix
                for (int i = 0; i < items.size(); i++) if (items.get(i).uri.equals(cur)) { start = i; break; }
            }
            pushToView(start);
        }
        toast("Order: " + ORDER_NAMES[o]);
    }

    /** Sorts "logo2" before "logo10". */
    private static int naturalCompare(String a, String b) {
        a = a == null ? "" : a.toLowerCase();
        b = b == null ? "" : b.toLowerCase();
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int cmp = na.compareTo(nb);
                if (cmp != 0) return cmp;
            } else {
                if (ca != cb) return ca - cb;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    // ---------- updater ----------

    private void checkForUpdate(boolean manual) {
        if (manual) toast("Checking for updates…");
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                int code = c.getResponseCode();
                if (code != 200) throw new Exception("GitHub answered " + code);
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                JSONObject j = new JSONObject(sb.toString());
                String tag = j.getString("tag_name");
                int remote = Integer.parseInt(tag.substring(tag.lastIndexOf('.') + 1).replaceAll("\\D", ""));
                String apk = null;
                JSONArray assets = j.optJSONArray("assets");
                if (assets != null) {
                    for (int k = 0; k < assets.length(); k++) {
                        JSONObject a = assets.getJSONObject(k);
                        if (a.optString("name").endsWith(".apk")) { apk = a.getString("browser_download_url"); break; }
                    }
                }
                long local = getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
                final String apkUrl = apk;
                final String localName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                runOnUiThread(() -> {
                    if (remote > local && apkUrl != null) {
                        new AlertDialog.Builder(this)
                                .setTitle("Update available")
                                .setMessage("Version " + tag.replaceFirst("^v", "") + " is out (you have " + localName + ").\n\n"
                                        + "Download it now? Your browser will download the APK. Open it to install.")
                                .setPositiveButton("Download", (dlg, w) ->
                                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))))
                                .setNegativeButton("Not now", null)
                                .show();
                    } else if (manual) {
                        toast("You have the latest version (" + localName + ")");
                    }
                });
            } catch (Exception e) {
                if (manual) runOnUiThread(() -> toast("Couldn't check for updates: " + e.getMessage()));
            }
        }).start();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
