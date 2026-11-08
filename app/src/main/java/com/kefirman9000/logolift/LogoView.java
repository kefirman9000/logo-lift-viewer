package com.kefirman9000.logolift;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Draws one image at a time, centred horizontally, at an adjustable height. Swipe to change. */
public class LogoView extends View {

    public interface Listener {
        void onTap();
        void onIndexChanged(int index);
    }

    private final List<Uri> uris = new ArrayList<>();
    private int index = 0;
    private float posFrac = 0.22f;
    private float sizeFrac = 0.70f;
    private int bg = Color.BLACK;
    private boolean invert = false;
    private float offset = 0f;
    private Listener listener;

    private final LruCache<Uri, Bitmap> cache = new LruCache<>(7);
    private final Set<Uri> loading = new HashSet<>();
    private final Set<Uri> failed = new HashSet<>();
    private final ExecutorService exec = Executors.newFixedThreadPool(2);
    private int generation = 0;

    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final int touchSlop;
    private float downX, downY;
    private boolean dragging, moved;
    private VelocityTracker vt;
    private ValueAnimator anim;

    public LogoView(Context c) {
        super(c);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(16 * getResources().getDisplayMetrics().scaledDensity);
    }

    public void setListener(Listener l) { listener = l; }

    public void setImages(List<Uri> list, int start) {
        if (anim != null) anim.cancel();
        generation++;
        uris.clear();
        uris.addAll(list);
        index = uris.isEmpty() ? 0 : Math.max(0, Math.min(start, uris.size() - 1));
        offset = 0;
        cache.evictAll();
        loading.clear();
        failed.clear();
        invalidate();
        if (listener != null) listener.onIndexChanged(index);
    }

    public int count() { return uris.size(); }
    public int getIndex() { return index; }

    public void setPosFrac(float f) { posFrac = f; invalidate(); }
    public void setSizeFrac(float f) { sizeFrac = f; invalidate(); }
    public void setBg(int color) { bg = color; invalidate(); }

    /** Inverts the image colours (transparent areas stay transparent). */
    public void setInvert(boolean on) {
        invert = on;
        bmpPaint.setColorFilter(on ? new ColorMatrixColorFilter(new float[]{
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0}) : null);
        invalidate();
    }

    private Bitmap get(int i) {
        if (i < 0 || i >= uris.size()) return null;
        final Uri u = uris.get(i);
        Bitmap b = cache.get(u);
        if (b == null && !failed.contains(u) && loading.add(u)) {
            final int gen = generation;
            final int maxDim = Math.max(Math.max(getWidth(), getHeight()), 1080);
            exec.submit(() -> {
                Bitmap result = null;
                try {
                    ImageDecoder.Source src = ImageDecoder.createSource(getContext().getContentResolver(), u);
                    result = ImageDecoder.decodeBitmap(src, (dec, info, s) -> {
                        int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                        if (w > maxDim || h > maxDim) {
                            float sc = Math.min((float) maxDim / w, (float) maxDim / h);
                            dec.setTargetSize(Math.max(1, Math.round(w * sc)), Math.max(1, Math.round(h * sc)));
                        }
                    });
                } catch (Exception | OutOfMemoryError e) {
                    result = null;
                }
                final Bitmap fin = result;
                post(() -> {
                    if (gen != generation) return;
                    loading.remove(u);
                    if (fin != null) cache.put(u, fin); else failed.add(u);
                    invalidate();
                });
            });
        }
        return b;
    }

    @Override
    protected void onDraw(Canvas c) {
        int shownBg = shownBg();
        c.drawColor(shownBg);
        float W = getWidth(), H = getHeight();
        textPaint.setColor(isLight(shownBg) ? 0xFF555A60 : 0xFF8B929A);

        if (uris.isEmpty()) {
            c.drawText("Open a folder or pick images below", W / 2, posFrac * H, textPaint);
            return;
        }
        drawOne(c, index, offset);
        if (offset > 0) drawOne(c, index - 1, offset - W);
        if (offset < 0) drawOne(c, index + 1, offset + W);
        // prefetch neighbours
        get(index - 1);
        get(index + 1);
    }

    private void drawOne(Canvas c, int i, float dx) {
        if (i < 0 || i >= uris.size()) return;
        float W = getWidth(), H = getHeight();
        float cy = posFrac * H;
        Bitmap b = get(i);
        if (b == null) {
            if (failed.contains(uris.get(i))) c.drawText("Can't open this file", W / 2 + dx, cy, textPaint);
            return;
        }
        float tw = sizeFrac * W;
        float sc = tw / b.getWidth();
        float th = b.getHeight() * sc;
        float maxH = 2f * Math.min(cy, H - cy) * 0.96f;
        if (th > maxH) {
            sc = maxH / b.getHeight();
            th = maxH;
            tw = b.getWidth() * sc;
        }
        rect.set(W / 2 - tw / 2 + dx, cy - th / 2, W / 2 + tw / 2 + dx, cy + th / 2);
        c.drawBitmap(b, null, rect, bmpPaint);
    }

    /** Background as displayed: inverted along with the image when invert is on. */
    public int shownBg() {
        return invert ? Color.rgb(255 - Color.red(bg), 255 - Color.green(bg), 255 - Color.blue(bg)) : bg;
    }

    private static boolean isLight(int color) {
        return (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000 > 128;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float W = getWidth();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (anim != null) anim.cancel();
                downX = e.getX();
                downY = e.getY();
                dragging = false;
                moved = false;
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (vt != null) vt.addMovement(e);
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) moved = true;
                if (!dragging && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) dragging = true;
                if (dragging) {
                    boolean atEdge = (index == 0 && dx > 0) || (index >= uris.size() - 1 && dx < 0);
                    offset = atEdge ? dx / 3f : dx;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float vx = 0;
                if (vt != null) {
                    vt.addMovement(e);
                    vt.computeCurrentVelocity(1000);
                    vx = vt.getXVelocity();
                    vt.recycle();
                    vt = null;
                }
                if (dragging) {
                    if ((offset < -W * 0.22f || vx < -1200) && index < uris.size() - 1) animateTo(-W, 1);
                    else if ((offset > W * 0.22f || vx > 1200) && index > 0) animateTo(W, -1);
                    else animateTo(0, 0);
                } else if (!moved) {
                    performClick();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                if (vt != null) { vt.recycle(); vt = null; }
                animateTo(0, 0);
                return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (listener != null) listener.onTap();
        return true;
    }

    /** Step by delta (used by volume keys too). */
    public void step(int delta) {
        int t = index + delta;
        if (t < 0 || t >= uris.size()) return;
        if (anim != null) anim.cancel();
        animateTo(-delta * getWidth(), delta);
    }

    private void animateTo(float target, final int delta) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(offset, target);
        anim.setDuration(200);
        anim.addUpdateListener(a -> {
            offset = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            boolean done = false;
            @Override public void onAnimationEnd(Animator a) {
                if (done) return;
                done = true;
                if (delta != 0) {
                    index = Math.max(0, Math.min(index + delta, uris.size() - 1));
                    if (listener != null) listener.onIndexChanged(index);
                }
                offset = 0;
                invalidate();
            }
        });
        anim.start();
    }
}
