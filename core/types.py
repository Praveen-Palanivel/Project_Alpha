"""LocalStream Core - Type definitions and data classes."""

from dataclasses import dataclass, field
from enum import IntEnum, auto
from pathlib import Path
from typing import List, Optional, Callable, Any
from datetime import datetime
import uuid


class MessageType(IntEnum):
    """Control message types as defined in protocol.md."""
    HELLO = 0x01
    HELLO_ACK = 0x02
    FILE_OFFER = 0x03
    FILE_ACCEPT = 0x04
    FILE_REJECT = 0x05
    CHUNK_ACK = 0x06
    TRANSFER_COMPLETE = 0x07
    TRANSFER_VERIFIED = 0x08
    ERROR = 0x09
    CANCEL = 0x0A
    RESUME_REQUEST = 0x0B
    RESUME_RESPONSE = 0x0C


class ErrorCode(IntEnum):
    """Error codes as defined in protocol.md."""
    OK = 0x00
    CHECKSUM_MISMATCH = 0x01
    FILE_NOT_FOUND = 0x02
    STORAGE_FULL = 0x03
    CONNECTION_LOST = 0x04
    TIMEOUT = 0x05
    CANCELLED = 0x06
    INVALID_MESSAGE = 0x07
    VERSION_MISMATCH = 0x08
    TRANSFER_REJECTED = 0x09
    RESUME_FAILED = 0x0A


class TransferState(IntEnum):
    """Transfer state machine states."""
    PENDING = auto()
    CONNECTING = auto()
    HANDSHAKING = auto()
    NEGOTIATING = auto()
    TRANSFERRING = auto()
    COMPLETING = auto()
    COMPLETE = auto()
    FAILED = auto()
    CANCELLED = auto()


@dataclass
class DeviceInfo:
    """Information about a LocalStream device."""
    device_id: str
    device_name: str
    platform: str  # "android" or "windows"
    capabilities: List[str] = field(default_factory=lambda: ["quic", "tcp", "resume"])
    protocol_version: str = "1.0.0"
    
    @classmethod
    def generate(cls, device_name: str, platform: str) -> "DeviceInfo":
        """Generate a new DeviceInfo with random UUID."""
        return cls(
            device_id=str(uuid.uuid4()),
            device_name=device_name,
            platform=platform
        )


@dataclass
class FileMetadata:
    """Metadata for a file being transferred."""
    transfer_id: str
    file_name: str
    file_size: int
    mime_type: str
    chunk_size: int
    chunk_count: int
    checksum_type: str
    file_checksum: str
    created_at: str
    modified_at: str
    
    @classmethod
    def from_path(
        cls,
        path: Path,
        file_checksum: str,
        chunk_size: int = 4 * 1024 * 1024  # 4MB default
    ) -> "FileMetadata":
        """Create FileMetadata from a file path."""
        import mimetypes
        
        stat = path.stat()
        file_size = stat.st_size
        chunk_count = (file_size + chunk_size - 1) // chunk_size
        
        mime_type, _ = mimetypes.guess_type(str(path))
        if mime_type is None:
            mime_type = "application/octet-stream"
        
        now = datetime.utcnow().isoformat() + "Z"
        
        return cls(
            transfer_id=str(uuid.uuid4()),
            file_name=path.name,
            file_size=file_size,
            mime_type=mime_type,
            chunk_size=chunk_size,
            chunk_count=chunk_count,
            checksum_type="xxhash64",
            file_checksum=file_checksum,
            created_at=now,
            modified_at=datetime.fromtimestamp(stat.st_mtime).isoformat() + "Z"
        )
    
    def to_dict(self) -> dict:
        """Convert to dictionary for JSON serialization."""
        return {
            "transfer_id": self.transfer_id,
            "file_name": self.file_name,
            "file_size": self.file_size,
            "mime_type": self.mime_type,
            "chunk_size": self.chunk_size,
            "chunk_count": self.chunk_count,
            "checksum_type": self.checksum_type,
            "file_checksum": self.file_checksum,
            "created_at": self.created_at,
            "modified_at": self.modified_at
        }
    
    @classmethod
    def from_dict(cls, data: dict) -> "FileMetadata":
        """Create from dictionary."""
        return cls(**data)


@dataclass
class ChunkInfo:
    """Information about a single chunk."""
    index: int
    offset: int
    size: int
    checksum: Optional[str] = None


@dataclass
class TransferProgress:
    """Progress information for a transfer."""
    transfer_id: str
    total_chunks: int
    completed_chunks: int
    bytes_transferred: int
    total_bytes: int
    speed_bytes_per_sec: float = 0.0
    
    @property
    def progress_percent(self) -> float:
        """Get progress as percentage."""
        if self.total_bytes == 0:
            return 100.0
        return (self.bytes_transferred / self.total_bytes) * 100


@dataclass
class TransferResult:
    """Result of a file transfer operation."""
    success: bool
    transfer_id: str
    file_path: Optional[Path] = None
    error_code: Optional[ErrorCode] = None
    error_message: Optional[str] = None
    bytes_transferred: int = 0
    elapsed_seconds: float = 0.0
    
    @property
    def speed_mbps(self) -> float:
        """Get average speed in MB/s."""
        if self.elapsed_seconds == 0:
            return 0.0
        return (self.bytes_transferred / 1024 / 1024) / self.elapsed_seconds


# Type aliases for callbacks
ProgressCallback = Callable[[TransferProgress], None]
FileReceivedCallback = Callable[[FileMetadata, Path], None]
ErrorCallback = Callable[[ErrorCode, str], None]


# Protocol constants from protocol.md
class Constants:
    """Protocol constants."""
    PROTOCOL_VERSION = "1.0.0"
    DEFAULT_QUIC_PORT = 42424
    DEFAULT_TCP_PORT = 42424
    DISCOVERY_PORT = 42425
    DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024  # 4 MB
    MIN_CHUNK_SIZE = 1 * 1024 * 1024      # 1 MB
    MAX_CHUNK_SIZE = 16 * 1024 * 1024     # 16 MB
    PARALLEL_STREAMS = 4
    MAX_PARALLEL_STREAMS = 8
    CHUNK_ACK_INTERVAL = 8
    ACK_TIMEOUT_MS = 100
    MAX_RETRY_COUNT = 3
    CONNECTION_TIMEOUT_MS = 30000
