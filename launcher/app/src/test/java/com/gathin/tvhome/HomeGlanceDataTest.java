package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HomeGlanceDataTest {

    private static final String QUOTE = "{\"USDBRL\":{\"high\":\"5.3514\",\"low\":\"5.3489\","
            + "\"varBid\":\"-0.0025\",\"pctChange\":\"-0.046715\",\"bid\":\"5.3489\","
            + "\"ask\":\"5.3494\",\"timestamp\":\"1793181723\"}}";

    private static final String WEATHER = "{\"current\":{\"temperature_2m\":18.6,\"weather_code\":61}}";

    @Test
    public void quoteIgnoresVarBidAndReadsDailyChange() {
        HomeGlanceData.Quote quote = HomeGlanceData.parseQuote(QUOTE);
        assertEquals(5.3489, quote.bid, 0.0001);
        assertEquals(-0.046715, quote.pct, 0.0001);
        assertEquals(-1, HomeGlanceData.direction(quote.pct));
        assertEquals("R$ 5,35", HomeGlanceData.formatBid(quote.bid));
        assertEquals("-0,05%", HomeGlanceData.formatPct(quote.pct));
    }

    @Test
    public void brokenQuoteStaysEmpty() {
        assertNull(HomeGlanceData.parseQuote("{\"ok\":true}"));
        assertNull(HomeGlanceData.parseQuote(null));
        assertEquals(0, HomeGlanceData.direction(0));
        assertEquals("0,00%", HomeGlanceData.formatPct(0));
        assertEquals("+0,37%", HomeGlanceData.formatPct(0.374));
    }

    @Test
    public void weatherMapsSunCloudAndRain() {
        HomeGlanceData.Weather weather = HomeGlanceData.parseWeather(WEATHER);
        assertEquals(18.6, weather.tempC, 0.01);
        assertEquals(61, weather.code);
        assertEquals("19°", HomeGlanceData.formatTemp(weather.tempC));
        assertEquals(HomeGlanceData.SUN, HomeGlanceData.weatherKind(0));
        assertEquals(HomeGlanceData.SUN, HomeGlanceData.weatherKind(1));
        assertEquals(HomeGlanceData.CLOUD, HomeGlanceData.weatherKind(2));
        assertEquals(HomeGlanceData.CLOUD, HomeGlanceData.weatherKind(3));
        assertEquals(HomeGlanceData.CLOUD, HomeGlanceData.weatherKind(45));
        assertEquals(HomeGlanceData.RAIN, HomeGlanceData.weatherKind(61));
        assertEquals(HomeGlanceData.RAIN, HomeGlanceData.weatherKind(95));
        assertNull(HomeGlanceData.parseWeather("{}"));
    }

    @Test
    public void seriesKeepsTheLastDayAndRoundTripsThroughCache() {
        long now = 1_700_000_000_000L;
        String json = "["
                + "{\"bid\":\"5.40\",\"timestamp\":\"" + ((now - 2 * HomeGlanceData.DAY_MS) / 1000) + "\"},"
                + "{\"bid\":\"5.20\",\"timestamp\":\"" + ((now - 3 * 60 * 60 * 1000L) / 1000) + "\"},"
                + "{\"bid\":\"5.30\",\"timestamp\":\"" + (now / 1000) + "\"}"
                + "]";
        List<HomeGlanceData.Point> parsed = HomeGlanceData.parseSeries(json);
        assertEquals(3, parsed.size());
        List<HomeGlanceData.Point> chart = HomeGlanceData.forChart(parsed);
        assertEquals(2, chart.size());
        assertEquals(5.20, chart.get(0).bid, 0.001);
        assertEquals(5.30, chart.get(1).bid, 0.001);
        assertTrue(chart.get(0).epochMs < chart.get(1).epochMs);

        List<HomeGlanceData.Point> back = HomeGlanceData.decodeSeries(HomeGlanceData.encodeSeries(parsed));
        assertEquals(3, back.size());
        assertEquals(parsed.get(0).bid, back.get(0).bid, 0.001);
    }

    @Test
    public void shortSeriesStillDraws() {
        long now = 1_700_000_000_000L;
        List<HomeGlanceData.Point> raw = Arrays.asList(
                new HomeGlanceData.Point(now - 3 * HomeGlanceData.DAY_MS, 5.1),
                new HomeGlanceData.Point(now - 2 * HomeGlanceData.DAY_MS, 5.2));
        assertEquals(2, HomeGlanceData.forChart(raw).size());
    }
}
