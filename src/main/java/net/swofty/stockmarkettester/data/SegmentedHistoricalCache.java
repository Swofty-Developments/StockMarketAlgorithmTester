package net.swofty.stockmarkettester.data;

import net.swofty.stockmarkettester.orders.HistoricalData;
import net.swofty.stockmarkettester.orders.MarketDataPoint;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SegmentedHistoricalCache {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private final Path cacheDirectory;
    private final Map<String, TreeMap<LocalDateTime, CacheSegment>> segmentIndex;

    private static class CacheSegment implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private final LocalDateTime start;
        private final LocalDateTime end;
        private final String ticker;
        private final HistoricalData data;

        public CacheSegment(String ticker, LocalDateTime start, LocalDateTime end, HistoricalData data) {
            this.ticker = ticker;
            this.start = start;
            this.end = end;
            this.data = data;
        }

        public boolean containsTimeRange(LocalDateTime queryStart, LocalDateTime queryEnd) {
            return !start.isAfter(queryStart) && !end.isBefore(queryEnd);
        }

        public boolean overlaps(LocalDateTime queryStart, LocalDateTime queryEnd) {
            return !end.isBefore(queryStart) && !start.isAfter(queryEnd);
        }

        public HistoricalData getData() {
            return data;
        }

        public LocalDateTime getStart() {
            return start;
        }

        public LocalDateTime getEnd() {
            return end;
        }
    }

    public SegmentedHistoricalCache(Path cacheDirectory) {
        this.cacheDirectory = cacheDirectory;
        this.segmentIndex = new ConcurrentHashMap<>();
        initializeFromDisk();
    }

    private void initializeFromDisk() {
        if (!Files.exists(cacheDirectory)) {
            try {
                Files.createDirectories(cacheDirectory);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create cache directory", e);
            }
            return;
        }

        try {
            Files.walk(cacheDirectory)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".cache"))
                    .forEach(this::loadSegment);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize cache from disk", e);
        }
    }

    private void loadSegment(Path path) {
        try (ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(path))) {
            CacheSegment segment = (CacheSegment) ois.readObject();
            addToIndex(segment);
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Failed to load cache segment: " + path);
            try {
                Files.delete(path);
            } catch (IOException ignored) {}
        }
    }

    private void addToIndex(CacheSegment segment) {
        segmentIndex.computeIfAbsent(segment.ticker, k -> new TreeMap<>())
                .put(segment.start, segment);
    }

    public Optional<HistoricalData> get(String ticker, LocalDateTime start, LocalDateTime end) {
        TreeMap<LocalDateTime, CacheSegment> segments = segmentIndex.get(ticker);
        if (segments == null) return Optional.empty();

        // First try to find a single segment that contains the entire range
        for (CacheSegment segment : segments.values()) {
            if (segment.containsTimeRange(start, end)) {
                return Optional.of(segment.getData());
            }
        }

        // If no single segment contains the range, try to merge overlapping segments
        List<CacheSegment> overlappingSegments = segments.values().stream()
                .filter(s -> s.overlaps(start, end))
                .sorted(Comparator.comparing(CacheSegment::getStart))
                .collect(Collectors.toList());

        if (overlappingSegments.isEmpty()) return Optional.empty();

        // Check if segments form a continuous range
        LocalDateTime currentEnd = overlappingSegments.get(0).getStart();
        for (CacheSegment segment : overlappingSegments) {
            if (segment.getStart().isAfter(currentEnd)) {
                return Optional.empty(); // Gap in the data
            }
            currentEnd = segment.getEnd();
        }

        if (currentEnd.isBefore(end)) return Optional.empty();

        // Merge the segments
        HistoricalData mergedData = new HistoricalData(ticker);
        for (CacheSegment segment : overlappingSegments) {
            List<MarketDataPoint> points = segment.getData().getDataPoints(start, end);
            points.forEach(mergedData::addDataPoint);
        }

        return Optional.of(mergedData);
    }

    public void put(String ticker, LocalDateTime start, LocalDateTime end, HistoricalData data) {
        CacheSegment segment = new CacheSegment(ticker, start, end, data);
        addToIndex(segment);
        saveSegment(segment);
    }

    private void saveSegment(CacheSegment segment) {
        Path path = getSegmentPath(segment);
        try (ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(path))) {
            oos.writeObject(segment);
        } catch (IOException e) {
            System.err.println("Failed to save cache segment: " + e.getMessage());
        }
    }

    private Path getSegmentPath(CacheSegment segment) {
        String filename = String.format("%s_%s_to_%s.cache",
                segment.ticker,
                segment.start.format(DATE_FORMAT),
                segment.end.format(DATE_FORMAT));
        return cacheDirectory.resolve(filename);
    }

    public void clearCache() {
        try {
            Files.walk(cacheDirectory)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        try {
                            Files.delete(file);
                        } catch (IOException e) {
                            System.err.println("Failed to delete cache file: " + file);
                        }
                    });
            segmentIndex.clear();
        } catch (IOException e) {
            throw new RuntimeException("Failed to clear cache", e);
        }
    }
}