package com.twog.smslab;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * A rounded frame whose border is a river of light.
 *
 * Version 2 (1.14): the first version rotated a sweep-gradient
 * shader — on the owner's phone the colours sat in fixed zones
 * and did not move (shader local-matrix updates are not honoured
 * everywhere). This version uses NO shaders: the perimeter is
 * drawn as many small segments, each painted with the palette
 * colour blended at its position plus the animation phase, so
 * the colours genuinely stream along the border and melt into
 * each other, identically on every device.
 *
 * Palettes (owner, 1.13): sunset threads for the dhikr frame —
 * yellow, orange, white — and green-white threads for the stop
 * button. One external clock drives every glow drawable and
 * rests when the app leaves the foreground.
 */
public final class GlowBorderDrawable extends Drawable {

    public static final int[] SUNSET = {
            0xFFFFD94D, 0xFFFF8C42, 0xFFFFFFFF,
            0xFFFFB347, 0xFFFFD94D};
    public static final int[] GREEN_WHITE = {
            0xFF2FBF71, 0xFFFFFFFF, 0xFF7BE3A8,
            0xFFE8FFF2, 0xFF2FBF71};

    private static final int SEGMENTS = 56;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint segPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] colors;
    private final float radiusPx;
    private final float strokePx;
    private final float speed;
    private final RectF rect = new RectF();
    private final Path segPath = new Path();
    private PathMeasure measure;
    private float phase;

    public GlowBorderDrawable(int fillColor, int[] borderColors,
                              float radiusDp, float strokeDp,
                              float speed, float density) {
        this.colors = borderColors;
        this.radiusPx = radiusDp * density;
        this.strokePx = strokeDp * density;
        this.speed = speed;
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(fillColor);
        segPaint.setStyle(Paint.Style.STROKE);
        segPaint.setStrokeWidth(strokePx);
        segPaint.setStrokeCap(Paint.Cap.BUTT);
    }

    /** Pure: rotation phase in [0,1) for an elapsed time. */
    public static float phase(long elapsedMs, long periodMs) {
        if (periodMs <= 0) return 0f;
        long m = elapsedMs % periodMs;
        if (m < 0) m += periodMs;
        return m / (float) periodMs;
    }

    /**
     * Pure: the palette colour at position t in [0,1) — stops are
     * evenly spaced and the palette wraps (last stop == first), so
     * neighbouring positions always blend smoothly into each other.
     */
    public static int colorAt(int[] palette, float t) {
        float x = t - (float) Math.floor(t);
        float scaled = x * (palette.length - 1);
        int i = (int) Math.floor(scaled);
        if (i >= palette.length - 1) return palette[palette.length - 1];
        float f = scaled - i;
        int c0 = palette[i], c1 = palette[i + 1];
        // Manual ARGB channel math (no android.graphics.Color) so
        // this stays pure and JVM-testable.
        int a = Math.round(((c0 >> 24) & 0xFF) + (((c1 >> 24) & 0xFF) - ((c0 >> 24) & 0xFF)) * f);
        int r = Math.round(((c0 >> 16) & 0xFF) + (((c1 >> 16) & 0xFF) - ((c0 >> 16) & 0xFF)) * f);
        int g = Math.round(((c0 >> 8) & 0xFF) + (((c1 >> 8) & 0xFF) - ((c0 >> 8) & 0xFF)) * f);
        int b = Math.round((c0 & 0xFF) + ((c1 & 0xFF) - (c0 & 0xFF)) * f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public void setPhase(float p) {
        phase = p;
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        float half = strokePx / 2f;
        rect.set(bounds.left + half, bounds.top + half,
                bounds.right - half, bounds.bottom - half);
        Path p = new Path();
        p.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW);
        measure = new PathMeasure(p, true);
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawRoundRect(rect, radiusPx, radiusPx, fillPaint);
        if (measure == null) return;
        float len = measure.getLength();
        if (len <= 0) return;
        float seg = len / SEGMENTS;
        float overlap = strokePx * 0.8f;
        for (int i = 0; i < SEGMENTS; i++) {
            float start = i * seg;
            float end = Math.min(start + seg + overlap, len);
            segPath.reset();
            if (!measure.getSegment(start, end, segPath, true)) continue;
            float t = i / (float) SEGMENTS + phase * speed;
            segPaint.setColor(colorAt(colors, t));
            canvas.drawPath(segPath, segPaint);
        }
    }

    @Override public void setAlpha(int alpha) {
        fillPaint.setAlpha(alpha);
        segPaint.setAlpha(alpha);
    }

    @Override public void setColorFilter(ColorFilter cf) {
        fillPaint.setColorFilter(cf);
        segPaint.setColorFilter(cf);
    }

    @Override public int getOpacity() {
        return android.graphics.PixelFormat.OPAQUE;
    }
}
