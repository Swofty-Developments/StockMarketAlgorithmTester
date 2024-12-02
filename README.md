# Stock Market Algorithm Tester
![badge](https://img.shields.io/maven-central/v/net.swofty/StockMarketAlgorithmTester)
![badge](https://img.shields.io/github/last-commit/Swofty/StockMarketAlgorithmTester)
[![badge](https://img.shields.io/github/license/Swofty/StockMarketAlgorithmTester)](https://github.com/Swofty/StockMarketAlgorithmTester/blob/master/LICENSE)

A powerful, extensible Java library for creating and backtesting stock market trading algorithms. Supports advanced trading strategies including options, short selling, and automated position management.

## Table of contents

* [Getting started](#getting-started)
    * [Installation](#installation)
    * [Market Data Providers](#market-data-providers)
    * [Initialization](#initialization)
    * [Caching System](#caching-system)
* [Basic Usage](#basic-usage)
* [Advanced Features](#advanced-features)
* [Building Trading Algorithms](#building-trading-algorithms)
* [Alpha Vantage Fetcher](#alpha-vantage-fetcher)
* [License](#license)

## Getting started

This library is designed for standalone usage in any Java application.

### Installation

Add the dependency to your project from Maven Central:

<details>
    <summary>Maven</summary>

```xml
<dependencies>
    <dependency>
        <groupId>net.swofty</groupId>
        <artifactId>stockmarketalgorithm</artifactId>
        <version>{VERSION}</version>
    </dependency>
</dependencies>
```
</details>

<details>
    <summary>Gradle</summary>

```gradle
dependencies {
    implementation 'net.swofty:stockmarketalgorithm:{VERSION}' // Requires Maven Central
}
```
</details>

### Market Data Providers

The library supports multiple market data providers:

1. **Alpha Vantage** (Recommended for getting started)
    - Free API key available at [Alpha Vantage](https://www.alphavantage.co/support/#api-key)
    - 5 API calls per minute on free tier
    - Includes real-time and historical data

2. **Polygon.io**
    - More extensive data but requires paid subscription
    - Higher rate limits
    - Better for production use

3. **Free Data Provider**
    - Sample historical data for testing
    - No API key required
    - Limited to certain stocks and date ranges

You can also create your own market data provider by implementing the `MarketDataProvider` interface and passing it to the `HistoricalMarketService`.

### Initialization

Here's a complete example of setting up the market data system:

```java
public class MarketExample {
    public static void main(String[] args) {
        // 1. Set up cache directories
        Path vantageCachePath = Path.of("cache/alphavantage/");
        Path dataProviderCachePath = Path.of("cache/market-data/");
        
        // 2. Initialize Alpha Vantage fetcher for additional market data
        AlphaVantageFetcher.setup(
            "YOUR-API-KEY",  // Get your free key from: https://www.alphavantage.co/support/#api-key
            vantageCachePath
        );
        
        // 3. Choose your market data provider
        // Option A: Alpha Vantage Provider
        MarketDataProvider provider = new AlphaVantageProvider("YOUR-API-KEY");
        
        // Option B: Free Data Provider (for testing)
        // MarketDataProvider provider = new FreeDataProvider(dataProviderCachePath);
        
        // Option C: Polygon Provider (if you have a subscription)
        // MarketDataProvider provider = new PolygonProvider("YOUR-POLYGON-KEY");
        
        // 4. Create market service with caching
        HistoricalMarketService marketService = new HistoricalMarketService(
                provider,
                3,  // Number of retries for failed requests
                dataProviderCachePath  // Cache directory for market data
        );
        
        // 5. Define your trading universe
        Set<String> tickers = Set.of("AAPL", "GOOGL", "MSFT");
        
        // 6. Initialize the service with historical data
        marketService.initialize(tickers, 30, MarketConfig.NYSE)
                .thenRun(() -> {
                    // 7. Create and run your backtest
                    new BacktestBuilder()
                            .withStocks(tickers.toArray(new String[0]))
                            .withPreviousDays(30)
                            .withLimitTimesToMarket(MarketConfig.NYSE)
                            .withShouldPrint(true)
                            .withInterval(Duration.ofMinutes(1))
                            .withRunOnMarketClosed(false)  // Skip non-market hours
                            .withProvider(marketService)
                            .withAlgorithm(
                                new SimpleBuyAndHoldAlgorithm("my-algorithm", tickers),
                                1_000_000  // Initial capital
                            )
                            .run()
                            .thenAccept(results -> {
                                System.out.println("Backtest Results:");
                                System.out.println(results);
                                
                                // Access portfolio for specific algorithm
                                Portfolio portfolio = results.portfolios()
                                    .get("my-algorithm");
                            })
                            .exceptionally(error -> {
                                System.err.println("Backtest failed: " + 
                                    error.getMessage());
                                error.printStackTrace();
                                return null;
                            })
                            .join();
                })
                .exceptionally(error -> {
                    System.err.println("Market service initialization failed: " + 
                        error.getMessage());
                    return null;
                });
    }
}
```

### Caching System

The library implements a multi-level caching system to minimize API calls and improve performance:

1. **Alpha Vantage Cache**
    - Caches additional market data like earnings, sentiment, and financial metrics
    - Default 24-hour cache duration
    - Automatically manages API rate limits

2. **Market Data Cache**
    - Caches historical price data for backtesting
    - Persists between runs to avoid re-downloading data
    - Automatically refreshed when data becomes stale

Cache directories are created automatically if they don't exist. Example directory structure:
```
/cache
  /alphavantage
    earnings_cache.json
    metrics_cache.json
    income_cache.json
  /market-data
    AAPL_historical.json
    GOOGL_historical.json
    MSFT_historical.json
```

## Basic Usage

Here's a complete example of creating and running a basic buy-and-hold algorithm:

```java
public class TestAlgorithm {
    public static void main(String[] args) {
        // Setup the Alpha Vantage API fetcher
        AlphaVantageFetcher.setup("YOUR-API-KEY", Path.of("vantage-cache/"));

        // Create market service
        HistoricalMarketService marketService = new HistoricalMarketService(
                new AlphaVantageProvider("YOUR-API-KEY"),
                1,
                Path.of("cache/")
        );
        
        // Define tickers to trade
        Set<String> tickers = Set.of("TSLA");

        // Initialize and run backtest
        marketService.initialize(tickers, 30, MarketConfig.NYSE)
                .thenRun(() -> {
                    new BacktestBuilder()
                            .withStocks(tickers.toArray(new String[0]))
                            .withPreviousDays(30)
                            .withLimitTimesToMarket(MarketConfig.NYSE)
                            .withShouldPrint(false)
                            .withInterval(Duration.ofMinutes(1))
                            .withRunOnMarketClosed(true)
                            .withProvider(marketService)
                            .withAlgorithm(new SimpleBuyAndHoldAlgorithm("simple-day-trader", tickers), 1_000_000)
                            .run()
                            .thenAccept(results -> {
                                System.out.println("Results: " + results);
                                Portfolio portfolio = results.portfolios().get("simple-day-trader");
                            })
                            .join();
                });
    }
}
```

### Creating a Simple Algorithm

Here's an example of a basic buy-and-hold algorithm:

```java
public class SimpleBuyAndHoldAlgorithm implements Algorithm {
    private final String algorithmId;
    private final Set<String> tickers;
    private final Map<String, Boolean> positionHeld;

    public SimpleBuyAndHoldAlgorithm(String algorithmId, Set<String> tickers) {
        this.algorithmId = algorithmId;
        this.tickers = new HashSet<>(tickers);
        this.positionHeld = new HashMap<>();
        tickers.forEach(ticker -> positionHeld.put(ticker, false));
    }

    @Override
    public void onMarketOpen(Map<String, MarketDataPoint> initialData) {
        // Initialize daily strategy
    }

    @Override
    public void onUpdate(Map<String, MarketDataPoint> currentData, LocalDateTime timestamp, Portfolio portfolio) {
        for (String ticker : tickers) {
            MarketDataPoint data = currentData.get(ticker);
            if (data == null) continue;

            // Buy if no position exists
            if (!positionHeld.get(ticker)) {
                portfolio.buyStock(ticker, calculatePositionSize(data.close()), data.close());
                positionHeld.put(ticker, true);
            }
        }
    }

    @Override
    public void onMarketClose(Map<String, MarketDataPoint> finalData) {
        // Clean up end-of-day positions
    }

    private int calculatePositionSize(double price) {
        return (int) (10_000 / price);
    }

    @Override
    public String getAlgorithmId() {
        return algorithmId;
    }
}
```

## Advanced Features

### Position Management
```java
public void onUpdate(Map<String, MarketDataPoint> currentData, LocalDateTime timestamp, Portfolio portfolio) {
    MarketDataPoint data = currentData.get("AAPL");
    
    // Check current position value and P&L
    double positionValue = portfolio.getPositionValue("AAPL", data);
    double unrealizedPnL = portfolio.getUnrealizedPnL("AAPL", data);
    
    // Set stop loss if losing too much
    if (unrealizedPnL < -1000) {
        portfolio.setStopLoss("AAPL", data.close() * 0.95, 100);
    }
}
```

### Short Selling
```java
// Open short position
portfolio.shortStock("AAPL", 100, currentPrice);

// Cover short position
portfolio.coverShort("AAPL", 100, currentPrice);
```

### Options Trading
```java
// Buy call option
portfolio.buyOption(
    "AAPL", 
    OptionType.CALL,
    strikePrice,
    expiration,
    contracts,
    premium
);

// Buy put option
portfolio.buyOption(
    "AAPL",
    OptionType.PUT,
    strikePrice,
    expiration,
    contracts,
    premium
);
```

### Stop Orders
```java
// Set stop loss
portfolio.setStopLoss("AAPL", stopPrice, quantity);

// Set take profit
portfolio.setTakeProfit("AAPL", targetPrice, quantity);
```

## Building Trading Algorithms

Create your own algorithm by implementing the `Algorithm` interface:

```java
public class CustomAlgorithm implements Algorithm {
    @Override
    public void onMarketOpen(Map<String, MarketDataPoint> initialData) {
        // Called at market open with initial data
    }
    
    @Override
    public void onUpdate(Map<String, MarketDataPoint> currentData, LocalDateTime timestamp, Portfolio portfolio) {
        // Called on each price update
        // Implement your trading logic here
    }
    
    @Override
    public void onMarketClose(Map<String, MarketDataPoint> finalData) {
        // Called at market close with final data
    }
    
    @Override
    public String getAlgorithmId() {
        return "custom-algorithm";
    }
}
```

### Backtesting Configuration

The `BacktestBuilder` supports various configuration options:

```java
new BacktestBuilder()
    .withStocks(new String[]{"AAPL", "GOOGL"})  // Stocks to trade
    .withPreviousDays(30)                        // Backtest period
    .withLimitTimesToMarket(MarketConfig.NYSE)   // Market hours
    .withShouldPrint(true)                       // Print progress
    .withInterval(Duration.ofMinutes(5))         // Trading interval
    .withRunOnMarketClosed(false)                // Skip closed hours
    .withProvider(marketService)                 // Data provider
    .withAlgorithm(algorithm, 100_000)           // Algorithm and capital
    .run();
```

## Alpha Vantage Fetcher

The `AlphaVantageFetcher` provides access to rich market data beyond just prices. This includes earnings events, financial metrics, income statements, and news sentiment analysis. All data is automatically cached to respect API limits and improve performance.

### Setup

First, initialize the fetcher:

```java
// Get your free API key from: https://www.alphavantage.co/support/#api-key
Path cachePath = Path.of("cache/alphavantage/");
AlphaVantageFetcher.setup("YOUR-API-KEY", cachePath);

// Create a fetcher instance with a specific point in time
// This is useful for backtesting to prevent future data leakage
AlphaVantageFetcher fetcher = new AlphaVantageFetcher(LocalDateTime.now());
```

### Earnings Information

Get upcoming and historical earnings calls:

```java
fetcher.getEarningsCalls("AAPL")
    .thenAccept(earnings -> {
        for (EarningsEvent event : earnings) {
            System.out.printf("Company: %s%n", event.companyName());
            System.out.printf("Report Date: %s%n", event.reportDate());
            System.out.printf("Fiscal Period End: %s%n", event.fiscalDateEnding());
        }
    });
```

### Financial Metrics

Get key financial ratios and metrics:

```java
fetcher.getFinancialMetrics("AAPL")
    .thenAccept(metrics -> {
        System.out.printf("PE Ratio: %.2f%n", metrics.peRatio());
        System.out.printf("Profit Margin: %.2f%%%n", metrics.profitMargin() * 100);
        System.out.printf("Operating Margin: %.2f%%%n", metrics.operatingMargin() * 100);
        System.out.printf("Return on Equity: %.2f%%%n", metrics.returnOnEquity() * 100);
    });
```

### Income Statements

Access quarterly income statement data:

```java
fetcher.getIncomeStatement("AAPL")
    .thenAccept(income -> {
        for (Map<String, Double> quarter : income.quarterlyMetrics()) {
            LocalDateTime reportDate = LocalDateTime.ofEpochSecond(
                quarter.get("reportDate").longValue(), 0, ZoneOffset.UTC);
            
            System.out.printf("Quarter ending: %s%n", reportDate);
            System.out.printf("Revenue: $%.2fM%n", quarter.get("totalRevenue") / 1_000_000);
            System.out.printf("Gross Profit: $%.2fM%n", quarter.get("grossProfit") / 1_000_000);
            System.out.printf("Operating Income: $%.2fM%n", quarter.get("operatingIncome") / 1_000_000);
            System.out.printf("Net Income: $%.2fM%n", quarter.get("netIncome") / 1_000_000);
        }
    });
```

### News Sentiment Analysis

Get news articles with sentiment scores:

```java
fetcher.getNewsSentiments("AAPL")
    .thenAccept(articles -> {
        for (NewsSentiment article : articles) {
            System.out.printf("Title: %s%n", article.title());
            System.out.printf("Published: %s%n", article.publishedTime());
            System.out.printf("Source: %s%n", article.source());
            System.out.printf("Overall Sentiment: %s (%.2f)%n", 
                article.overallSentimentLabel(),
                article.overallSentimentScore());
            
            // Per-ticker sentiment scores
            for (TickerSentiment sentiment : article.tickerSentiments()) {
                System.out.printf("Ticker: %s, Relevance: %.2f, Sentiment: %s (%.2f)%n",
                    sentiment.ticker(),
                    sentiment.relevanceScore(),
                    sentiment.sentimentLabel(),
                    sentiment.sentimentScore());
            }
        }
    });
```

### Caching System

Cache files are stored in the specified directory:
```
/cache/alphavantage/
  earnings_cache.json   // Earnings call data
  metrics_cache.json    // Financial metrics
  income_cache.json     // Income statements
```

### Rate Limits

The free Alpha Vantage API has the following limits:
- 5 API calls per minute
- 500 calls per day
- The fetcher automatically manages these limits via its caching system

For higher limits, consider upgrading to a paid API key at [Alpha Vantage Premium](https://www.alphavantage.co/premium/).

## License
StockMarketAlgorithm is licensed under the permissive MIT license. Please see [`LICENSE.txt`](https://github.com/Swofty/StockMarketAlgorithm/blob/master/LICENSE.txt) for more information.
