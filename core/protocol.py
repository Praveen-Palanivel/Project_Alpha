"""LocalStream Core - Protocol message encoding/decoding."""

import json
import struct
from typing import Tuple, Any, Dict

from .types import MessageType, DeviceInfo, FileMetadata


# Message header format: 1 byte type + 4 bytes payload length
HEADER_FORMAT = ">BL"  # Big-endian: unsigned char + unsigned long
HEADER_SIZE = struct.calcsize(HEADER_FORMAT)  # 5 bytes


def encode_message(msg_type: MessageType, payload: Dict[str, Any]) -> bytes:
    """
    Encode a control message for transmission.
    
    Format: [MsgType: 1 byte][PayloadLen: 4 bytes][Payload: variable]
    
    Args:
        msg_type: The message type enum
        payload: Dictionary to be JSON-encoded as payload
        
    Returns:
        Encoded message bytes
    """
    payload_bytes = json.dumps(payload, separators=(',', ':')).encode('utf-8')
    header = struct.pack(HEADER_FORMAT, msg_type, len(payload_bytes))
    return header + payload_bytes


def decode_message(data: bytes) -> Tuple[MessageType, Dict[str, Any]]:
    """
    Decode a control message.
    
    Args:
        data: Raw message bytes including header
        
    Returns:
        Tuple of (message_type, payload_dict)
        
    Raises:
        ValueError: If message is malformed
    """
    if len(data) < HEADER_SIZE:
        raise ValueError(f"Message too short: {len(data)} bytes, need at least {HEADER_SIZE}")
    
    msg_type_raw, payload_len = struct.unpack(HEADER_FORMAT, data[:HEADER_SIZE])
    
    try:
        msg_type = MessageType(msg_type_raw)
    except ValueError:
        raise ValueError(f"Unknown message type: 0x{msg_type_raw:02x}")
    
    if len(data) < HEADER_SIZE + payload_len:
        raise ValueError(f"Incomplete payload: got {len(data) - HEADER_SIZE}, expected {payload_len}")
    
    payload_bytes = data[HEADER_SIZE:HEADER_SIZE + payload_len]
    payload = json.loads(payload_bytes.decode('utf-8'))
    
    return msg_type, payload


def read_message_header(data: bytes) -> Tuple[MessageType, int]:
    """
    Read just the message header to determine type and length.
    
    Args:
        data: At least HEADER_SIZE bytes
        
    Returns:
        Tuple of (message_type, payload_length)
    """
    if len(data) < HEADER_SIZE:
        raise ValueError(f"Not enough data for header: {len(data)} bytes")
    
    msg_type_raw, payload_len = struct.unpack(HEADER_FORMAT, data[:HEADER_SIZE])
    msg_type = MessageType(msg_type_raw)
    
    return msg_type, payload_len


# ============================================================================
# Message builders for each message type
# ============================================================================

def build_hello(device_info: DeviceInfo) -> bytes:
    """Build HELLO message."""
    payload = {
        "protocol_version": device_info.protocol_version,
        "device_name": device_info.device_name,
        "device_id": device_info.device_id,
        "capabilities": device_info.capabilities
    }
    return encode_message(MessageType.HELLO, payload)


def build_hello_ack(device_info: DeviceInfo, accepted: bool = True) -> bytes:
    """Build HELLO_ACK message."""
    payload = {
        "protocol_version": device_info.protocol_version,
        "device_name": device_info.device_name,
        "device_id": device_info.device_id,
        "accepted": accepted
    }
    return encode_message(MessageType.HELLO_ACK, payload)


def build_file_offer(metadata: FileMetadata) -> bytes:
    """Build FILE_OFFER message."""
    return encode_message(MessageType.FILE_OFFER, metadata.to_dict())


def build_file_accept(transfer_id: str, save_path: str) -> bytes:
    """Build FILE_ACCEPT message."""
    payload = {
        "transfer_id": transfer_id,
        "accepted": True,
        "save_path": save_path
    }
    return encode_message(MessageType.FILE_ACCEPT, payload)


def build_file_reject(transfer_id: str, reason: str = "") -> bytes:
    """Build FILE_REJECT message."""
    payload = {
        "transfer_id": transfer_id,
        "accepted": False,
        "reason": reason
    }
    return encode_message(MessageType.FILE_REJECT, payload)


def build_chunk_ack(transfer_id: str, acked_chunks: list, last_acked: int) -> bytes:
    """Build CHUNK_ACK message."""
    payload = {
        "transfer_id": transfer_id,
        "acked_chunks": acked_chunks,
        "last_acked": last_acked
    }
    return encode_message(MessageType.CHUNK_ACK, payload)


def build_transfer_complete(transfer_id: str) -> bytes:
    """Build TRANSFER_COMPLETE message."""
    payload = {"transfer_id": transfer_id}
    return encode_message(MessageType.TRANSFER_COMPLETE, payload)


def build_transfer_verified(transfer_id: str, success: bool = True) -> bytes:
    """Build TRANSFER_VERIFIED message."""
    payload = {
        "transfer_id": transfer_id,
        "verified": success
    }
    return encode_message(MessageType.TRANSFER_VERIFIED, payload)


def build_error(transfer_id: str, error_code: int, error_message: str) -> bytes:
    """Build ERROR message."""
    payload = {
        "transfer_id": transfer_id,
        "error_code": error_code,
        "error_message": error_message
    }
    return encode_message(MessageType.ERROR, payload)


def build_cancel(transfer_id: str) -> bytes:
    """Build CANCEL message."""
    payload = {"transfer_id": transfer_id}
    return encode_message(MessageType.CANCEL, payload)


def build_resume_request(transfer_id: str, file_checksum: str) -> bytes:
    """Build RESUME_REQUEST message."""
    payload = {
        "transfer_id": transfer_id,
        "file_checksum": file_checksum
    }
    return encode_message(MessageType.RESUME_REQUEST, payload)


def build_resume_response(
    transfer_id: str,
    can_resume: bool,
    received_chunks: list,
    missing_chunks: list
) -> bytes:
    """Build RESUME_RESPONSE message."""
    payload = {
        "transfer_id": transfer_id,
        "can_resume": can_resume,
        "received_chunks": received_chunks,
        "missing_chunks": missing_chunks
    }
    return encode_message(MessageType.RESUME_RESPONSE, payload)


# ============================================================================
# Data stream chunk format
# ============================================================================

# Chunk header: 16 bytes UUID + 4 bytes index + 8 bytes checksum
CHUNK_HEADER_FORMAT = ">16sLQ"  # 16 bytes + unsigned long + unsigned long long
CHUNK_HEADER_SIZE = struct.calcsize(CHUNK_HEADER_FORMAT)  # 28 bytes


def encode_chunk(transfer_id: str, chunk_index: int, chunk_checksum: int, data: bytes) -> bytes:
    """
    Encode a data chunk for transmission on a data stream.
    
    Format: [TransferID: 16 bytes][ChunkIndex: 4 bytes][ChunkChecksum: 8 bytes][Data]
    
    Args:
        transfer_id: UUID string (will be converted to bytes)
        chunk_index: 0-based chunk index
        chunk_checksum: xxHash64 of the chunk data as integer
        data: Raw chunk data
        
    Returns:
        Encoded chunk bytes
    """
    import uuid
    transfer_id_bytes = uuid.UUID(transfer_id).bytes
    header = struct.pack(CHUNK_HEADER_FORMAT, transfer_id_bytes, chunk_index, chunk_checksum)
    return header + data


def decode_chunk_header(data: bytes) -> Tuple[str, int, int]:
    """
    Decode chunk header to get transfer ID, index, and checksum.
    
    Args:
        data: At least CHUNK_HEADER_SIZE bytes
        
    Returns:
        Tuple of (transfer_id, chunk_index, chunk_checksum)
    """
    import uuid
    
    if len(data) < CHUNK_HEADER_SIZE:
        raise ValueError(f"Not enough data for chunk header: {len(data)} bytes")
    
    transfer_id_bytes, chunk_index, chunk_checksum = struct.unpack(
        CHUNK_HEADER_FORMAT, 
        data[:CHUNK_HEADER_SIZE]
    )
    
    transfer_id = str(uuid.UUID(bytes=transfer_id_bytes))
    
    return transfer_id, chunk_index, chunk_checksum


def get_chunk_data(data: bytes) -> bytes:
    """Extract chunk data after header."""
    return data[CHUNK_HEADER_SIZE:]
