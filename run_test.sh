#!/bin/bash

# Create logs directory
mkdir -p logs

# Timestamp for log files
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# Run the test
echo "Starting KMS latency test..."
java -jar target/kms-latency-test-1.0-SNAPSHOT.jar > logs/test_${TIMESTAMP}.log 2>&1

# Extract high latency calls
echo "Analyzing high latency calls..."
awk '/High latency detected/ {print}' logs/test_${TIMESTAMP}.log > logs/high_latency_${TIMESTAMP}.log

echo "Test complete. Results available in:"
echo "  Full log: logs/test_${TIMESTAMP}.log"
echo "  High latency analysis: logs/high_latency_${TIMESTAMP}.log"
