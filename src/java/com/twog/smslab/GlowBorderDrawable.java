package com.twog.smslab;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;

/**
 * A rounded frame whose border is a river of light: a sweep
 * gradient that rotates slowly around the rectangle, so the
 * colours travel the perimeter like threads.
 *
 * Two palettes serve the owner (1.13): sunset threads for the
 * dhikr frame — yellow, orange, white — and green-white threads
 * for the stop button beneath it. The animation clock is external
 * (one ValueAnimator in the activity drives every glow drawable
 * and rests when the app leaves the foreground).
 */
public final class GlowBorderDrawable extends Drawable {

    public static final int[] SUNSET = {
            0xFFFFD94D, 0xFFFF8C42, 0xFFFFFFFF,
            0xFFFFB347, 0xFFFFD94D};
    public static final int[] GREEN_WHITE = {
            0xFF2FBF71, 0xFFFFFFFF, 0xFF7BE3A8,
            0xFFE8FFF2, 0xFF2FBF71};

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] colors;
    private final float radiusPx;
    private final float strokePx;
    private final float speed;
    private final RectF rect = new RectF();
    private SweepGradient shader;
    private final Matrix matrix = new Matrix();
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
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(strokePx);
    }

    /** Pure: rotation phase in [0,1) for an elapsed time. */
    public static float phase(long elapsedMs, long periodMs) {
        if (periodMs <= 0) return 0f;
        long m = elapsedMs % periodMs;
        if (m < 0) m += periodMs;
        return m / (float) periodMs;
    }

    public void setPhase(float p) {
        phase = p;
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        shader = new SweepGradient(
                bounds.exactCenterX(), bounds.exactCenterY(), colors, null);
        borderPaint.setShader(shader);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float half = strokePx / 2f;
        rect.set(b.left + half, b.top + half, b.right - half, b.bottom - half);
        canvas.drawRoundRect(rect, radiusPx, radiusPx, fillPaint);
        if (shader != null) {
            matrix.reset();
            matrix.setRotate(phase * 360f * speed,
                    b.exactCenterX(), b.exactCenterY());
            shader.setLocalMatrix(matrix);
        }
        canvas.drawRoundRect(rect, radiusPx, radiusPx, borderPaint);
    }

    @Override public void setAlpha(int alpha) {
        fillPaint.setAlpha(alpha);
        borderPaint.setAlpha(alpha);
    }

    @Override public void setColorFilter(ColorFilter cf) {
        fillPaint.setColorFilter(cf);
        borderPaint.setColorFilter(cf);
    }

    @Override public int getOpacity() {
        return android.graphics.PixelFormat.OPAQUE;
    }
}
