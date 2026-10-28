package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Linha da cotação. O eixo acompanha os pontos que a AwesomeAPI devolveu (no máximo as últimas 24 h). */
public class DollarChartView extends View {

    private static final int UP = 0xFF30D158;
    private static final int DOWN = 0xFFFF453A;

    private long[] times = new long[0];
    private float[] values = new float[0];
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SimpleDateFormat hours = new SimpleDateFormat("HH:mm", new Locale("pt", "BR"));
    private final float density;

    public DollarChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * density);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(1f * density);
        grid.setColor(0x22FFFFFF);
        dotRing.setStyle(Paint.Style.FILL);
        dotRing.setColor(0xFF161618);
        label.setColor(0xB3FFFFFF);
        label.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13,
                getResources().getDisplayMetrics()));
        try {
            label.setTypeface(UiFont.get(context, R.string.font_medium));
        } catch (RuntimeException ignored) {
            // Sem a fonte Outfit: o sistema desenha o rótulo.
        }
    }

    void setSeries(List<HomeGlanceData.Point> points) {
        int n = points == null ? 0 : points.size();
        times = new long[n];
        values = new float[n];
        for (int i = 0; i < n; i++) {
            times[i] = points.get(i).epochMs;
            values[i] = (float) points.get(i).bid;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = values.length;
        if (n < 2 || getWidth() == 0 || getHeight() == 0) return;

        float dataMin = values[0];
        float dataMax = values[0];
        for (float v : values) {
            if (v < dataMin) dataMin = v;
            if (v > dataMax) dataMax = v;
        }
        float min = dataMin;
        float max = dataMax;
        if (max - min < 0.0001f) {
            max += 0.01f;
            min -= 0.01f;
        }
        float pad = (max - min) * 0.14f;
        min -= pad;
        max += pad;

        Paint.FontMetrics fm = label.getFontMetrics();
        float left = 8f * density;
        float right = getWidth() - 8f * density;
        float topBaseline = 2f * density - fm.ascent;
        float bottomBaseline = getHeight() - 4f * density;
        float top = topBaseline + fm.descent + 8f * density;
        float bottom = bottomBaseline + fm.ascent - 8f * density;
        if (right <= left || bottom <= top) return;

        label.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("máx " + HomeGlanceData.formatNumber(dataMax), left, topBaseline, label);
        label.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("mín " + HomeGlanceData.formatNumber(dataMin), right, topBaseline, label);

        for (int g = 1; g <= 2; g++) {
            float y = top + (bottom - top) * g / 3f;
            canvas.drawLine(left, y, right, y, grid);
        }

        long t0 = times[0];
        long t1 = times[n - 1];
        if (t1 <= t0) t1 = t0 + 1;
        float span = (float) (t1 - t0);

        Path stroke = new Path();
        Path area = new Path();
        float lastX = left;
        float lastY = top;
        for (int i = 0; i < n; i++) {
            float x = left + (times[i] - t0) / span * (right - left);
            float y = top + (max - values[i]) / (max - min) * (bottom - top);
            if (i == 0) {
                stroke.moveTo(x, y);
                area.moveTo(x, bottom);
                area.lineTo(x, y);
            } else {
                stroke.lineTo(x, y);
                area.lineTo(x, y);
            }
            lastX = x;
            lastY = y;
        }
        area.lineTo(lastX, bottom);
        area.close();

        int color = values[n - 1] > values[0] ? UP : values[n - 1] < values[0] ? DOWN : 0xFFFFFFFF;
        line.setColor(color);
        dot.setColor(color);
        fill.setShader(new LinearGradient(0, top, 0, bottom,
                (0x55 << 24) | (color & 0xFFFFFF), (color & 0xFFFFFF), Shader.TileMode.CLAMP));
        canvas.drawPath(area, fill);
        canvas.drawPath(stroke, line);

        float r = 4f * density;
        canvas.drawCircle(lastX, lastY, r + 1.5f * density, dotRing);
        canvas.drawCircle(lastX, lastY, r, dot);

        String range = hours.format(new Date(t0)) + " – " + hours.format(new Date(t1));
        label.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(range, (left + right) / 2f, bottomBaseline, label);
    }
}
