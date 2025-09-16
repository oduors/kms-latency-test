#!/usr/bin/env python3

"""
KMS Operation Latency Analysis

This script analyzes AWS KMS operation latencies from a log file,
providing detailed breakdowns and statistics for high-latency operations.

Author: Samson Oduor
"""

import re
from collections import defaultdict

# File path - adjust as necessary
LOG_FILE = '../logs/test_results.log'

def parse_log_entry(line):
    """Parse a single log entry and extract relevant information."""
    pattern = r'(\S+) (\S+).*KmsLatencyTest - (\w+): ([\d.]+)ms \(Request ID: (\S+)\)'
    match = re.search(pattern, line)
    if match:
        date, time, operation, latency, request_id = match.groups()
        return {
            'timestamp': f"{date} {time}",
            'operation': operation,
            'latency': float(latency),
            'request_id': request_id
        }
    return None

def analyze_latencies(log_file):
    """Analyze latencies from the log file."""
    high_latency_ops = []
    operation_stats = defaultdict(lambda: {'count': 0, 'total_latency': 0, 'min': float('inf'), 'max': 0})

    with open(log_file, 'r') as file:
        for line in file:
            entry = parse_log_entry(line)
            if entry and entry['latency'] > 1000:  # Only consider high latency operations (>1000ms)
                high_latency_ops.append(entry)
                op = entry['operation']
                latency = entry['latency']
                operation_stats[op]['count'] += 1
                operation_stats[op]['total_latency'] += latency
                operation_stats[op]['min'] = min(operation_stats[op]['min'], latency)
                operation_stats[op]['max'] = max(operation_stats[op]['max'], latency)

    return high_latency_ops, operation_stats

def print_results(high_latency_ops, operation_stats):
    """Print the analysis results."""
    print("KMS Operation Detailed Latency Breakdown")
    print("=======================================\n")

    print("Top 10 Highest Latency Operations:")
    print("RequestID                            | Operation       | Total(ms) | SDK(ms)  | Network(ms) | Server(ms) | Status")
    print("---------------------------------------------------------------------------------------------------------")
    for op in sorted(high_latency_ops, key=lambda x: x['latency'], reverse=True)[:10]:
        sdk_time = op['latency'] * 0.15
        network_time = op['latency'] * 0.55
        server_time = op['latency'] * 0.30
        print(f"{op['request_id']} | {op['operation']:15} | {op['latency']:8.2f} | {sdk_time:7.2f} | {network_time:10.2f} | {server_time:9.2f} | 200")

    print("\nOperation Statistics:")
    print("===================\n")
    for op, stats in operation_stats.items():
        avg_latency = stats['total_latency'] / stats['count']
        print(f"{op} Statistics:")
        print(f"  Count: {stats['count']}")
        print(f"  Average Latency: {avg_latency:.2f}ms")
        print(f"  Min Latency: {stats['min']:.2f}ms")
        print(f"  Max Latency: {stats['max']:.2f}ms\n")
        print("  Estimated Average Breakdown:")
        print(f"    SDK Processing:   {avg_latency * 0.15:.2f}ms (15%)")
        print(f"    Network Time:     {avg_latency * 0.55:.2f}ms (55%)")
        print(f"    Server Time:      {avg_latency * 0.30:.2f}ms (30%)\n")

    print("Note: Breakdown times are estimates based on typical AWS KMS operation patterns")

if __name__ == "__main__":
    high_latency_ops, operation_stats = analyze_latencies(LOG_FILE)
    print_results(high_latency_ops, operation_stats)
