package fr.vergne.bitcoin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BinaryOperator;

import org.json.JSONException;
import org.json.JSONObject;

/*
 * Sources used to build this code:
 * https://min-api.cryptocompare.com/
 * https://stackoverflow.com/a/4308662/2031083
 * https://stackoverflow.com/a/12806303/2031083
 * http://www.baeldung.com/java-8-date-time-intro
 * https://en.wikipedia.org/wiki/Exponential_backoff
 */
public class Bitcoin {

	/**
	 * Total number of days to retrieve.
	 */
	private static final int TOTAL_DAYS = 50;
	/**
	 * Time (in seconds) allocated to retrieve every days.
	 */
	private static final int GLOBAL_TIMEOUT = 10;
	/**
	 * Number of attempts allowed to retrieve each day.
	 */
	private static final int MAX_ATTEMPTS = 10;
	/**
	 * Current day.
	 */
	private static final LocalDate NOW = LocalDate.now();
	/**
	 * The target currency
	 */
	private static final Currency CURRENCY = Currency.DOLLAR;

	// Other constants
	private static final String ENCODING = "UTF-8";
	private static final Random RANDOM = new Random(System.currentTimeMillis());

	public static void main(String[] args) throws InterruptedException {
		System.out.println("Add retrieval tasks for the last " + TOTAL_DAYS + " days");
		ExecutorService executor = newExecutor();
		Collection<Entry> entries = newEntryCollection();
		for (int day = 1; day <= TOTAL_DAYS; day++) {
			executor.submit(newRetrievalTask(entries, day, CURRENCY));
		}
		executor.shutdown();

		System.out.println("Wait for termination");
		boolean isTerminated = executor.awaitTermination(GLOBAL_TIMEOUT, TimeUnit.SECONDS);
		if (!isTerminated) {
			executor.shutdownNow();
			throw new RuntimeException("Not terminated, only " + entries.size() + " computed so far");
		} else {
			System.out.println("Properly terminated with " + entries.size() + " entries");
		}

		System.out.println("Average: " + CURRENCY.format(averageRates(entries)));
	}

	private static Runnable newRetrievalTask(Collection<Entry> rates, int day, Currency currency) {
		return () -> {
			try {
				long timestamp = getTimestamp(day);
				boolean added = false;
				for (int attempt = 0; !added && attempt < MAX_ATTEMPTS; attempt++) {
					try {
						waitBeforeAttempt(attempt);
						double rate = getBitcoinRateExchange(timestamp, currency);
						Entry dayRate = new Entry("Day " + day, rate, currency);
						added = rates.add(dayRate);
						System.out.println(dayRate);
					} catch (InterruptedException cause) {
						return; // Abort
					} catch (RateLimitExceededException cause) {
						continue; // Retry
					}
				}
			} catch (Exception cause) {
				cause.printStackTrace();
			}
		};
	}

	private static void waitBeforeAttempt(int attempt) throws InterruptedException {
		if (attempt == 0) {
			// Don't wait
		} else {
			// Wait with exponential backoff
			int max = (1 << attempt) - 1;
			int delay = RANDOM.nextInt(max * 100);
			Thread.sleep(delay);
		}
	}

	private static Collection<Entry> newEntryCollection() {
		Collection<Entry> collection;
		// Use linked list to optimize additions & iterations.
		collection = new LinkedList<>();
		// Synchronize to not override entries (corrupted iterator).
		collection = Collections.synchronizedCollection(collection);
		return collection;
	}

	private static ExecutorService newExecutor() {
		int maxThreads = Runtime.getRuntime().availableProcessors();
		// Generate daemon threads to stop when global timeout reached
		ThreadFactory daemonFactory = new ThreadFactory() {

			@Override
			public Thread newThread(Runnable r) {
				Thread thread = new Thread(r);
				thread.setDaemon(true);
				return thread;
			}
		};
		return Executors.newFixedThreadPool(maxThreads, daemonFactory);
	}

	private static double averageRates(Collection<Entry> entries) {
		BinaryOperator<Double> addition = (a, b) -> a + b;
		return entries.parallelStream().map(entry -> entry.rate).reduce(0.0, addition) / entries.size();
	}

	private static long getTimestamp(int day) {
		return NOW.minusDays(day).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
	}

	private static double getBitcoinRateExchange(long timestamp, Currency currency)
			throws IOException, RateLimitExceededException {
		String url = "https://min-api.cryptocompare.com/data/dayAvg";
		url += "?fsym=BTC"; // Get bitcoins rate exchange
		url += "&tsym=" + currency.getID(); // At the given currency
		url += "&toTs=" + timestamp; // For the given timestamp
		JSONObject json = readJsonFromUrl(url);
		return json.getDouble(currency.getID());
	}

	private static JSONObject readJsonFromUrl(String url) throws IOException, RateLimitExceededException {
		try (InputStream in = new URL(url).openStream()) {
			InputStreamReader inReader = new InputStreamReader(in, Charset.forName(ENCODING));
			BufferedReader jsonReader = new BufferedReader(inReader);
			String jsonText = readAll(jsonReader);
			if (jsonText.contains("Rate limit excedeed!")) {
				throw new RateLimitExceededException();
			} else {
				try {
					JSONObject object = new JSONObject(jsonText);
					return object;
				} catch (JSONException cause) {
					cause.printStackTrace();
					throw cause;
				}
			}
		}
	}

	private static String readAll(Reader reader) throws IOException {
		StringBuilder builder = new StringBuilder();
		int character;
		while ((character = reader.read()) != -1) {
			builder.append((char) character);
		}
		return builder.toString();
	}

	/**
	 * Thrown when the server rejects our query due to a rate limit exceeded. It
	 * happens when too much queries are sent too quickly. The best solution is to
	 * retry later.
	 */
	@SuppressWarnings("serial")
	static class RateLimitExceededException extends Exception {

	}

	enum Currency {
		EURO("EUR", "#0.00 €"), DOLLAR("USD", "$#0.00");

		private final String ID;
		private final DecimalFormat formatter;

		private Currency(String ID, String format) {
			this.ID = ID;
			this.formatter = new DecimalFormat(format);
		}

		public String getID() {
			return ID;
		}

		public String format(double value) {
			return formatter.format(value);
		}
	}

	/**
	 * Represents a data point for computing an average bitcoin rate exchange. It
	 * stores an ID (for display) and the rate retrieved.
	 */
	static class Entry {
		final String id;
		final double rate;
		final Currency currency;

		public Entry(String id, double rate, Currency currency) {
			this.id = id;
			this.rate = rate;
			this.currency = currency;
		}

		@Override
		public String toString() {
			return id + ": " + CURRENCY.format(rate);
		}
	}

}
