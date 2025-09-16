#!/bin/bash

# Configuration
LATENCY_THRESHOLD=100  # milliseconds
TOP_COUNT=5

# Colors for output
BLUE='\033[0;34m'
GREEN='\033[0;32m'
NC='\033[0m' # No Color

echo "Analyzing KMS high latency operations (>${LATENCY_THRESHOLD}ms)..."

# Function to extract and calculate percentages
analyze_latency() {
    local log_file="$1"
    
    awk -v threshold="$LATENCY_THRESHOLD" '
    function extract_value(line, pattern) {
        match(line, pattern"=[[:space:]]*\\[([0-9.]+)\\]", arr)
        return (arr[1] != "") ? arr[1] : 0
    }
    
    function extract_credentials_time(line) {
        match(line, "CredentialsRequestTime=\\[([0-9., ]+)\\]", arr)
        if (arr[1] != "") {
            split(arr[1], times, ",")
            sum = 0
            for (i in times) sum += times[i]
            return sum
        }
        return 0
    }
    
    /Sign:/ {
        # Extract timestamp
        match($0, /([0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3})/, ts)
        timestamp = ts[1]
        
        # Extract total latency
        match($0, /Sign: ([0-9.]+)ms/, lat)
        total_latency = lat[1]
        
        # Extract Request ID
        match($0, /Request ID: ([a-f0-9-]+)/, req)
        request_id = req[1]
        
        if (total_latency > threshold) {
            # SDK Processing Time components
            request_signing = extract_value($0, "RequestSigningTime")
            response_processing = extract_value($0, "ResponseProcessingTime")
            credentials_time = extract_credentials_time($0)
            sdk_time = request_signing + response_processing + credentials_time
            
            # Network Time components
            send_time = extract_value($0, "HttpClientSendRequestTime")
            receive_time = extract_value($0, "HttpClientReceiveResponseTime")
            network_time = send_time + receive_time
            
            # Server Processing Time
            http_request_time = extract_value($0, "HttpRequestTime")
            server_time = http_request_time - network_time
            
            # Calculate percentages
            sdk_pct = (sdk_time / total_latency) * 100
            network_pct = (network_time / total_latency) * 100
            server_pct = (server_time / total_latency) * 100
            
            printf "%f|%s|%s|%.2f|%.2f|%.2f|%.2f|%.2f|%.2f\n", 
                   total_latency, timestamp, request_id,
                   sdk_time, sdk_pct,
                   network_time, network_pct,
                   server_time, server_pct
        }
    }
    ' "$log_file" | sort -rn | head -n "$TOP_COUNT"
}

# Function to print formatted output
print_analysis() {
    echo -e "\n${BLUE}Top $TOP_COUNT High Latency Operations${NC}"
    echo "--------------------------------------------------------------------------------"
    
    while IFS="|" read -r total_lat timestamp req_id sdk_time sdk_pct net_time net_pct srv_time srv_pct; do
        echo -e "\n${GREEN}Total Latency: ${total_lat}ms${NC}"
        echo "Timestamp: $timestamp"
        echo "Request ID: $req_id"
        echo -e "\nBreakdown:"
        printf "%-20s %10s %10s\n" "Component" "Time (ms)" "Percentage"
        echo "------------------------------------------------"
        printf "%-20s %10.2f %9.1f%%\n" "SDK Processing" "$sdk_time" "$sdk_pct"
        printf "%-20s %10.2f %9.1f%%\n" "Network" "$net_time" "$net_pct"
        printf "%-20s %10.2f %9.1f%%\n" "Server Processing" "$srv_time" "$srv_pct"
        echo "------------------------------------------------"
    done
}

# Main execution
if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <log_file>"
    exit 1
fi

LOG_FILE="$1"

if [ ! -f "$LOG_FILE" ]; then
    echo "Error: Log file '$LOG_FILE' not found"
    exit 1
fi

analyze_latency "$LOG_FILE" | print_analysis

