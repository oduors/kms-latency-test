# KMS Latency Testing Tool

A tool to analyze AWS KMS API performance and latency patterns.

## Features
- Tests multiple KMS operations (DescribeKey, Sign, Verify)
- Provides detailed latency breakdowns
- Tracks request IDs for troubleshooting
- Analyzes performance patterns and trends
- Configurable test parameters

## Prerequisites
- Java 17
- Maven
- AWS credentials configured
- KMS key with appropriate permissions

## Configuration
Update the following parameters in `KmsLatencyTest.java`:

private static final String KEY_ID = "YOUR_KMS_KEY_ARN";
private static final Region AWS_REGION = Region.YOUR_REGION;
private static final Duration TEST_DURATION = Duration.ofMinutes(10);
private static final int TARGET_TPS = 100;
private static final int THREAD_POOL_SIZE = 50;

## Building
mvn clean package

## Running
./run_test.sh

## Analyzing Results
# View transactions with different latency thresholds
./analyze_latency.sh 50   # > 50ms
./analyze_latency.sh 100  # > 100ms
./analyze_latency.sh 1000 # > 1 second

Analysis provides:
- Transaction timestamps and Request IDs
- SDK/Network/Server timing breakdowns
- Statistical summaries by operation
- Time-based patterns
- Error analysis

## Output Example
=== Summary Statistics ===
Operation Type | Total Calls | Avg Time (ms) | P50 | P90 | P99
DescribeKey    | 50000      | 5.28         | 4.15| 4.81| 11.03
Sign          | 50000      | 7.95         | 6.73| 7.53| 25.61
Verify        | 50000      | 5.94         | 5.42| 6.09| 10.65

## Security Note
- Ensure proper IAM permissions for KMS operations
- Never commit AWS credentials or actual KMS key ARNs
- Monitor and adjust rate limits as needed

## Contributing
Please submit issues and pull requests for any improvements.

## License
MIT License
