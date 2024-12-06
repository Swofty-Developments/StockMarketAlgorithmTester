package net.swofty.stockmarkettester.builtin;

import net.swofty.stockmarkettester.stockloopers.IndividualStockLooper;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.CandlestickRenderer;
import org.jfree.data.time.*;
import org.jfree.data.xy.DefaultOHLCDataset;
import org.jfree.data.xy.OHLCDataItem;

import javax.swing.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.List;

public class StockVisualizer {
    public static void visualizeStockPrice(List<IndividualStockLooper.TimePoint> timePoints, String ticker) {
        // Sort time points chronologically
        timePoints.sort((a, b) -> a.timestamp().compareTo(b.timestamp()));

        List<OHLCDataItem> dataItems = new ArrayList<>();
        LocalDateTime lastTimestamp = null;
        OHLCDataItem lastValidItem = null;

        for (int i = 0; i < timePoints.size(); i++) {
            IndividualStockLooper.TimePoint point = timePoints.get(i);
            LocalDateTime currentTimestamp = point.timestamp();
            double open = point.dataPoint().open();
            double high = point.dataPoint().high();
            double low = point.dataPoint().low();
            double close = point.dataPoint().close();
            double volume = point.dataPoint().volume();

            // Check if this is a valid price point
            if (isValidPricePoint(open, high, low, close)) {
                Date date = Date.from(currentTimestamp.atZone(ZoneId.systemDefault()).toInstant());
                OHLCDataItem item = new OHLCDataItem(date, open, high, low, close, volume);

                // If there's a gap and we have a last valid item
                if (lastValidItem != null && lastTimestamp != null) {
                    Duration gap = Duration.between(lastTimestamp, currentTimestamp);
                    if (gap.toMinutes() > 1) { // If gap is more than 1 minute
                        // Fill in the gap with interpolated points
                        fillGap(dataItems, lastValidItem, item, lastTimestamp, currentTimestamp);
                    }
                }

                dataItems.add(item);
                lastValidItem = item;
                lastTimestamp = currentTimestamp;
            }
        }

        // Create dataset
        DefaultOHLCDataset dataset = new DefaultOHLCDataset(ticker, dataItems.toArray(new OHLCDataItem[0]));

        // Create chart
        JFreeChart chart = ChartFactory.createCandlestickChart(
                ticker + " Stock Price",
                "Time",
                "Price ($)",
                dataset,
                true
        );

        // Customize the chart
        XYPlot plot = (XYPlot) chart.getPlot();
        plot.setBackgroundPaint(Color.WHITE);
        plot.setDomainGridlinePaint(Color.LIGHT_GRAY);
        plot.setRangeGridlinePaint(Color.LIGHT_GRAY);

        // Customize the candlestick renderer
        CandlestickRenderer renderer = (CandlestickRenderer) plot.getRenderer();
        renderer.setUpPaint(Color.GREEN);
        renderer.setDownPaint(Color.RED);
        renderer.setDrawVolume(true);
        renderer.setCandleWidth(3.0);

        // Customize axes
        DateAxis dateAxis = (DateAxis) plot.getDomainAxis();
        dateAxis.setDateFormatOverride(new SimpleDateFormat("MM/dd HH:mm"));

        NumberAxis priceAxis = (NumberAxis) plot.getRangeAxis();
        priceAxis.setAutoRangeIncludesZero(false);

        // Create chart panel
        ChartPanel chartPanel = new ChartPanel(chart);
        chartPanel.setPreferredSize(new Dimension(800, 600));
        chartPanel.setMouseWheelEnabled(true);
        chartPanel.setZoomInFactor(0.8);
        chartPanel.setZoomOutFactor(1.2);

        // Create frame
        JFrame frame = new JFrame("Stock Price Chart - " + ticker);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(chartPanel);

        // Add control panel
        JPanel controlPanel = new JPanel();
        JButton resetButton = new JButton("Reset Zoom");
        resetButton.addActionListener(e -> chartPanel.restoreAutoBounds());
        controlPanel.add(resetButton);
        frame.add(controlPanel, BorderLayout.SOUTH);

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static void fillGap(List<OHLCDataItem> dataItems, OHLCDataItem start, OHLCDataItem end,
                                LocalDateTime startTime, LocalDateTime endTime) {
        long minutes = Duration.between(startTime, endTime).toMinutes() - 1;
        double openStep = (end.getOpen().doubleValue() - start.getClose().doubleValue()) / (minutes + 1);
        double closeStep = openStep;

        for (int i = 1; i <= minutes; i++) {
            LocalDateTime interpolatedTime = startTime.plusMinutes(i);
            Date date = Date.from(interpolatedTime.atZone(ZoneId.systemDefault()).toInstant());

            double interpolatedOpen = start.getClose().doubleValue() + (openStep * i);
            double interpolatedClose = interpolatedOpen;
            // Make the high/low very close to the open/close for flat appearance
            double interpolatedHigh = interpolatedOpen + 0.01;
            double interpolatedLow = interpolatedOpen - 0.01;

            dataItems.add(new OHLCDataItem(
                    date,
                    interpolatedOpen,
                    interpolatedHigh,
                    interpolatedLow,
                    interpolatedClose,
                    0.0  // No volume for interpolated points
            ));
        }
    }

    private static boolean isValidPricePoint(double open, double high, double low, double close) {
        return !Double.isNaN(open) && !Double.isInfinite(open) &&
                !Double.isNaN(high) && !Double.isInfinite(high) &&
                !Double.isNaN(low) && !Double.isInfinite(low) &&
                !Double.isNaN(close) && !Double.isInfinite(close) &&
                high >= low &&
                high >= open &&
                high >= close &&
                low <= open &&
                low <= close &&
                open > 0 && high > 0 && low > 0 && close > 0;
    }
}