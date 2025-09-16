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

public class KmsLatencyTracker {
    private static final Logger logger = LoggerFactory.getLogger(KmsLatencyTracker.class);
    private static final String KEY_ID = "arn:aws:kms:eu-central-1:368825226956:key/e882b555-32d8-435b-b03d-610fb77f3d5c";
    private static final Region AWS_REGION = Region.EU_CENTRAL_1;
    private static final Duration TEST_DURATION = Duration.ofMinutes(10);
    private static final int TARGET_TPS = 100;

    static class LatencyInterceptor implements ExecutionInterceptor {
        private final ThreadLocal<Map<String, Long>> timings = ThreadLocal.withInitial(HashMap::new);

        @Override
        public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes executionAttributes) {
            timings.get().put("sdkStart", System.nanoTime());
        }

        @Override
        public void beforeMarshalling(Context.BeforeMarshalling context, ExecutionAttributes executionAttributes) {
            timings.get().put("marshallingStart", System.nanoTime());
        }

        @Override
        public void afterMarshalling(Context.AfterMarshalling context, ExecutionAttributes executionAttributes) {
            timings.get().put("marshallingEnd", System.nanoTime());
        }

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes executionAttributes) {
            timings.get().put("networkStart", System.nanoTime());
        }

        @Override
        public void afterTransmission(Context.AfterTransmission context, ExecutionAttributes executionAttributes) {
            timings.get().put("networkEnd", System.nanoTime());
        }

        @Override
        public void beforeUnmarshalling(Context.BeforeUnmarshalling context, ExecutionAttributes executionAttributes) {
            timings.get().put("serverEnd", System.nanoTime());
        }

        @Override
        public void afterExecution(Context.AfterExecution context, ExecutionAttributes executionAttributes) {
            Map<String, Long> timing = timings.get();
            long endTime = System.nanoTime();

            String awsRequestId = context.httpResponse().firstMatchingHeader("x-amzn-RequestId").orElse("Unknown");
            String operation = context.request().getClass().getSimpleName().replace("Request", "");

            // Calculate timings
            double sdkTime = (timing.get("marshallingEnd") - timing.get("sdkStart")) / 1_000_000.0;
            double networkOutTime = (timing.get("networkEnd") - timing.get("networkStart")) / 1_000_000.0;
            double serverTime = (timing.get("serverEnd") - timing.get("networkEnd")) / 1_000_000.0;
            double networkInTime = (endTime - timing.get("serverEnd")) / 1_000_000.0;
            double totalTime = (endTime - timing.get("sdkStart")) / 1_000_000.0;

            logger.info("KMS Operation Metrics:");
            logger.info("Operation: {} - Request ID: {}", operation, awsRequestId);
            logger.info("Total Time: {}ms", String.format("%.2f", totalTime));
            logger.info("SDK Time: {}ms", String.format("%.2f", sdkTime));
            logger.info("Network Out: {}ms", String.format("%.2f", networkOutTime));
            logger.info("Server Time: {}ms", String.format("%.2f", serverTime));
            logger.info("Network In: {}ms", String.format("%.2f", networkInTime));

            if (totalTime > 100) {
                logger.warn("High Latency Alert:");
                logger.warn("Operation: {} - Request ID: {}", operation, awsRequestId);
                logger.warn("Total: {}ms - SDK: {}ms ({}%) - Network: {}ms ({}%) - Server: {}ms ({}%)",
                    String.format("%.2f", totalTime),
                    String.format("%.2f", sdkTime),
                    String.format("%.1f", (sdkTime/totalTime)*100),
                    String.format("%.2f", networkOutTime + networkInTime),
                    String.format("%.1f", ((networkOutTime + networkInTime)/totalTime)*100),
                    String.format("%.2f", serverTime),
                    String.format("%.1f", (serverTime/totalTime)*100));
            }

            timings.remove();
        }
    }

    public static void main(String[] args) {
        KmsClient kmsClient = KmsClient.builder()
            .region(AWS_REGION)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .overrideConfiguration(ClientOverrideConfiguration.builder()
                .addExecutionInterceptor(new LatencyInterceptor())
                .build())
            .build();

        // Test operations
        testKmsOperations(kmsClient);
    }

    private static void testKmsOperations(KmsClient kmsClient) {
        try {
            // Test DescribeKey
            DescribeKeyRequest describeRequest = DescribeKeyRequest.builder()
                .keyId(KEY_ID)
                .build();
            kmsClient.describeKey(describeRequest);

            // Test Sign
            SignRequest signRequest = SignRequest.builder()
                .keyId(KEY_ID)
                .message(SdkBytes.fromString("Test Data", StandardCharsets.UTF_8))
                .messageType(MessageType.RAW)
                .signingAlgorithm(SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256)
                .build();
            SignResponse signResponse = kmsClient.sign(signRequest);

            // Test Verify
            VerifyRequest verifyRequest = VerifyRequest.builder()
                .keyId(KEY_ID)
                .message(SdkBytes.fromString("Test Data", StandardCharsets.UTF_8))
                .messageType(MessageType.RAW)
                .signature(signResponse.signature())
                .signingAlgorithm(SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256)
                .build();
            kmsClient.verify(verifyRequest);

        } catch (Exception e) {
            logger.error("Error during KMS operations", e);
        }
    }
}
