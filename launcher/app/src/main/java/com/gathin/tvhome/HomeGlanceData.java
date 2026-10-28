package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Leitura da cotação (AwesomeAPI) e do tempo (Open-Meteo), sem Android: dá para testar no JVM.
 * A variação do dia vem pronta em pctChange. A série gratuita traz no máximo cerca de 100 ticks.
 */
final class HomeGlanceData {

    static final int SUN = 0;
    static final int CLOUD = 1;
    static final int RAIN = 2;

    static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final Locale BR = new Locale("pt", "BR");

    private HomeGlanceData() {}

    static final class Quote {
        final double bid;
        final double pct;

        Quote(double bid, double pct) {
            this.bid = bid;
            this.pct = pct;
        }
    }

    static final class Weather {
        final double tempC;
        final int code;

        Weather(double tempC, int code) {
            this.tempC = tempC;
            this.code = code;
        }
    }

    static final class Point {
        final long epochMs;
        final double bid;

        Point(long epochMs, double bid) {
            this.epochMs = epochMs;
            this.bid = bid;
        }
    }

    /** 1 subiu, -1 caiu, 0 estável. */
    static int direction(double pct) {
        if (pct > 0) return 1;
        if (pct < 0) return -1;
        return 0;
    }

    /** Sol, nublado ou chuva a partir do código WMO. */
    static int weatherKind(int code) {
        if (code <= 1) return SUN;
        if (code == 2 || code == 3 || code == 45 || code == 48) return CLOUD;
        return RAIN;
    }

    static String formatBid(double bid) {
        return String.format(BR, "R$ %,.2f", bid);
    }

    static String formatPct(double pct) {
        if (pct == 0) return String.format(BR, "%.2f%%", 0.0);
        return String.format(BR, "%+.2f%%", pct);
    }

    static String formatTemp(double celsius) {
        return String.format(BR, "%d°", (int) Math.round(celsius));
    }

    static String formatNumber(double value) {
        return String.format(BR, "%.2f", value);
    }

    static Quote parseQuote(String json) {
        if (json == null) return null;
        String bid = field(json, "bid");
        String pct = field(json, "pctChange");
        if (bid == null || pct == null) return null;
        try {
            return new Quote(Double.parseDouble(bid), Double.parseDouble(pct));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Weather parseWeather(String json) {
        if (json == null) return null;
        int cur = json.indexOf("\"current\":{");
        if (cur >= 0) json = json.substring(cur);
        String temp = field(json, "temperature_2m");
        String code = field(json, "weather_code");
        if (temp == null || code == null) return null;
        try {
            return new Weather(Double.parseDouble(temp), (int) Math.round(Double.parseDouble(code)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static List<Point> parseSeries(String json) {
        List<Point> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        int start = 0;
        while (start < json.length()) {
            int open = json.indexOf('{', start);
            if (open < 0) break;
            int close = json.indexOf('}', open);
            if (close < 0) break;
            String obj = json.substring(open, close + 1);
            String bid = field(obj, "bid");
            String ts = field(obj, "timestamp");
            if (bid != null && ts != null) {
                try {
                    out.add(new Point(epochMs(ts), Double.parseDouble(bid)));
                } catch (NumberFormatException ignored) {
                    // ponto inválido: segue a série
                }
            }
            start = close + 1;
        }
        return out;
    }

    /** Pontos dentro das últimas 24 h a partir do mais novo. Se sobrar menos de dois, usa a série inteira. */
    static List<Point> forChart(List<Point> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        long newest = Long.MIN_VALUE;
        for (Point p : raw) if (p.epochMs > newest) newest = p.epochMs;
        long cutoff = newest - DAY_MS;
        List<Point> inWindow = new ArrayList<>();
        for (Point p : raw) if (p.epochMs >= cutoff) inWindow.add(p);
        List<Point> chosen = inWindow.size() >= 2 ? inWindow : new ArrayList<>(raw);
        chosen.sort((a, b) -> Long.compare(a.epochMs, b.epochMs));
        return chosen;
    }

    static String encodeSeries(List<Point> points) {
        if (points == null || points.isEmpty()) return "";
        List<Point> copy = new ArrayList<>(points);
        copy.sort((a, b) -> Long.compare(a.epochMs, b.epochMs));
        int from = Math.max(0, copy.size() - 96);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < copy.size(); i++) {
            if (sb.length() > 0) sb.append(';');
            Point p = copy.get(i);
            sb.append(p.epochMs).append(',').append(Double.toString(p.bid));
        }
        return sb.toString();
    }

    static List<Point> decodeSeries(String raw) {
        List<Point> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        String[] parts = raw.split(";");
        for (String part : parts) {
            int comma = part.indexOf(',');
            if (comma <= 0) continue;
            try {
                out.add(new Point(Long.parseLong(part.substring(0, comma)),
                        Double.parseDouble(part.substring(comma + 1))));
            } catch (NumberFormatException ignored) {
                // trecho corrompido no cache
            }
        }
        return out;
    }

    static long epochMs(String raw) {
        long value = Long.parseLong(raw.trim());
        return Math.abs(value) < 10_000_000_000L ? value * 1000L : value;
    }

    private static String field(String json, String key) {
        String needle = "\"" + key + "\"";
        int from = 0;
        while (from < json.length()) {
            int i = json.indexOf(needle, from);
            if (i < 0) return null;
            String value = valueAfterColon(json, i + needle.length());
            if (value != null) return value;
            from = i + needle.length();
        }
        return null;
    }

    private static String valueAfterColon(String json, int from) {
        int j = from;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        if (j >= json.length() || json.charAt(j) != ':') return null;
        j++;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        if (j >= json.length()) return null;
        char c = json.charAt(j);
        if (c == '"') {
            int end = j + 1;
            while (end < json.length()) {
                char d = json.charAt(end);
                if (d == '\\') {
                    end += 2;
                    continue;
                }
                if (d == '"') return json.substring(j + 1, end);
                end++;
            }
            return null;
        }
        if (c == '-' || (c >= '0' && c <= '9')) {
            int end = j + 1;
            while (end < json.length()) {
                char d = json.charAt(end);
                if ((d >= '0' && d <= '9') || d == '.' || d == 'e' || d == 'E' || d == '+' || d == '-') end++;
                else break;
            }
            return json.substring(j, end);
        }
        return null;
    }
}
