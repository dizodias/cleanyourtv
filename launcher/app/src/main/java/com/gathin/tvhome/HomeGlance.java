package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dólar (AwesomeAPI) e temperatura em Rio do Sul (Open-Meteo) ao lado do relógio.
 * Atualiza a cada 10 minutos, guarda cache e não mostra nada se a rede falhar sem leitura anterior.
 */
final class HomeGlance {

    private static final String TAG = "HomeGlance";
    private static final String PREFS = "home_glance";
    private static final long REFRESH_MS = 10 * 60 * 1000L;
    private static final int TIMEOUT_MS = 8_000;
    private static final int UP = 0xFF30D158;
    private static final int DOWN = 0xFFFF453A;

    private static final String QUOTE_URL = "https://economia.awesomeapi.com.br/json/last/USD-BRL";
    private static final String SERIES_URL = "https://economia.awesomeapi.com.br/USD-BRL/96";
    private static final String WEATHER_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=-27.2142&longitude=-49.6431"
                    + "&current=temperature_2m,weather_code&timezone=America%2FSao_Paulo";

    private static final String KEY_AT = "at";
    private static final String KEY_BID = "bid";
    private static final String KEY_PCT = "pct";
    private static final String KEY_TEMP = "temp";
    private static final String KEY_CODE = "code";
    private static final String KEY_SERIES = "series";

    private final Activity activity;
    private final Handler handler;
    private final SharedPreferences prefs;
    private final View root;
    private final View dollarPill;
    private final TextView dollarValue;
    private final TextView dollarChange;
    private final ImageView dollarArrow;
    private final View weatherPill;
    private final ImageView weatherIcon;
    private final TextView weatherTemp;
    private final ColorStateList flatChange = new ColorStateList(
            new int[][] { new int[] { android.R.attr.state_focused }, new int[0] },
            new int[] { 0xFF111111, 0xCCFFFFFF });
    private final ExecutorService workers = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cotacao");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (closed) return;
            fetchAsync();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private Dialog chartDialog;
    private TextView chartNow;
    private TextView chartEmpty;
    private DollarChartView chart;
    private volatile boolean closed;
    private boolean fetching;
    private int generation;

    HomeGlance(Activity activity, Handler handler) {
        this.activity = activity;
        this.handler = handler;
        prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        root = activity.findViewById(R.id.homeGlance);
        dollarPill = activity.findViewById(R.id.dollarPill);
        dollarValue = activity.findViewById(R.id.dollarValue);
        dollarChange = activity.findViewById(R.id.dollarChange);
        dollarArrow = activity.findViewById(R.id.dollarArrow);
        weatherPill = activity.findViewById(R.id.weatherPill);
        weatherIcon = activity.findViewById(R.id.weatherIcon);
        weatherTemp = activity.findViewById(R.id.weatherTemp);
        dollarPill.setOnClickListener(v -> openChart());
    }

    View dollarPill() {
        return dollarPill;
    }

    void resume() {
        if (closed) return;
        bind(read());
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, delayUntilDue());
    }

    /** Rede voltou: busca de novo se o cache já passou de 10 minutos. */
    void onNetwork() {
        if (closed) return;
        if (delayUntilDue() > 0) return;
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    void pause() {
        handler.removeCallbacks(refresh);
        if (chartDialog != null) chartDialog.dismiss();
    }

    void close() {
        closed = true;
        pause();
        workers.shutdownNow();
    }

    private long delayUntilDue() {
        long at = prefs.getLong(KEY_AT, 0);
        if (at <= 0) return 0;
        long age = System.currentTimeMillis() - at;
        if (age < 0 || age >= REFRESH_MS) return 0;
        return REFRESH_MS - age;
    }

    private void fetchAsync() {
        final int gen = ++generation;
        fetching = true;
        workers.execute(() -> {
            HomeGlanceData.Quote quote = null;
            HomeGlanceData.Weather weather = null;
            List<HomeGlanceData.Point> series = Collections.emptyList();
            try {
                quote = HomeGlanceData.parseQuote(httpGet(QUOTE_URL));
            } catch (Exception e) {
                Log.d(TAG, "cotação indisponível: " + e);
            }
            try {
                weather = HomeGlanceData.parseWeather(httpGet(WEATHER_URL));
            } catch (Exception e) {
                Log.d(TAG, "tempo indisponível: " + e);
            }
            try {
                series = HomeGlanceData.parseSeries(httpGet(SERIES_URL));
            } catch (Exception e) {
                Log.d(TAG, "série indisponível: " + e);
            }
            if (closed || gen != generation) return;
            HomeGlanceData.Quote q = quote;
            HomeGlanceData.Weather w = weather;
            List<HomeGlanceData.Point> s = series;
            handler.post(() -> {
                if (closed || gen != generation || activity.isFinishing()) return;
                fetching = false;
                save(q, w, s);
                bind(read());
            });
        });
    }

    private void save(HomeGlanceData.Quote quote, HomeGlanceData.Weather weather,
                      List<HomeGlanceData.Point> series) {
        SharedPreferences.Editor editor = prefs.edit();
        boolean any = false;
        if (quote != null) {
            editor.putString(KEY_BID, Double.toString(quote.bid));
            editor.putString(KEY_PCT, Double.toString(quote.pct));
            any = true;
        }
        if (weather != null) {
            editor.putString(KEY_TEMP, Double.toString(weather.tempC));
            editor.putInt(KEY_CODE, weather.code);
            any = true;
        }
        if (series != null && series.size() >= 2) {
            editor.putString(KEY_SERIES, HomeGlanceData.encodeSeries(series));
            any = true;
        }
        if (any) editor.putLong(KEY_AT, System.currentTimeMillis());
        editor.apply();
    }

    private static final class Snapshot {
        final Double bid;
        final Double pct;
        final Double tempC;
        final int code;
        final List<HomeGlanceData.Point> series;

        Snapshot(Double bid, Double pct, Double tempC, int code, List<HomeGlanceData.Point> series) {
            this.bid = bid;
            this.pct = pct;
            this.tempC = tempC;
            this.code = code;
            this.series = series;
        }
    }

    private Snapshot read() {
        Double bid = parseDouble(prefs.getString(KEY_BID, null));
        Double pct = parseDouble(prefs.getString(KEY_PCT, null));
        Double temp = parseDouble(prefs.getString(KEY_TEMP, null));
        int code = prefs.getInt(KEY_CODE, 0);
        List<HomeGlanceData.Point> series = HomeGlanceData.decodeSeries(prefs.getString(KEY_SERIES, ""));
        return new Snapshot(bid, pct, temp, code, series);
    }

    private static Double parseDouble(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void bind(Snapshot snap) {
        boolean dollar = snap.bid != null && snap.pct != null;
        if (dollar) {
            String price = HomeGlanceData.formatBid(snap.bid);
            String pct = HomeGlanceData.formatPct(snap.pct);
            int dir = HomeGlanceData.direction(snap.pct);
            dollarValue.setText(price);
            dollarChange.setText(pct);
            if (dir > 0) {
                dollarArrow.setVisibility(View.VISIBLE);
                dollarArrow.setImageResource(R.drawable.ic_trend_up);
                dollarArrow.setImageTintList(ColorStateList.valueOf(UP));
                dollarChange.setTextColor(UP);
            } else if (dir < 0) {
                dollarArrow.setVisibility(View.VISIBLE);
                dollarArrow.setImageResource(R.drawable.ic_trend_down);
                dollarArrow.setImageTintList(ColorStateList.valueOf(DOWN));
                dollarChange.setTextColor(DOWN);
            } else {
                dollarArrow.setVisibility(View.GONE);
                dollarChange.setTextColor(flatChange);
            }
            int word = dir > 0 ? R.string.glance_up : dir < 0 ? R.string.glance_down : R.string.glance_flat;
            dollarPill.setContentDescription(activity.getString(R.string.glance_dollar_cd, price,
                    activity.getString(word), pct));
            dollarPill.setVisibility(View.VISIBLE);
        } else {
            dollarPill.setVisibility(View.GONE);
        }

        boolean weather = snap.tempC != null;
        if (weather) {
            String temp = HomeGlanceData.formatTemp(snap.tempC);
            weatherTemp.setText(temp);
            int kind = HomeGlanceData.weatherKind(snap.code);
            int icon = kind == HomeGlanceData.SUN ? R.drawable.ic_weather_sun
                    : kind == HomeGlanceData.CLOUD ? R.drawable.ic_weather_cloud
                    : R.drawable.ic_weather_rain;
            int tint = kind == HomeGlanceData.SUN ? 0xFFFFD60A
                    : kind == HomeGlanceData.CLOUD ? 0xFFFFFFFF
                    : 0xFF7EB6FF;
            weatherIcon.setImageResource(icon);
            weatherIcon.setImageTintList(ColorStateList.valueOf(tint));
            weatherPill.setContentDescription(activity.getString(R.string.glance_weather_cd, temp));
            weatherPill.setVisibility(View.VISIBLE);
        } else {
            weatherPill.setVisibility(View.GONE);
        }
        root.setVisibility(dollar || weather ? View.VISIBLE : View.GONE);
        if (chartDialog != null && chartDialog.isShowing()) fillChart();
    }

    private void openChart() {
        if (closed || activity.isFinishing()) return;
        if (chartDialog != null && chartDialog.isShowing()) return;
        View panel = activity.getLayoutInflater().inflate(R.layout.dialog_dollar_chart, null);
        chartNow = panel.findViewById(R.id.chartNow);
        chartEmpty = panel.findViewById(R.id.chartEmpty);
        chart = panel.findViewById(R.id.chart);
        TextView close = panel.findViewById(R.id.btnChartClose);
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setOnDismissListener(d -> {
            if (chartDialog == dialog) {
                chartDialog = null;
                chartNow = null;
                chartEmpty = null;
                chart = null;
            }
            if (dollarPill.getVisibility() == View.VISIBLE && activity.hasWindowFocus()) dollarPill.requestFocus();
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            float density = activity.getResources().getDisplayMetrics().density;
            int screen = activity.getResources().getDisplayMetrics().widthPixels;
            int width = Math.min(Math.round(560 * density), screen - Math.round(64 * density));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setDimAmount(0.55f);
        }
        close.setOnClickListener(v -> dialog.dismiss());
        close.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        });
        chartDialog = dialog;
        fillChart();
        dialog.show();
        close.requestFocus();
    }

    private void fillChart() {
        if (chartNow == null || chart == null || chartEmpty == null) return;
        Snapshot snap = read();
        if (snap.bid != null && snap.pct != null) {
            chartNow.setText(activity.getString(R.string.glance_chart_now,
                    HomeGlanceData.formatBid(snap.bid), HomeGlanceData.formatPct(snap.pct)));
        } else if (fetching) {
            chartNow.setText(R.string.glance_chart_loading);
        } else {
            chartNow.setText("");
        }
        List<HomeGlanceData.Point> series = HomeGlanceData.forChart(snap.series);
        if (series.size() >= 2) {
            chart.setSeries(series);
            chart.setVisibility(View.VISIBLE);
            chartEmpty.setVisibility(View.GONE);
        } else {
            chart.setVisibility(View.GONE);
            chartEmpty.setVisibility(View.VISIBLE);
            chartEmpty.setText(fetching ? R.string.glance_chart_loading : R.string.glance_chart_empty);
        }
    }

    static String httpGet(String url) throws java.io.IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "CleanYourTV/3.0");
        try {
            int code = connection.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? connection.getInputStream() : null;
            if (in == null) throw new java.io.IOException("http " + code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > 262144) break;
                out.write(buf, 0, n);
            }
            in.close();
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }
}
