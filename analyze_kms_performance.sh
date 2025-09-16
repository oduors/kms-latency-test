#!/bin/bash

LOGFILE="fresh_test_results.log"
if [ ! -f "$LOGFILE" ]; then
    echo "Log file not found: $LOGFILE"
    exit 1
fi

echo "=== KMS Performance Analysis ==="
echo "Log file: $LOGFILE"
echo "================================"

echo -e "\n1. Peak Performance Metrics"
echo "Operation | Calls | Avg(ms) | P50(ms) | P90(ms) | P99(ms)"
echo "-----------------------------------------------------"
awk '
/Statistics:/ {
    operation = $1
    sub(/:$/, "", operation)
    
    # Get metrics
    getline; calls = $NF
    getline; avg = $(NF-1)
    getline; p50 = $(NF-1)
    getline; p90 = $(NF-1)
    getline; p99 = $(NF-1)
    
    # Store highest values
    if (p99 > max_p99[operation] || !max_p99[operation]) {
        metrics[operation] = sprintf("%-10s | %5d | %7.2f | %7.2f | %7.2f | %7.2f",
            operation, calls, avg, p50, p90, p99)
        max_p99[operation] = p99
    }
}
END {
    for (op in max_p99) {
        print metrics[op]
    }
}' "$LOGFILE" | sort -t'|' -k6 -nr

echo -e "\n2. High Latency Events (>100ms)"
echo "Timestamp | Operation | Latency(ms) | Request ID"
echo "---------------------------------------------"
grep -B1 -A2 "High latency detected" "$LOGFILE" | awk '
/High latency detected/ {
    ts = prev_timestamp
    sub(/.*detected for /, "")
    sub(/ operation:/, "")
    op = $0
    
    getline
    rid = $NF
    getline
    latency = $(NF-1)
    
    if (latency + 0 > 100) {
        printf "%s | %-9s | %10.2f | %s\n", 
            ts, op, latency, rid
    }
}
{
    prev_timestamp = $1 " " $2
}' | sort -t'|' -k3 -nr | head -10

echo -e "\n3. Performance by Time Period"
echo "Time | Operation | Avg(ms) | P99(ms)"
echo "----------------------------------"
awk '
/Statistics:/ {
    ts = $1
    operation = $1
    sub(/:$/, "", operation)
    
    getline; calls = $NF
    getline; avg = $(NF-1)
    getline  # skip p50
    getline  # skip p90
    getline; p99 = $(NF-1)
    
    minute = substr(ts, 1, 16)
    
    if (avg < 1000) {  # Filter out obvious errors
        printf "%s | %-9s | %7.2f | %7.2f\n",
            minute, operation, avg, p99
    }
}' "$LOGFILE" | sort | tail -15

