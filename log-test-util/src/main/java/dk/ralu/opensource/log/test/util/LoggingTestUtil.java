package dk.ralu.opensource.log.test.util;


import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEventVO;
import ch.qos.logback.core.AppenderBase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.LoggerFactory;

/**
 * Only works with Logback Classic.
 */
public class LoggingTestUtil implements AutoCloseable {

    private final InternalAppender internalAppender = new InternalAppender();
    private final Level levelAtInitializationTime;
    private final Logger logger;
    private final Map<Consumer<ILoggingEvent>, Object> loggingEventListeners = new IdentityHashMap<>(); // Value not used

    /**
     * Creates a new instance, that received logging events by registering a custom logging appender on the root logger.
     */
    public LoggingTestUtil() {
        this(((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME));
    }

    /**
     * Creates a new instance, that received logging events by registering a custom logging appender on the provided logger.
     *
     * @param slf4jLogger the logger on which to register.
     */
    public LoggingTestUtil(org.slf4j.Logger slf4jLogger) {
        if (!(Objects.requireNonNull(slf4jLogger) instanceof Logger)) {
            throw new IllegalStateException("Only works with Logback Classic");
        }
        logger = (Logger) slf4jLogger;
        levelAtInitializationTime = logger.getLevel();
        nonSynchronizedActivate();
    }

    /**
     * Overrides the logging level threshold on the logger on which the logging test util is connected. Note: When closing this logger the
     * logging level will automatically be reset to its original value.
     *
     * @param newLevel the log level you want to use.
     */
    public synchronized void setLevel(Level newLevel) {
        logger.setLevel(newLevel);
    }

    /**
     * Reset the logging level threshold of the logger on which the logging test util was connected.
     */
    public synchronized void resetLevel() {
        logger.setLevel(levelAtInitializationTime);
    }

    /**
     * Get the current logging level threshold of the logger on which the logging test util was connected.
     *
     * @return the current logging level.
     */
    public synchronized Level getLevel() {
        return logger.getLevel();
    }

    /**
     * A quick way to reset everything. The instance cannot be used anymore.
     * <p>
     * More precisely does the following:
     * <ul>
     * <li>Resets the root logger to its initial log level
     * <li>Removes all registered logging event listeners
     * <li>Deactivates the associated appender
     * </ul>
     */
    @Override
    public synchronized void close() {
        resetLevel();
        loggingEventListeners.clear();
        deactivate();
    }

    /**
     * Registers a new logging even listener.
     *
     * @param loggingEventListener the listener to register.
     */
    public synchronized void addLoggingEventListener(Consumer<ILoggingEvent> loggingEventListener) {
        loggingEventListeners.put(Objects.requireNonNull(loggingEventListener), null);
    }

    /**
     * Removes a previously registered logging even listener.
     *
     * @param loggingEventListener the listener to remove.
     */
    public synchronized void removeLoggingEventListener(Consumer<ILoggingEvent> loggingEventListener) {
        loggingEventListeners.remove(Objects.requireNonNull(loggingEventListener));
    }

    /**
     * Registers a recorder that stores all the logging events that happen from now on. Deregister by calling {@link
     * #removeLoggingEventListener(Consumer)} with the recorder instance as parameter.
     *
     * @return the recorder.
     */
    public synchronized LoggingEventRecorder addLoggingEventRecorder() {
        LoggingEventRecorder loggingEventRecorder = new LoggingEventRecorder();
        addLoggingEventListener(loggingEventRecorder);
        return loggingEventRecorder;
    }

    /**
     * As {@link #awaitLoggingEvents(Duration, int, Predicate)} but only awaits the fist logging event that matches.
     *
     * @param timeout               the max duration to wait for the matching logging events
     * @param loggingEventPredicate the predicate that the awaited logging events must match
     * @return a list of the logging events that matched the predicate (size will match times)
     * @throws InterruptedException  if the awaiting thread was interrupted - e.g. if you annotated a JUnit 5 test with {@code @Timeout}.
     * @throws AwaitTimeoutException if the required number of matching logging events didn't turn up within the duration
     */
    public ILoggingEvent awaitLoggingEvent(Duration timeout, Predicate<ILoggingEvent> loggingEventPredicate)
            throws InterruptedException, AwaitTimeoutException {

        return awaitLoggingEvents(timeout, 1, loggingEventPredicate).get(0);
    }

    /**
     * Blocks the current thread until {@code numberOfEvents} matching {@code AwaitTimeoutException} has happened.
     *
     * @param timeout               the max duration to wait for the matching logging events
     * @param numberOfEvents        the number of matching logging events to wait for
     * @param loggingEventPredicate the predicate that the awaited logging events must match
     * @return a list of the logging events that matched the predicate (size will match times)
     * @throws InterruptedException  if the awaiting thread was interrupted - e.g. if you annotated a JUnit 5 test with {@code @Timeout}.
     * @throws AwaitTimeoutException if the required number of matching logging events didn't turn up within the duration
     */
    public List<ILoggingEvent> awaitLoggingEvents(Duration timeout, int numberOfEvents, Predicate<ILoggingEvent> loggingEventPredicate)
            throws InterruptedException, AwaitTimeoutException {

        LoggingEventSynchronizer loggingEventSynchronizer = new LoggingEventSynchronizer(timeout, numberOfEvents, loggingEventPredicate);
        try {
            synchronized (this) {
                addLoggingEventListener(loggingEventSynchronizer);
            }
            return loggingEventSynchronizer.awaitLoggingEvents();
        } finally {
            synchronized (this) {
                removeLoggingEventListener(loggingEventSynchronizer);
            }
        }
    }

    /**
     * Records logging events by storing them in memory.
     */
    public class LoggingEventRecorder implements Consumer<ILoggingEvent> {

        private final List<ILoggingEvent> loggingEvents = new ArrayList<>();

        /**
         * Called when a new logging event happens.
         *
         * @param loggingEvent the logging event.
         */
        @Override
        public void accept(ILoggingEvent loggingEvent) {
            loggingEvents.add(loggingEvent);
        }

        /**
         * Clears the logging events recorded up until now.
         */
        public void clear() {
            synchronized (LoggingTestUtil.this) {
                loggingEvents.clear();
            }
        }

        /**
         * Get the recorded logging events.
         *
         * @return the recorded logging events
         */
        public List<ILoggingEvent> getLoggingEvents() {
            synchronized (LoggingTestUtil.this) {
                return new ArrayList<>(loggingEvents);
            }
        }

        /**
         * Count how many of the recorded log messages match the given predicate.
         *
         * @param loggingEventPredicate the predicate logging events must match.
         * @return the number of matches.
         */
        public int countMatches(Predicate<ILoggingEvent> loggingEventPredicate) {
            int matches = 0;
            for (ILoggingEvent loggingEvent : loggingEvents) {
                if (loggingEventPredicate.test(loggingEvent)) {
                    matches++;
                }
            }
            return matches;
        }

        /**
         * Counts the number of logging messages that match the given string.
         *
         * @param logMessage the log message to count.
         * @return how many log messages matched.
         */
        public int countMatches(String logMessage) {
            return countMatches(loggingEvent -> Objects.equals(logMessage, loggingEvent.getFormattedMessage()));
        }
    }

    private static class LoggingEventSynchronizer implements Consumer<ILoggingEvent> {

        private final Predicate<ILoggingEvent> loggingEventPredicate;
        private final Duration duration;
        private final CountDownLatch countDownLatch;
        private final List<ILoggingEvent> loggingEvents = new ArrayList<>();
        private final int times;

        private LoggingEventSynchronizer(Duration duration, int times, Predicate<ILoggingEvent> loggingEventPredicate) {
            this.loggingEventPredicate = Objects.requireNonNull(loggingEventPredicate);
            this.duration = Objects.requireNonNull(duration);
            this.countDownLatch = new CountDownLatch(times);
            this.times = times;
        }

        @Override
        public void accept(ILoggingEvent loggingEvent) {
            if (loggingEvents.size() < times && loggingEventPredicate.test(loggingEvent)) {
                loggingEvents.add(loggingEvent);
                countDownLatch.countDown();
            }
        }

        private List<ILoggingEvent> awaitLoggingEvents() throws InterruptedException, AwaitTimeoutException {
            boolean wasCompleted = countDownLatch.await(duration.toMillis(), TimeUnit.MILLISECONDS);
            if (!wasCompleted) {
                throw new AwaitTimeoutException();
            }
            return loggingEvents;
        }
    }

    /**
     * Thrown if an invocation of {@code await..()} timed out before the expected logging message(s) were seen.
     */
    public static class AwaitTimeoutException extends Exception {

        private AwaitTimeoutException() {
            super("Did not receive the requested logging events within duration");
        }
    }

    // http://logback.qos.ch/manual/appenders.html#WriteYourOwnAppender
    private class InternalAppender extends AppenderBase<ILoggingEvent> {

        private InternalAppender() {
            setName(InternalAppender.class.getSimpleName() + "-" + UUID.randomUUID());
        }

        @Override
        protected void append(ILoggingEvent eventObject) {
            onLoggingEvent(eventObject);
        }
    }

    private void nonSynchronizedActivate() {
        internalAppender.start();
        logger.addAppender(internalAppender);
    }

    private synchronized void deactivate() {
        logger.detachAppender(internalAppender);
        internalAppender.stop();
    }

    private synchronized void onLoggingEvent(ILoggingEvent loggingEvent) {
        LoggingEventVO loggingEventVo = LoggingEventVO.build(loggingEvent);
        for (Consumer<ILoggingEvent> loggingEventListener : loggingEventListeners.keySet()) {
            loggingEventListener.accept(loggingEventVo);
        }
    }
}
