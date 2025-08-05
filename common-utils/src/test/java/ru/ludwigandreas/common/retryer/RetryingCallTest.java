package ru.ludwigandreas.common.retryer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class RetryingCallTest {

    @Test
    public void test() {
        // Define a retry policy
        RetryPolicy retryPolicy = new RetryPolicy()
                .retryOn(IOException.class, TimeoutException.class)
                .withMaxRetries(5)
                .withDelay(1, TimeUnit.SECONDS)
                .withIncreasingDelay(true)
                .withDelayFactor(2.0)
                .withMaxDelay(30, TimeUnit.SECONDS);

        // Use the policy with a simple operation
        RetryingCall
                .with(retryPolicy)
                .withListener((attempt, exception, result) -> {
                    System.out.println("Retry attempt #" + attempt + " due to: " +
                            (exception != null ? exception.getMessage() : "Unsatisfactory result"));
                })
                .run(() -> {
                    System.out.println("Executing operation...");
                    if (Math.random() < 0.7) {
                        throw new IOException("Simulated random failure");
                    }
                    System.out.println("Operation succeeded!");
                });


    }

    @Test
    void testReturningValue() {
        // Define a retry policy
        RetryPolicy retryPolicy = new RetryPolicy()
                .retryOn(IOException.class, TimeoutException.class)
                .withMaxRetries(5)
                .withDelay(1, TimeUnit.SECONDS)
                .withIncreasingDelay(true)
                .withDelayFactor(2.0)
                .withMaxDelay(30, TimeUnit.SECONDS);
        // With an operation that returns a value
        String result = RetryingCall
                .with(retryPolicy)
                .get(() -> {
                    if (Math.random() < 0.5) {
                        throw new TimeoutException("Connection timed out");
                    }
                    return "Success!";
                });
        System.out.println("Result: " + result);
    }

    @Test
    void testResultBasedPolicy() {
        // Example with retrying based on result value
        System.out.println("\nExample of retrying until we get a satisfactory result:");
        RetryPolicy resultBasedPolicy = new RetryPolicy()
                .retryOnResult(result1 -> {
                    // Retry if the result is null or an empty string
                    if (result1 == null) return true;
                    if (result1 instanceof String && ((String) result1).isEmpty()) return true;
                    // For numeric results, retry if value is 0 or negative
                    if (result1 instanceof Number && ((Number) result1).doubleValue() <= 0) return true;
                    return false;
                })
                .withMaxRetries(10)
                .withDelay(100, TimeUnit.MILLISECONDS);

        Integer validNumber = RetryingCall
                .with(resultBasedPolicy)
                .withListener((attempt, exception, res) -> {
                    System.out.println("Retry #" + attempt + ", last result: " + res);
                })
                .get(() -> {
                    int randomValue = (int) (Math.random() * 10) - 5; // Random between -5 and 4
                    System.out.println("Generated value: " + randomValue);
                    return randomValue;
                });

        System.out.println("Final valid number: " + validNumber);
    }
}