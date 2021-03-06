package dk.ralu.opensource.log.test.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import dk.ralu.opensource.log.test.util.LoggingTestUtil.AwaitTimeoutException;
import dk.ralu.opensource.log.test.util.LoggingTestUtil.LoggingEventRecorder;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LoggingTestUtilTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingTestUtilTest.class);

    private AtomicBoolean keepRunningNumberLoggingThread = new AtomicBoolean();
    private CountDownLatch numberLoggingThreadHasStopped = new CountDownLatch(1);

    /**
     * Shows how we can "record" log messages, and how to assert they are as expected.
     */
    @Test
    void demoVerifyLogMessages() {

        // By using a try-with-resources, we ensure the LoggingTestUtil is closed when done (which unregister's its log appender).
        // Passing a logger as argument, means the util will only "listen" to log messages written to this logger or one of its descendants.
        try (LoggingTestUtil loggingTestUtil = new LoggingTestUtil(LOGGER)) {

            // We overwrite the configured level (will be reset when we close the LoggingTestUtil).
            loggingTestUtil.setLevel(Level.ALL);

            // This will collect all log messages from now on and until we close the LoggingTestUtil.
            // The recorder stores all logging events in memory - but using it in a single test shouldn't be a problem.
            LoggingEventRecorder loggingEventRecorder = loggingTestUtil.addLoggingEventRecorder();

            // Lets log something (here we would usually call some code that we expect to write some log statements when run)

            LOGGER.debug("Hello on {} level", "debug");
            LOGGER.info("Hello on info level");

            // Let's assert the log statements we have received are as expected

            assertEquals(2, loggingEventRecorder.getLoggingEvents().size());

            assertEquals(1, loggingEventRecorder.countMatches("Hello on debug level"));

            assertEquals(2, loggingEventRecorder.countMatches(
                    loggingEvent -> loggingEvent.getFormattedMessage().startsWith("Hello")));

            assertEquals(1, loggingEventRecorder.countMatches(
                    loggingEvent -> loggingEvent.getLevel() == Level.INFO));
        }
    }

    /**
     * Sometimes we are testing code, that runs in multiple threads. It can be useful to let the test main thread wait (block) until another
     * thread has written a specific log message. In other words a kind of "crappy" coordination between threads, which should never be done
     * in production code, but can be OK to do, when we're testing (it might be even worse to built thread coordination into the production
     * code just to be able to test it).
     */
    @Test
    void demoAwaitLogMessages() throws AwaitTimeoutException, InterruptedException {

        // Not giving a specific logger to the LoggingTestUtil constructor, means it will use the root logger, which in turn means all log
        // messages will be handled (unless ignored because of the log level set in the config).
        try (LoggingTestUtil loggingTestUtil = new LoggingTestUtil()) {

            loggingTestUtil.setLevel(Level.ALL);

            // Let's start a thread, that logs a sequence of numbers (1-100 in a never ending loop), just to simulate a situation, where we
            // can wait for another thread to log something (as we then know how far it is), before we let the main test thread continue.
            startNumberLoggingThread();

            // Await requires a timeout to ensure, that our test stops & fails, even if the log message(s) we are waiting for never comes.
            // Await takes a predicate, that must return true if the current logging event is one of those we waited for.
            // Here we say we want to wait for 5 matching events before await should return.
            // The await call returns the actual logging events that matched our predicate.
            List<ILoggingEvent> loggingEvents = loggingTestUtil.awaitLoggingEvents(
                    Duration.ofMinutes(1),
                    5,
                    loggingEvent -> loggingEvent.getFormattedMessage().endsWith("12") || loggingEvent.getFormattedMessage().endsWith("24")
            );

            // Let's just write out the logging messages that matched our predicate.
            LOGGER.info(
                    "Finally we got the 5 log messages we were waiting for:\n{}",
                    loggingEvents.stream()
                            .map(ILoggingEvent::getFormattedMessage)
                            .collect(Collectors.joining(", ", "{", "}"))
            );

            // Will output something a la:
            // Finally we got the 5 log messages we were waiting for:
            // {Current number: 12, Current number: 24, Current number: 12, Current number: 24, Current number: 12}

        } finally {
            stopNumberLoggingThread();
        }
    }

    /**
     * Shows how we can get a timeout.
     */
    @Test
    void demoAwaitLogMessagesWithTimeout() {

        try (LoggingTestUtil loggingTestUtil = new LoggingTestUtil()) {

            // Note: the log level for the root logger (see logback-test.xml) is set to Level WARN, and as the number logging thread logs
            // on level INFO, we will never get the log messages we are waiting for.
            // loggingTestUtil.setLevel(Level.ALL);

            startNumberLoggingThread();

            assertThrows(AwaitTimeoutException.class, () ->

                    loggingTestUtil.awaitLoggingEvents(
                            Duration.ofSeconds(2), // Let's fail fast
                            5,
                            loggingEvent -> loggingEvent.getFormattedMessage().endsWith("12") || loggingEvent.getFormattedMessage()
                                    .endsWith("24")
                    )
            );


        } finally {
            stopNumberLoggingThread();
        }
    }

    /**
     * You can define your own logging event listeners - if you come up with something cool - please share :-)
     */
    @Test
    void demoDefineYourOwnLoggingEventListeners() {

        try (LoggingTestUtil loggingTestUtil = new LoggingTestUtil()) {

            loggingTestUtil.addLoggingEventListener(loggingEvent -> System.out.println(loggingEvent.getMessage()));

            LOGGER.warn("Message without an arg");
            LOGGER.warn("Message that says {} with an arg!", "Hello");

            // Will print:
            // Message without an arg
            // Message that says {} with an arg!
        }
    }

    private void stopNumberLoggingThread() {
        keepRunningNumberLoggingThread.set(false);
        try {
            numberLoggingThreadHasStopped.await();
        } catch (InterruptedException ignored) {
        }
    }

    private void startNumberLoggingThread() {
        Thread thread = new Thread(() -> {
            keepRunningNumberLoggingThread.set(true);
            int currentNumber = 0;
            while (keepRunningNumberLoggingThread.get()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                }
                LOGGER.info("Current number: {}", ++currentNumber);
                if (currentNumber >= 100) {
                    currentNumber = 0;
                }
            }
            numberLoggingThreadHasStopped.countDown();
        });
        thread.setDaemon(true);
        thread.setName("Test thread");
        thread.start();
    }
}
