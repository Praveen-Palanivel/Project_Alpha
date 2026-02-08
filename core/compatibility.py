"""LocalStream Core - Cross-Platform Compatibility Layer (Task A5).

This module ensures consistent behavior between Android (Kotlin) and Windows (Flutter)
implementations of the LocalStream protocol.

Key concerns:
1. Endianness consistency (big-endian for all network data)
2. File path normalization (handle Windows vs Unix paths)
3. Platform detection and capability negotiation
4. Encoding consistency (UTF-8 everywhere)
"""

import os
import sys
import struct
import platform
from pathlib import Path, PurePosixPath, PureWindowsPath
from typing import Union, Optional
from enum import Enum


class Platform(Enum):
    """Supported platforms."""
    ANDROID = "android"
    WINDOWS = "windows"
    LINUX = "linux"
    UNKNOWN = "unknown"


def detect_platform() -> Platform:
    """
    Detect the current platform.
    
    Returns:
        Platform enum value
    """
    system = platform.system().lower()
    
    # Check for Android (runs on Linux kernel but has specific markers)
    if system == "linux":
        # Android has specific environment variables or paths
        if os.path.exists("/system/build.prop") or "ANDROID" in os.environ:
            return Platform.ANDROID
        return Platform.LINUX
    elif system == "windows":
        return Platform.WINDOWS
    else:
        return Platform.UNKNOWN


def get_platform_name() -> str:
    """Get platform name string for protocol messages."""
    return detect_platform().value


# =============================================================================
# Endianness Utilities
# =============================================================================

# Network byte order is big-endian (standard for protocols)
BYTE_ORDER = "big"


def pack_u16(value: int) -> bytes:
    """Pack unsigned 16-bit integer to big-endian bytes."""
    return struct.pack(">H", value)


def unpack_u16(data: bytes) -> int:
    """Unpack big-endian bytes to unsigned 16-bit integer."""
    return struct.unpack(">H", data)[0]


def pack_u32(value: int) -> bytes:
    """Pack unsigned 32-bit integer to big-endian bytes."""
    return struct.pack(">L", value)


def unpack_u32(data: bytes) -> int:
    """Unpack big-endian bytes to unsigned 32-bit integer."""
    return struct.unpack(">L", data)[0]


def pack_u64(value: int) -> bytes:
    """Pack unsigned 64-bit integer to big-endian bytes."""
    return struct.pack(">Q", value)


def unpack_u64(data: bytes) -> int:
    """Unpack big-endian bytes to unsigned 64-bit integer."""
    return struct.unpack(">Q", data)[0]


def pack_i32(value: int) -> bytes:
    """Pack signed 32-bit integer to big-endian bytes."""
    return struct.pack(">l", value)


def unpack_i32(data: bytes) -> int:
    """Unpack big-endian bytes to signed 32-bit integer."""
    return struct.unpack(">l", data)[0]


# =============================================================================
# Path Normalization
# =============================================================================

def normalize_path_for_protocol(path: Union[str, Path]) -> str:
    """
    Normalize a file path for transmission over the protocol.
    
    Uses forward slashes (/) as the universal separator.
    Only transmits the filename, not full paths (for security).
    
    Args:
        path: Local file path
        
    Returns:
        Normalized filename suitable for protocol transmission
    """
    # Convert to Path object
    if isinstance(path, str):
        path = Path(path)
    
    # Return just the filename (no directory traversal attacks)
    return path.name


def normalize_path_for_local(filename: str, save_directory: Path) -> Path:
    """
    Convert a protocol filename to a safe local path.
    
    Sanitizes the filename to prevent directory traversal and
    invalid characters.
    
    Args:
        filename: Filename received from protocol
        save_directory: Local directory to save to
        
    Returns:
        Safe local path
    """
    # Remove any path separators (prevent directory traversal)
    safe_name = filename.replace("/", "_").replace("\\", "_")
    
    # Remove leading dots (prevent hidden files on Unix)
    while safe_name.startswith("."):
        safe_name = safe_name[1:]
    
    # If empty after sanitization, use a default name
    if not safe_name:
        safe_name = "unnamed_file"
    
    # Replace invalid Windows characters
    invalid_chars = '<>:"|?*'
    for char in invalid_chars:
        safe_name = safe_name.replace(char, "_")
    
    # Limit length (Windows has 255 char limit)
    max_name_length = 200  # Leave room for conflict suffixes
    if len(safe_name) > max_name_length:
        # Preserve extension
        name_path = Path(safe_name)
        stem = name_path.stem[:max_name_length - len(name_path.suffix) - 1]
        safe_name = stem + name_path.suffix
    
    return save_directory / safe_name


def resolve_filename_conflict(path: Path) -> Path:
    """
    Handle duplicate filenames by appending a number.
    
    Example: file.txt -> file (1).txt -> file (2).txt
    
    Args:
        path: Proposed file path
        
    Returns:
        Unique file path that doesn't exist
    """
    if not path.exists():
        return path
    
    counter = 1
    stem = path.stem
    suffix = path.suffix
    parent = path.parent
    
    while True:
        new_name = f"{stem} ({counter}){suffix}"
        new_path = parent / new_name
        if not new_path.exists():
            return new_path
        counter += 1
        
        # Safety limit
        if counter > 10000:
            raise RuntimeError(f"Too many duplicate files: {path}")


# =============================================================================
# String Encoding
# =============================================================================

ENCODING = "utf-8"


def encode_string(s: str) -> bytes:
    """Encode string to UTF-8 bytes."""
    return s.encode(ENCODING)


def decode_string(data: bytes) -> str:
    """Decode UTF-8 bytes to string."""
    return data.decode(ENCODING)


def safe_decode_string(data: bytes, fallback: str = "") -> str:
    """
    Safely decode bytes to string with fallback.
    
    Args:
        data: Bytes to decode
        fallback: Value to return if decoding fails
        
    Returns:
        Decoded string or fallback
    """
    try:
        return data.decode(ENCODING)
    except UnicodeDecodeError:
        return fallback


# =============================================================================
# Platform-Specific Defaults
# =============================================================================

def get_default_save_directory() -> Path:
    """
    Get the default directory for saving received files.
    
    Returns platform-appropriate default:
    - Windows: Downloads folder
    - Android: /storage/emulated/0/Download
    - Linux: ~/Downloads
    """
    plat = detect_platform()
    
    if plat == Platform.WINDOWS:
        # Use known folder API via environment or fallback
        downloads = os.environ.get("USERPROFILE", "")
        if downloads:
            return Path(downloads) / "Downloads"
        return Path.home() / "Downloads"
    
    elif plat == Platform.ANDROID:
        # Android's public downloads directory
        return Path("/storage/emulated/0/Download")
    
    else:  # Linux and others
        return Path.home() / "Downloads"


def get_temp_directory() -> Path:
    """
    Get a temporary directory for partial transfers.
    
    Returns platform-appropriate temp location.
    """
    plat = detect_platform()
    
    if plat == Platform.WINDOWS:
        temp = os.environ.get("TEMP", os.environ.get("TMP", ""))
        if temp:
            return Path(temp) / "localstream"
        return Path.home() / ".localstream" / "temp"
    
    elif plat == Platform.ANDROID:
        # Android app's cache directory would be set by the app
        return Path("/data/local/tmp/localstream")
    
    else:  # Linux
        return Path("/tmp/localstream")


def ensure_directory(path: Path) -> Path:
    """
    Ensure a directory exists, creating it if necessary.
    
    Args:
        path: Directory path
        
    Returns:
        The path (for chaining)
    """
    path.mkdir(parents=True, exist_ok=True)
    return path


# =============================================================================
# Capability Detection
# =============================================================================

def get_capabilities() -> list:
    """
    Get list of supported capabilities for this platform.
    
    Returns:
        List of capability strings for protocol negotiation
    """
    capabilities = ["quic", "resume"]  # Base capabilities
    
    # TCP fallback always available
    capabilities.append("tcp")
    
    # Check for specific features
    try:
        import aioquic
        capabilities.append("quic-aioquic")
    except ImportError:
        pass
    
    return capabilities


def check_quic_available() -> bool:
    """Check if QUIC is available on this platform."""
    try:
        import aioquic
        return True
    except ImportError:
        return False


# =============================================================================
# Validation Utilities
# =============================================================================

def validate_port(port: int) -> bool:
    """Validate that a port number is valid."""
    return 1 <= port <= 65535


def validate_chunk_size(size: int) -> bool:
    """Validate that chunk size is within acceptable range."""
    from .types import Constants
    return Constants.MIN_CHUNK_SIZE <= size <= Constants.MAX_CHUNK_SIZE


def validate_file_size(size: int) -> bool:
    """Validate that file size is positive and reasonable."""
    # Max 1 TB for now
    MAX_FILE_SIZE = 1024 * 1024 * 1024 * 1024
    return 0 < size <= MAX_FILE_SIZE


def sanitize_device_name(name: str, max_length: int = 32) -> str:
    """
    Sanitize a device name for safe display and transmission.
    
    Args:
        name: Raw device name
        max_length: Maximum allowed length
        
    Returns:
        Sanitized device name
    """
    # Remove control characters
    safe_name = "".join(c for c in name if c.isprintable())
    
    # Trim whitespace
    safe_name = safe_name.strip()
    
    # Limit length
    if len(safe_name) > max_length:
        safe_name = safe_name[:max_length - 3] + "..."
    
    # Default if empty
    if not safe_name:
        safe_name = "Unknown Device"
    
    return safe_name
