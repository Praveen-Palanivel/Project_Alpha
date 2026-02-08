"""
LocalStream Core - High-speed local file transfer library.

This module implements the QUIC-based file transfer protocol for
ultra-high-speed local network file transfers.

Usage:
    from core import FileSender, FileReceiver, DeviceInfo
    
    # Sending
    device = DeviceInfo.generate("My Phone", "android")
    sender = FileSender(device)
    result = await sender.send_file(path, host, port)
    
    # Receiving
    receiver = FileReceiver(device, save_dir)
    await receiver.start_server(port=42424)
"""

from .types import (
    MessageType,
    ErrorCode,
    TransferState,
    DeviceInfo,
    FileMetadata,
    ChunkInfo,
    TransferProgress,
    TransferResult,
    Constants,
    ProgressCallback,
    FileReceivedCallback,
    ErrorCallback,
)

from .protocol import (
    encode_message,
    decode_message,
    encode_chunk,
    decode_chunk_header,
    get_chunk_data,
)

from .checksum import (
    compute_xxhash64,
    compute_xxhash64_hex,
    compute_file_checksum,
    verify_chunk,
    StreamingChecksum,
)

from .sender import FileSender
from .receiver import FileReceiver

from .compatibility import (
    Platform,
    detect_platform,
    get_platform_name,
    pack_u16, unpack_u16,
    pack_u32, unpack_u32,
    pack_u64, unpack_u64,
    normalize_path_for_protocol,
    normalize_path_for_local,
    resolve_filename_conflict,
    get_default_save_directory,
    get_capabilities,
    sanitize_device_name,
)

__version__ = "1.0.0"
__all__ = [
    # Types
    "MessageType",
    "ErrorCode", 
    "TransferState",
    "DeviceInfo",
    "FileMetadata",
    "ChunkInfo",
    "TransferProgress",
    "TransferResult",
    "Constants",
    "ProgressCallback",
    "FileReceivedCallback",
    "ErrorCallback",
    # Protocol
    "encode_message",
    "decode_message",
    "encode_chunk",
    "decode_chunk_header",
    "get_chunk_data",
    # Checksum
    "compute_xxhash64",
    "compute_xxhash64_hex",
    "compute_file_checksum",
    "verify_chunk",
    "StreamingChecksum",
    # Main classes
    "FileSender",
    "FileReceiver",
    # Compatibility
    "Platform",
    "detect_platform",
    "get_platform_name",
    "pack_u16", "unpack_u16",
    "pack_u32", "unpack_u32",
    "pack_u64", "unpack_u64",
    "normalize_path_for_protocol",
    "normalize_path_for_local",
    "resolve_filename_conflict",
    "get_default_save_directory",
    "get_capabilities",
    "sanitize_device_name",
]
