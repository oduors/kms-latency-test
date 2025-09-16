package com.example;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public class KmsLatencyTest {
    private static final Logger logger = LoggerFactory.getLogger(KmsLatencyTest.class);
    
    // CUSTOMIZE: Your KMS key ARN and region
    private static final String KEY_ID = "arn:aws:kms:eu-central-1:368825226956:key/e882b555-32d8-435b-b03d-610fb77f3d5c";
    private static final Region AWS_REGION = Region.EU_CENTRAL_1;
    
    // CUSTOMIZE: Test duration and load
    private static final Duration TEST_DURATION = Duration.ofMinutes(10);
    private static final int TARGET_TPS = 100;
    private static final int THREAD_POOL_SIZE = 50;
    
    // CUSTOMIZE: Enable/disable operations
    private static final boolean ENABLE_DESCRIBE_KEY = true;
    private static final boolean ENABLE_SIGN = true;
    private static final boolean ENABLE_VERIFY = true;
    
    // CUSTOMIZE: Threshold for slow calls (1 second)
    private static final long SLOW_CALL_THRESHOLD = 1000;
    
    private static final Map<String, List<Double>> latenciesByOperation = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> slowCallsByOperation = new ConcurrentHashMap<>();
    private static final AtomicInteger totalCalls = new AtomicInteger(0);
    private static final Map<String, String> requestIdMap = new ConcurrentHashMap<>();
    private static final ThreadLocal<String> currentRequestId = new ThreadLocal<>();
    private static final ThreadLocal<Long> operationStartTime = new ThreadLocal<>();
    
    static class LatencyTrackingInterceptor implements ExecutionInterceptor {
        private final Map<String, Long> requestStartTimes = new ConcurrentHashMap<>();
        private final Map<String, Long> sdkStartTimes = new ConcurrentHashMap<>();
        private final Map<String, Long> networkStartTimes = new ConcurrentHashMap<>();

        @Override
        public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes executionAttributes) {
            String requestId = UUID.randomUUID().toString();
            sdkStartTimes.put(requestId, System.nanoTime());
            currentRequestId.set(requestId);
        }

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes executionAttributes) {
            String requestId = currentRequestId.get();
            if (requestId != null) {
                networkStartTimes.put(requestId, System.nanoTime());
            }
        }

        @Override
        public void afterExecution(Context.AfterExecution context, ExecutionAttributes executionAttributes) {
            String requestId = currentRequestId.get();
            if (requestId != null) {
                long endTime = System.nanoTime();
                long sdkStartTime = sdkStartTimes.remove(requestId);
                long networkStartTime = networkStartTimes.remove(requestId);
                
                double totalTime = (endTime - sdkStartTime) / 1_000_000.0;
                double sdkTime = (networkStartTime - sdkStartTime) / 1_000_000.0;
                double networkTime = (endTime - networkStartTime) / 1_000_000.0;

                String awsRequestId = context.httpResponse().firstMatchingHeader("x-amzn-RequestId").orElse("Unknown");
                
                if (totalTime >= SLOW_CALL_THRESHOLD) {
                    logger.warn("High latency detected - Request ID: {}", awsRequestId);
                    logger.warn("Operation: {}", context.request().getClass().getSimpleName());
                    logger.warn("Total Time: {:.2f}ms", totalTime);
                    logger.warn("  SDK Processing: {:.2f}ms", sdkTime);
                    logger.warn("  Network + Server Time: {:.2f}ms", networkTime);
                    
                    requestIdMap.put(context.request().getClass().getSimpleName() + "-" + totalTime, awsRequestId);
                }
                currentRequestId.remove();
            }
        }

        @Override
        public void afterMarshalling(Context.AfterMarshalling context, ExecutionAttributes executionAttributes) {
            // Record SDK processing completion
            String requestId = currentRequestId.get();
            if (requestId != null) {
                requestStartTimes.put(requestId, System.nanoTime());
            }
        }
    }

    public static void main(String[] args) {
        logger.info("Initializing KMS latency test...");
        logger.info("Test Configuration:");
        logger.info("  Duration: {}", TEST_DURATION);
        logger.info("  Target TPS: {}", TARGET_TPS);
        logger.info("  Thread Pool Size: {}", THREAD_POOL_SIZE);
        logger.info("  Region: {}", AWS_REGION);
        
        Arrays.asList("DescribeKey", "Sign", "Verify")
              .forEach(op -> {
                  latenciesByOperation.put(op, Collections.synchronizedList(new ArrayList<>()));
                  slowCallsByOperation.put(op, new AtomicInteger(0));
              });

        KmsClient kmsClient = KmsClient.builder()
            .region(AWS_REGION)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .overrideConfiguration(ClientOverrideConfiguration.builder()
                .addExecutionInterceptor(new LatencyTrackingInterceptor())
                .build())
            .build();

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        logger.info("Starting KMS latency tests...");
        Instant startTime = Instant.now();

        scheduler.scheduleAtFixedRate(() -> 
            printSummary(startTime), 1, 1, TimeUnit.MINUTES);

        long submitDelayNanos = 1_000_000_000L / TARGET_TPS;

        while (Duration.between(startTime, Instant.now()).compareTo(TEST_DURATION) < 0) {
            long cycleStart = System.nanoTime();
            
            if (ENABLE_DESCRIBE_KEY) {
                executor.submit(() -> performDescribeKey(kmsClient));
            }
            if (ENABLE_SIGN) {
                executor.submit(() -> performSign(kmsClient));
            }
            if (ENABLE_VERIFY) {
                executor.submit(() -> performVerify(kmsClient));
            }

            while (System.nanoTime() - cycleStart < submitDelayNanos) {
                Thread.yield();
            }
        }

        logger.info("Test completed. Shutting down...");
        executor.shutdown();
        scheduler.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.MINUTES);
            scheduler.awaitTermination(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            logger.error("Shutdown interrupted", e);
        }

        printFinalSummary(startTime);
        kmsClient.close();
    }

    private static void performDescribeKey(KmsClient kmsClient) {
        try {
            operationStartTime.set(System.nanoTime());
            
            DescribeKeyRequest request = DescribeKeyRequest.builder()
                .keyId(KEY_ID)
                .build();

            kmsClient.describeKey(request);
            
            recordLatency("DescribeKey", operationStartTime.get());
        } catch (Exception e) {
            logger.error("DescribeKey error: {}", e.getMessage());
        } finally {
            operationStartTime.remove();
        }
    }

    private static void performSign(KmsClient kmsClient) {
        try {
            operationStartTime.set(System.nanoTime());
            
            SignRequest request = SignRequest.builder()
                .keyId(KEY_ID)
                .message(SdkBytes.fromString("Test Data", StandardCharsets.UTF_8))
                .messageType(MessageType.RAW)
                .signingAlgorithm(SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256)
                .build();

            kmsClient.sign(request);
            
            recordLatency("Sign", operationStartTime.get());
        } catch (Exception e) {
            logger.error("Sign error: {}", e.getMessage());
        } finally {
            operationStartTime.remove();
        }
    }

    private static void performVerify(KmsClient kmsClient) {
        try {
            SignRequest signRequest = SignRequest.builder()
                .keyId(KEY_ID)
                .message(SdkBytes.fromString("Test Data", StandardCharsets.UTF_8))
                .messageType(MessageType.RAW)
                .signingAlgorithm(SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256)
                .build();
            SignResponse signResponse = kmsClient.sign(signRequest);

            operationStartTime.set(System.nanoTime());
            
            VerifyRequest request = VerifyRequest.builder()
                .keyId(KEY_ID)
                .message(SdkBytes.fromString("Test Data", StandardCharsets.UTF_8))
                .messageType(MessageType.RAW)
                .signature(signResponse.signature())
                .signingAlgorithm(SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256)
                .build();

            kmsClient.verify(request);
            
            recordLatency("Verify", operationStartTime.get());
        } catch (Exception e) {
            logger.error("Verify error: {}", e.getMessage());
        } finally {
            operationStartTime.remove();
        }
    }

    private static void recordLatency(String operation, long startTime) {
        double totalDurationMs = (System.nanoTime() - startTime) / 1_000_000.0;
        latenciesByOperation.get(operation).add(totalDurationMs);
        
        if (totalDurationMs > SLOW_CALL_THRESHOLD) {
            slowCallsByOperation.get(operation).incrementAndGet();
            String requestId = currentRequestId.get();
            logger.warn("Slow {} call detected:", operation);
            logger.warn("  Total Duration: {} ms", String.format("%.2f", totalDurationMs));
            logger.warn("  Request ID: {}", requestId != null ? requestId : "Unknown");
        }
        totalCalls.incrementAndGet();
    }

    private static void printSummary(Instant startTime) {
        Duration runTime = Duration.between(startTime, Instant.now());
        
        logger.info("\n=== Test Summary ===");
        logger.info("Runtime: {}:{}:{}", 
            runTime.toHours(), runTime.toMinutesPart(), runTime.toSecondsPart());
        logger.info("Total Calls: {}", totalCalls.get());
        logger.info("Average TPS: {}", 
            totalCalls.get() / (double) runTime.getSeconds());

        latenciesByOperation.forEach((operation, latencies) -> {
            if (!latencies.isEmpty()) {
                synchronized(latencies) {
                    List<Double> sortedLatencies = new ArrayList<>(latencies);
                    Collections.sort(sortedLatencies);
                    
                    double avgLatency = sortedLatencies.stream()
                        .mapToDouble(Double::doubleValue)
                        .average()
                        .orElse(0.0);
                    
                    logger.info("\n{} Statistics:", operation);
                    logger.info("  Calls: {}", latencies.size());
                    logger.info("  Average Latency: {} ms", String.format("%.2f", avgLatency));
                    logger.info("  P50 Latency: {} ms", 
                        String.format("%.2f", calculatePercentile(sortedLatencies, 50)));
                    logger.info("  P90 Latency: {} ms", 
                        String.format("%.2f", calculatePercentile(sortedLatencies, 90)));
                    logger.info("  P99 Latency: {} ms", 
                        String.format("%.2f", calculatePercentile(sortedLatencies, 99)));
                    logger.info("  Slow Calls (>{}ms): {} ({:.2f}%)",
                        SLOW_CALL_THRESHOLD,
                        slowCallsByOperation.get(operation).get(),
                        (slowCallsByOperation.get(operation).get() * 100.0) / latencies.size());
                }
            }
        });
        logger.info("=================\n");
    }

    private static void printFinalSummary(Instant startTime) {
        printSummary(startTime);
        logger.info("High Latency Request IDs:");
        requestIdMap.forEach((key, value) -> 
            logger.info("  {}: {}", key, value));
    }

    private static double calculatePercentile(List<Double> latencies, int percentile) {
        if (latencies.isEmpty()) return 0.0;
        int index = (int) Math.ceil(percentile / 100.0 * latencies.size()) - 1;
        return latencies.get(Math.max(0, Math.min(index, latencies.size() - 1)));
    }
}
