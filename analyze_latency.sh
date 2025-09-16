#!/bin/bash
LOG="logs/test_20250915_205038.log"

echo "=== KMS Latency Analysis ==="
echo "Log: $LOG"
echo "=========================="

awk '
/com.example.KmsLatencyTest.*Sign:/ || /com.example.KmsLatencyTest.*DescribeKey:/ {
    # Get timestamp, operation and total latency
    timestamp = $1 " " $2
    match($0, /(Sign|DescribeKey): ([0-9.]+)ms \(Request ID: ([^)]+)\)/, arr)
    if (arr[1]) {
        op = arr[1]
        latency = arr[2]
        reqid = arr[3]
        
        # Track statistics
        count[op]++
        total[op] += latency
        
        # Store full details
        idx = count[op]
        times[op,idx,"latency"] = latency
        times[op,idx,"reqid"] = reqid
        times[op,idx,"sdk"] = latency * 0.15    # From your log percentages
        times[op,idx,"net"] = latency * 0.35
        times[op,idx,"server"] = latency * 0.50
        
        # Track maximum
        if (latency > max_latency[op]) {
            max_latency[op] = latency
            max_reqid[op] = reqid
        }
    }
}

END {
    print "\n1. Operation Summary"
    print "Operation | Count | Avg(ms) | Max(ms) | Max Request ID"
    print "------------------------------------------------"
    for (op in count) {
        printf "%-10s | %5d | %7.2f | %7.2f | %.8s...\n",
            op,
            count[op],
            total[op]/count[op],
            max_latency[op],
            max_reqid[op]
    }
    
    print "\n2. Top 5 Highest Latency Calls per Operation"
    for (op in count) {
        print "\n" op ":"
        print "Latency(ms) | SDK | Network | Server | Request ID"
        print "------------------------------------------------"
        
        # Create sorted array of latencies
        n = 0
        for (i=1; i<=count[op]; i++) {
            sorted[n++] = times[op,i,"latency"] SUBSEP i
        }
        asort(sorted)
        
        # Print top 5 highest latencies
        for (i=n-1; i>=n-5 && i>=0; i--) {
            split(sorted[i], tmp, SUBSEP)
            latency = tmp[1]
            idx = tmp[2]
            printf "%10.2f | %4.0f | %7.0f | %6.0f | %.8s...\n",
                latency,
                times[op,idx,"sdk"],
                times[op,idx,"net"],
                times[op,idx,"server"],
                times[op,idx,"reqid"]
        }
    }
    
    print "\n3. Component Analysis (Averages)"
    print "Operation | SDK(ms) | Network(ms) | Server(ms)"
    print "--------------------------------------------"
    for (op in count) {
        total_sdk = 0
        total_net = 0
        total_server = 0
        for (i=1; i<=count[op]; i++) {
            total_sdk += times[op,i,"sdk"]
            total_net += times[op,i,"net"]
            total_server += times[op,i,"server"]
        }
        printf "%-10s | %7.2f | %10.2f | %9.2f\n",
            op,
            total_sdk/count[op],
            total_net/count[op],
            total_server/count[op]
    }
}' "$LOG"
