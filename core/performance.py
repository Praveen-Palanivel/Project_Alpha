"""LocalStream Core - Performance Tuning Module (Task A6).

This module provides configuration and utilities for optimizing
QUIC file transfer performance on local networks.

Key optimizations:
1. QUIC stream tuning (optimal parallel stream count)
2. Chunk size optimization (4MB, 8MB, 16MB)
3. 0-RTT optimization
4. Congestion control tuning for LAN
5. Buffer size optimization
"""

import asyncio
import time
from dataclasses import dataclass, field
from typing import Optional, Callable, List, Tuple
from pathlib import Path
import logging

from .types import Constants, TransferProgress


logger = logging.getLogger(__name__)


@dataclass
class PerformanceConfig:
    """
    Configuration for optimal transfer performance.
    
    These settings are tuned for local network (LAN) transfers
    where bandwidth is high and latency is low.
    """
    
    # Chunk size in bytes (default 4MB, can go up to 16MB for very fast networks)
    chunk_size: int = 4 * 1024 * 1024
    
    # Number of parallel QUIC streams for data transfer
    parallel_streams: int = 4
    
    # Maximum parallel streams allowed
    max_parallel_streams: int = 8
    
    # Send buffer size (bytes) - larger = more throughput
    send_buffer_size: int = 16 * 1024 * 1024  # 16 MB
    
    # Receive buffer size (bytes)
    receive_buffer_size: int = 16 * 1024 * 1024  # 16 MB
    
    # File read-ahead buffer size
    read_ahead_size: int = 32 * 1024 * 1024  # 32 MB
    
    # Acknowledgment interval (number of chunks)
    ack_interval: int = 8
    
    # Initial estimated RTT for LAN (milliseconds)
    initial_rtt_ms: int = 5
    
    # Enable 0-RTT for faster connection establishment
    enable_0rtt: bool = True
    
    # Congestion control algorithm preference
    # Options: "bbr", "cubic", "reno" (depends on QUIC implementation)
    congestion_control: str = "bbr"
    
    # Disable slow start for LAN (already know we have bandwidth)
    disable_slow_start: bool = True
    
    # Progress update interval (seconds)
    progress_interval: float = 0.1
    
    @classmethod
    def for_gigabit_lan(cls) -> "PerformanceConfig":
        """Optimized settings for gigabit LAN (1 Gbps)."""
        return cls(
            chunk_size=4 * 1024 * 1024,  # 4 MB
            parallel_streams=4,
            send_buffer_size=16 * 1024 * 1024,
            receive_buffer_size=16 * 1024 * 1024,
            initial_rtt_ms=5,
        )
    
    @classmethod
    def for_fast_lan(cls) -> "PerformanceConfig":
        """Optimized settings for 2.5/5/10 Gbps LAN."""
        return cls(
            chunk_size=8 * 1024 * 1024,  # 8 MB
            parallel_streams=6,
            send_buffer_size=32 * 1024 * 1024,
            receive_buffer_size=32 * 1024 * 1024,
            initial_rtt_ms=2,
        )
    
    @classmethod
    def for_wifi(cls) -> "PerformanceConfig":
        """Optimized settings for typical Wi-Fi (variable speed)."""
        return cls(
            chunk_size=2 * 1024 * 1024,  # 2 MB (smaller for variable connection)
            parallel_streams=4,
            send_buffer_size=8 * 1024 * 1024,
            receive_buffer_size=8 * 1024 * 1024,
            initial_rtt_ms=10,
            ack_interval=4,  # More frequent ACKs for reliability
        )
    
    @classmethod
    def for_mobile(cls) -> "PerformanceConfig":
        """Conservative settings for mobile devices."""
        return cls(
            chunk_size=1 * 1024 * 1024,  # 1 MB
            parallel_streams=2,
            send_buffer_size=4 * 1024 * 1024,
            receive_buffer_size=4 * 1024 * 1024,
            initial_rtt_ms=20,
            ack_interval=4,
        )


@dataclass
class TransferStats:
    """Statistics from a transfer for performance analysis."""
    
    transfer_id: str
    file_size: int
    chunk_size: int
    parallel_streams: int
    
    start_time: float = 0.0
    end_time: float = 0.0
    
    bytes_transferred: int = 0
    chunks_transferred: int = 0
    chunks_retried: int = 0
    
    # Per-chunk timing data for analysis
    chunk_times: List[float] = field(default_factory=list)
    
    # Speed samples (bytes per second)
    speed_samples: List[float] = field(default_factory=list)
    
    @property
    def elapsed_seconds(self) -> float:
        """Total transfer time in seconds."""
        if self.end_time == 0:
            return time.time() - self.start_time
        return self.end_time - self.start_time
    
    @property
    def average_speed_mbps(self) -> float:
        """Average transfer speed in MB/s."""
        if self.elapsed_seconds == 0:
            return 0.0
        return (self.bytes_transferred / 1024 / 1024) / self.elapsed_seconds
    
    @property
    def peak_speed_mbps(self) -> float:
        """Peak transfer speed in MB/s."""
        if not self.speed_samples:
            return 0.0
        return max(self.speed_samples) / 1024 / 1024
    
    @property
    def retry_rate(self) -> float:
        """Percentage of chunks that needed retry."""
        if self.chunks_transferred == 0:
            return 0.0
        return (self.chunks_retried / self.chunks_transferred) * 100
    
    def to_dict(self) -> dict:
        """Convert to dictionary for logging/analysis."""
        return {
            "transfer_id": self.transfer_id,
            "file_size_mb": self.file_size / 1024 / 1024,
            "chunk_size_mb": self.chunk_size / 1024 / 1024,
            "parallel_streams": self.parallel_streams,
            "elapsed_seconds": self.elapsed_seconds,
            "average_speed_mbps": self.average_speed_mbps,
            "peak_speed_mbps": self.peak_speed_mbps,
            "retry_rate_percent": self.retry_rate,
        }


class SpeedMonitor:
    """
    Real-time speed monitoring for transfers.
    
    Calculates instantaneous and average speed, with smoothing
    to avoid jitter in the display.
    """
    
    def __init__(self, smoothing_window: int = 5):
        self.smoothing_window = smoothing_window
        self._samples: List[Tuple[float, int]] = []  # (timestamp, bytes)
        self._total_bytes = 0
        self._start_time = time.time()
    
    def add_bytes(self, byte_count: int) -> None:
        """Record bytes transferred."""
        now = time.time()
        self._samples.append((now, byte_count))
        self._total_bytes += byte_count
        
        # Keep only recent samples
        cutoff = now - self.smoothing_window
        self._samples = [(t, b) for t, b in self._samples if t > cutoff]
    
    def get_instantaneous_speed(self) -> float:
        """Get smoothed instantaneous speed in bytes/second."""
        if len(self._samples) < 2:
            return 0.0
        
        oldest = self._samples[0]
        newest = self._samples[-1]
        
        time_diff = newest[0] - oldest[0]
        if time_diff == 0:
            return 0.0
        
        bytes_in_window = sum(b for _, b in self._samples)
        return bytes_in_window / time_diff
    
    def get_average_speed(self) -> float:
        """Get overall average speed in bytes/second."""
        elapsed = time.time() - self._start_time
        if elapsed == 0:
            return 0.0
        return self._total_bytes / elapsed
    
    def get_eta_seconds(self, remaining_bytes: int) -> float:
        """Estimate time remaining based on current speed."""
        speed = self.get_instantaneous_speed()
        if speed == 0:
            return float('inf')
        return remaining_bytes / speed
    
    def format_speed(self, speed_bps: float) -> str:
        """Format speed as human-readable string."""
        if speed_bps >= 1024 * 1024 * 1024:
            return f"{speed_bps / 1024 / 1024 / 1024:.1f} GB/s"
        elif speed_bps >= 1024 * 1024:
            return f"{speed_bps / 1024 / 1024:.1f} MB/s"
        elif speed_bps >= 1024:
            return f"{speed_bps / 1024:.1f} KB/s"
        else:
            return f"{speed_bps:.0f} B/s"
    
    def format_eta(self, seconds: float) -> str:
        """Format ETA as human-readable string."""
        if seconds == float('inf'):
            return "calculating..."
        elif seconds < 60:
            return f"{seconds:.0f}s"
        elif seconds < 3600:
            return f"{seconds // 60:.0f}m {seconds % 60:.0f}s"
        else:
            hours = seconds // 3600
            minutes = (seconds % 3600) // 60
            return f"{hours:.0f}h {minutes:.0f}m"


def auto_tune_config(
    file_size: int,
    estimated_bandwidth_mbps: float = 100,
    is_wifi: bool = False
) -> PerformanceConfig:
    """
    Automatically determine optimal performance config.
    
    Args:
        file_size: Size of file to transfer in bytes
        estimated_bandwidth_mbps: Estimated network bandwidth
        is_wifi: Whether connection is over Wi-Fi (more variable)
        
    Returns:
        Optimized PerformanceConfig
    """
    # Start with baseline
    if is_wifi:
        config = PerformanceConfig.for_wifi()
    elif estimated_bandwidth_mbps >= 2500:
        config = PerformanceConfig.for_fast_lan()
    else:
        config = PerformanceConfig.for_gigabit_lan()
    
    # Adjust chunk size based on file size
    if file_size < 10 * 1024 * 1024:  # < 10 MB
        config.chunk_size = 512 * 1024  # 512 KB for small files
        config.parallel_streams = 2
    elif file_size < 100 * 1024 * 1024:  # < 100 MB
        config.chunk_size = 1 * 1024 * 1024  # 1 MB
        config.parallel_streams = 3
    elif file_size >= 1024 * 1024 * 1024:  # >= 1 GB
        # For large files, use larger chunks
        if not is_wifi:
            config.chunk_size = 8 * 1024 * 1024  # 8 MB
            config.parallel_streams = 6
    
    return config


def calculate_optimal_chunk_size(
    bandwidth_mbps: float,
    rtt_ms: float = 5
) -> int:
    """
    Calculate optimal chunk size based on bandwidth-delay product.
    
    Args:
        bandwidth_mbps: Available bandwidth in Mbps
        rtt_ms: Round-trip time in milliseconds
        
    Returns:
        Optimal chunk size in bytes
    """
    # Bandwidth-delay product: how much data can be "in flight"
    bdp_bytes = (bandwidth_mbps * 1024 * 1024 / 8) * (rtt_ms / 1000)
    
    # Chunk size should be at least 2x BDP for efficiency
    optimal = int(bdp_bytes * 2)
    
    # Clamp to reasonable range
    optimal = max(Constants.MIN_CHUNK_SIZE, min(optimal, Constants.MAX_CHUNK_SIZE))
    
    # Round to nearest power of 2 MB for simplicity
    mb = optimal / (1024 * 1024)
    if mb <= 1:
        return 1 * 1024 * 1024
    elif mb <= 2:
        return 2 * 1024 * 1024
    elif mb <= 4:
        return 4 * 1024 * 1024
    elif mb <= 8:
        return 8 * 1024 * 1024
    else:
        return 16 * 1024 * 1024


async def benchmark_connection(
    host: str,
    port: int,
    test_size_mb: int = 10
) -> dict:
    """
    Benchmark connection to a receiver.
    
    Sends test data to measure actual throughput and latency.
    
    Args:
        host: Receiver host
        port: Receiver port  
        test_size_mb: Size of test data in MB
        
    Returns:
        Dictionary with benchmark results
    """
    # This would require an actual connection - placeholder for now
    # In real implementation, would send test chunks and measure timing
    
    results = {
        "host": host,
        "port": port,
        "test_size_mb": test_size_mb,
        "throughput_mbps": 0.0,
        "latency_ms": 0.0,
        "recommended_config": None,
    }
    
    logger.info(f"Benchmarking connection to {host}:{port}...")
    
    # TODO: Implement actual benchmark when receiver is available
    # For now, return defaults
    results["throughput_mbps"] = 100.0  # Assume gigabit
    results["latency_ms"] = 5.0  # Typical LAN
    results["recommended_config"] = PerformanceConfig.for_gigabit_lan()
    
    return results


def create_performance_report(stats: TransferStats) -> str:
    """
    Create a human-readable performance report.
    
    Args:
        stats: Transfer statistics
        
    Returns:
        Formatted report string
    """
    report = []
    report.append("=" * 50)
    report.append("TRANSFER PERFORMANCE REPORT")
    report.append("=" * 50)
    report.append(f"Transfer ID: {stats.transfer_id}")
    report.append(f"File Size: {stats.file_size / 1024 / 1024:.1f} MB")
    report.append(f"Chunk Size: {stats.chunk_size / 1024 / 1024:.1f} MB")
    report.append(f"Parallel Streams: {stats.parallel_streams}")
    report.append("-" * 50)
    report.append(f"Duration: {stats.elapsed_seconds:.2f} seconds")
    report.append(f"Average Speed: {stats.average_speed_mbps:.1f} MB/s")
    report.append(f"Peak Speed: {stats.peak_speed_mbps:.1f} MB/s")
    report.append(f"Chunks Transferred: {stats.chunks_transferred}")
    report.append(f"Retry Rate: {stats.retry_rate:.1f}%")
    report.append("=" * 50)
    
    # Performance assessment
    if stats.average_speed_mbps >= 100:
        report.append("✅ EXCELLENT - Near gigabit saturation")
    elif stats.average_speed_mbps >= 50:
        report.append("⚡ GOOD - High speed transfer")
    elif stats.average_speed_mbps >= 20:
        report.append("⚠️ MODERATE - Room for improvement")
    else:
        report.append("❌ SLOW - Check network conditions")
    
    return "\n".join(report)


# QUIC-specific tuning parameters for aioquic
def get_quic_config_kwargs(config: PerformanceConfig) -> dict:
    """
    Get aioquic QuicConfiguration keyword arguments for performance.
    
    Args:
        config: Performance configuration
        
    Returns:
        Dictionary of kwargs for QuicConfiguration
    """
    return {
        # Maximum data that can be in flight
        "max_data": config.send_buffer_size * 2,
        
        # Maximum data per stream
        "max_stream_data_bidi_local": config.chunk_size * 2,
        "max_stream_data_bidi_remote": config.chunk_size * 2,
        "max_stream_data_uni": config.chunk_size * 2,
        
        # Number of concurrent streams
        "max_streams_bidi": config.max_parallel_streams * 2,
        "max_streams_uni": config.max_parallel_streams * 4,
        
        # Idle timeout
        "idle_timeout": 60.0,  # 60 seconds
    }
