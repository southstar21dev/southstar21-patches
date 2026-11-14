package app.southstar21.extension.instagram.liquidglass;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * iOS 26 glass circle behind a header icon button (Home's + and heart, Explore's Saved and
 * More): a plain translucent disc with a faint rim, on top of the frosted header band.
 * <p>
 * Like iOS it's hidden at rest, so the icons blend into the header, and only shows while the
 * feed scrolls fast ({@link #setReveal}). The circle is centered on the button and may be larger
 * than it (its parents are told not to clip it).
 */
final class GlassButtonDrawable extends Drawable {
    private float diameter;
    private boolean dark;
    private boolean pressed;
    /** 0 hidden, 1 fully shown. */
    private float reveal;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);

    GlassButtonDrawable(float density) {
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, 0.6f * density));
    }

    void setDiameter(float diameter) {
        if (this.diameter == diameter) return;
        this.diameter = diameter;
        invalidateSelf();
    }

    void setDark(boolean dark) {
        if (this.dark == dark) return;
        this.dark = dark;
        invalidateSelf();
    }

    void setReveal(float reveal) {
        if (this.reveal == reveal) return;
        this.reveal = reveal;
        invalidateSelf();
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean now = false;
        for (int s : state) {
            if (s == android.R.attr.state_pressed) now = true;
        }
        if (now == pressed) return false;
        pressed = now;
        invalidateSelf();
        return true;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty() || diameter <= 0 || reveal <= 0.01f) return;
        float r = diameter / 2f;
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();

        int fillColor = dark
                ? (pressed ? Color.argb(0xD0, 0x3A, 0x3A, 0x3E) : Color.argb(0xB8, 0x26, 0x26, 0x2A))
                : (pressed ? Color.argb(0xD0, 0xE8, 0xE8, 0xEC) : Color.argb(0xB8, 0xFF, 0xFF, 0xFF));
        int rimColor = dark ? Color.argb(0x2A, 0xFF, 0xFF, 0xFF) : Color.argb(0x14, 0, 0, 0);
        fill.setColor(scaleAlpha(fillColor, reveal));
        rim.setColor(scaleAlpha(rimColor, reveal));

        canvas.drawCircle(cx, cy, r, fill);
        canvas.drawCircle(cx, cy, r - rim.getStrokeWidth() / 2f, rim);
    }

    private static int scaleAlpha(int color, float factor) {
        return (color & 0x00FFFFFF) | (Math.round(Color.alpha(color) * factor) << 24);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public boolean getPadding(Rect padding) {
        padding.set(0, 0, 0, 0);
        return false;
    }
}
