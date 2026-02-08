"""LocalStream Core - Fast xxHash64 checksum utilities."""

import xxhash
from pathlib import Path
from typing import BinaryIO


def compute_xxhash64(data: bytes) -> int:
    """
    Compute xxHash64 of data.
    
    Args:
        data: Bytes to hash
        
    Returns:
        Hash as unsigned 64-bit integer
    """
    return xxhash.xxh64(data).intdigest()


def compute_xxhash64_hex(data: bytes) -> str:
    """
    Compute xxHash64 of data as hex string.
    
    Args:
        data: Bytes to hash
        
    Returns:
        Hash as hex string (16 characters)
    """
    return xxhash.xxh64(data).hexdigest()


async def compute_file_checksum(path: Path, chunk_size: int = 8 * 1024 * 1024) -> str:
    """
    Compute xxHash64 of entire file asynchronously.
    
    Args:
        path: Path to file
        chunk_size: Read chunk size (default 8MB for speed)
        
    Returns:
        Hash as hex string
    """
    import aiofiles
    
    hasher = xxhash.xxh64()
    
    async with aiofiles.open(path, 'rb') as f:
        while True:
            chunk = await f.read(chunk_size)
            if not chunk:
                break
            hasher.update(chunk)
    
    return hasher.hexdigest()


def compute_file_checksum_sync(path: Path, chunk_size: int = 8 * 1024 * 1024) -> str:
    """
    Compute xxHash64 of entire file synchronously.
    
    Args:
        path: Path to file
        chunk_size: Read chunk size (default 8MB for speed)
        
    Returns:
        Hash as hex string
    """
    hasher = xxhash.xxh64()
    
    with open(path, 'rb') as f:
        while True:
            chunk = f.read(chunk_size)
            if not chunk:
                break
            hasher.update(chunk)
    
    return hasher.hexdigest()


def verify_chunk(data: bytes, expected_checksum: int) -> bool:
    """
    Verify chunk data matches expected checksum.
    
    Args:
        data: Chunk data bytes
        expected_checksum: Expected xxHash64 as integer
        
    Returns:
        True if checksum matches
    """
    actual = compute_xxhash64(data)
    return actual == expected_checksum


class StreamingChecksum:
    """
    Incrementally compute checksum for streaming data.
    
    Usage:
        hasher = StreamingChecksum()
        hasher.update(chunk1)
        hasher.update(chunk2)
        final_hash = hasher.hexdigest()
    """
    
    def __init__(self):
        self._hasher = xxhash.xxh64()
    
    def update(self, data: bytes) -> None:
        """Add data to the hash."""
        self._hasher.update(data)
    
    def intdigest(self) -> int:
        """Get hash as integer."""
        return self._hasher.intdigest()
    
    def hexdigest(self) -> str:
        """Get hash as hex string."""
        return self._hasher.hexdigest()
    
    def copy(self) -> "StreamingChecksum":
        """Create a copy of the current hash state."""
        new = StreamingChecksum()
        new._hasher = self._hasher.copy()
        return new
    
    def reset(self) -> None:
        """Reset the hasher to initial state."""
        self._hasher.reset()
