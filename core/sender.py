"""LocalStream Core - QUIC File Sender (Task A3)."""

import asyncio
import logging
import time
from pathlib import Path
from typing import Optional, List, Set
from ssl import SSLContext

import aiofiles
from aioquic.asyncio import connect
from aioquic.asyncio.protocol import QuicConnectionProtocol
from aioquic.quic.configuration import QuicConfiguration
from aioquic.quic.events import StreamDataReceived, ConnectionTerminated

from .types import (
    DeviceInfo, FileMetadata, TransferState, TransferResult,
    TransferProgress, ErrorCode, Constants, ProgressCallback
)
from .protocol import (
    MessageType, decode_message, HEADER_SIZE,
    build_hello, build_file_offer, build_transfer_complete,
    encode_chunk, read_message_header
)
from .checksum import compute_file_checksum, compute_xxhash64


logger = logging.getLogger(__name__)


class FileSenderProtocol(QuicConnectionProtocol):
    """QUIC protocol handler for file sending."""
    
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.control_stream_id: Optional[int] = None
        self.received_messages: asyncio.Queue = asyncio.Queue()
        self.transfer_state = TransferState.CONNECTING
        self._pending_data = b""
    
    def quic_event_received(self, event):
        """Handle QUIC events."""
        if isinstance(event, StreamDataReceived):
            if event.stream_id == self.control_stream_id:
                self._handle_control_data(event.data)
        elif isinstance(event, ConnectionTerminated):
            logger.info(f"Connection terminated: {event.error_code}")
            self.transfer_state = TransferState.FAILED
    
    def _handle_control_data(self, data: bytes):
        """Handle data received on control stream."""
        self._pending_data += data
        
        # Try to parse complete messages
        while len(self._pending_data) >= HEADER_SIZE:
            try:
                msg_type, payload_len = read_message_header(self._pending_data)
                total_len = HEADER_SIZE + payload_len
                
                if len(self._pending_data) < total_len:
                    break  # Wait for more data
                
                msg_data = self._pending_data[:total_len]
                self._pending_data = self._pending_data[total_len:]
                
                msg_type, payload = decode_message(msg_data)
                self.received_messages.put_nowait((msg_type, payload))
                
            except Exception as e:
                logger.error(f"Error parsing message: {e}")
                break


class FileSender:
    """
    High-speed file sender using QUIC protocol.
    
    Usage:
        sender = FileSender(device_info)
        result = await sender.send_file(
            file_path=Path("large_file.zip"),
            receiver_host="192.168.1.100",
            receiver_port=42424,
            progress_callback=lambda p: print(f"{p.progress_percent:.1f}%")
        )
    """
    
    def __init__(
        self,
        device_info: DeviceInfo,
        chunk_size: int = Constants.DEFAULT_CHUNK_SIZE,
        parallel_streams: int = Constants.PARALLEL_STREAMS
    ):
        self.device_info = device_info
        self.chunk_size = chunk_size
        self.parallel_streams = parallel_streams
        self._protocol: Optional[FileSenderProtocol] = None
    
    async def send_file(
        self,
        file_path: Path,
        receiver_host: str,
        receiver_port: int = Constants.DEFAULT_QUIC_PORT,
        progress_callback: Optional[ProgressCallback] = None,
        resume_from_chunk: int = 0
    ) -> TransferResult:
        """
        Send a file to a receiver.
        
        Args:
            file_path: Path to the file to send
            receiver_host: Receiver's IP address
            receiver_port: Receiver's QUIC port
            progress_callback: Called with progress updates
            resume_from_chunk: Start from this chunk (for resume)
            
        Returns:
            TransferResult with success status and details
        """
        start_time = time.time()
        
        # Validate file
        if not file_path.exists():
            return TransferResult(
                success=False,
                transfer_id="",
                error_code=ErrorCode.FILE_NOT_FOUND,
                error_message=f"File not found: {file_path}"
            )
        
        try:
            # Compute file checksum
            logger.info(f"Computing checksum for {file_path}")
            file_checksum = await compute_file_checksum(file_path)
            
            # Create metadata
            metadata = FileMetadata.from_path(file_path, file_checksum, self.chunk_size)
            logger.info(f"File: {metadata.file_name}, Size: {metadata.file_size}, Chunks: {metadata.chunk_count}")
            
            # Configure QUIC
            config = QuicConfiguration(
                is_client=True,
                alpn_protocols=["localstream-1"],
            )
            # Allow self-signed certs for local transfer
            config.verify_mode = False
            
            # Connect to receiver
            logger.info(f"Connecting to {receiver_host}:{receiver_port}")
            async with connect(
                receiver_host,
                receiver_port,
                configuration=config,
                create_protocol=FileSenderProtocol
            ) as protocol:
                self._protocol = protocol
                
                # Open control stream
                protocol.control_stream_id = protocol._quic.get_next_available_stream_id()
                
                # Handshake
                if not await self._do_handshake(protocol):
                    return TransferResult(
                        success=False,
                        transfer_id=metadata.transfer_id,
                        error_code=ErrorCode.CONNECTION_LOST,
                        error_message="Handshake failed"
                    )
                
                # Offer file
                if not await self._offer_file(protocol, metadata):
                    return TransferResult(
                        success=False,
                        transfer_id=metadata.transfer_id,
                        error_code=ErrorCode.TRANSFER_REJECTED,
                        error_message="File rejected by receiver"
                    )
                
                # Transfer chunks
                bytes_sent = await self._transfer_chunks(
                    protocol,
                    file_path,
                    metadata,
                    progress_callback,
                    resume_from_chunk
                )
                
                # Complete transfer
                await self._complete_transfer(protocol, metadata)
                
                elapsed = time.time() - start_time
                
                return TransferResult(
                    success=True,
                    transfer_id=metadata.transfer_id,
                    file_path=file_path,
                    bytes_transferred=bytes_sent,
                    elapsed_seconds=elapsed
                )
                
        except Exception as e:
            logger.exception(f"Transfer failed: {e}")
            return TransferResult(
                success=False,
                transfer_id=metadata.transfer_id if 'metadata' in locals() else "",
                error_code=ErrorCode.CONNECTION_LOST,
                error_message=str(e),
                elapsed_seconds=time.time() - start_time
            )
    
    async def _do_handshake(self, protocol: FileSenderProtocol) -> bool:
        """Perform HELLO handshake."""
        # Send HELLO
        hello_msg = build_hello(self.device_info)
        protocol._quic.send_stream_data(
            protocol.control_stream_id,
            hello_msg,
            end_stream=False
        )
        protocol.transmit()
        
        # Wait for HELLO_ACK
        try:
            msg_type, payload = await asyncio.wait_for(
                protocol.received_messages.get(),
                timeout=Constants.CONNECTION_TIMEOUT_MS / 1000
            )
            
            if msg_type != MessageType.HELLO_ACK:
                logger.error(f"Expected HELLO_ACK, got {msg_type}")
                return False
            
            if not payload.get("accepted", False):
                logger.error("Connection rejected by receiver")
                return False
            
            protocol.transfer_state = TransferState.HANDSHAKING
            logger.info(f"Connected to: {payload.get('device_name')}")
            return True
            
        except asyncio.TimeoutError:
            logger.error("Handshake timeout")
            return False
    
    async def _offer_file(self, protocol: FileSenderProtocol, metadata: FileMetadata) -> bool:
        """Offer file and wait for acceptance."""
        # Send FILE_OFFER
        offer_msg = build_file_offer(metadata)
        protocol._quic.send_stream_data(
            protocol.control_stream_id,
            offer_msg,
            end_stream=False
        )
        protocol.transmit()
        
        # Wait for FILE_ACCEPT or FILE_REJECT
        try:
            msg_type, payload = await asyncio.wait_for(
                protocol.received_messages.get(),
                timeout=60  # Give user time to accept
            )
            
            if msg_type == MessageType.FILE_ACCEPT:
                protocol.transfer_state = TransferState.TRANSFERRING
                logger.info(f"File accepted, saving to: {payload.get('save_path')}")
                return True
            elif msg_type == MessageType.FILE_REJECT:
                logger.info(f"File rejected: {payload.get('reason', 'No reason')}")
                return False
            else:
                logger.error(f"Unexpected message: {msg_type}")
                return False
                
        except asyncio.TimeoutError:
            logger.error("File offer timeout")
            return False
    
    async def _transfer_chunks(
        self,
        protocol: FileSenderProtocol,
        file_path: Path,
        metadata: FileMetadata,
        progress_callback: Optional[ProgressCallback],
        start_chunk: int
    ) -> int:
        """Transfer file chunks using parallel streams."""
        total_bytes = 0
        chunks_to_send = list(range(start_chunk, metadata.chunk_count))
        acked_chunks: Set[int] = set()
        active_streams: dict = {}
        
        async with aiofiles.open(file_path, 'rb') as f:
            chunk_idx = 0
            
            while chunks_to_send or active_streams:
                # Start new streams up to parallel limit
                while chunks_to_send and len(active_streams) < self.parallel_streams:
                    chunk_index = chunks_to_send.pop(0)
                    stream_id = protocol._quic.get_next_available_stream_id(is_unidirectional=True)
                    
                    # Read chunk data
                    await f.seek(chunk_index * self.chunk_size)
                    chunk_data = await f.read(self.chunk_size)
                    
                    if not chunk_data:
                        break
                    
                    # Compute chunk checksum
                    chunk_checksum = compute_xxhash64(chunk_data)
                    
                    # Encode and send chunk
                    encoded = encode_chunk(
                        metadata.transfer_id,
                        chunk_index,
                        chunk_checksum,
                        chunk_data
                    )
                    
                    protocol._quic.send_stream_data(stream_id, encoded, end_stream=True)
                    active_streams[stream_id] = chunk_index
                    total_bytes += len(chunk_data)
                    
                    logger.debug(f"Sent chunk {chunk_index} on stream {stream_id}")
                
                protocol.transmit()
                
                # Check for ACKs (non-blocking)
                try:
                    msg_type, payload = await asyncio.wait_for(
                        protocol.received_messages.get(),
                        timeout=0.01  # Quick check
                    )
                    
                    if msg_type == MessageType.CHUNK_ACK:
                        for acked in payload.get("acked_chunks", []):
                            acked_chunks.add(acked)
                            # Remove from active streams
                            for sid, cidx in list(active_streams.items()):
                                if cidx == acked:
                                    del active_streams[sid]
                                    break
                        
                        # Update progress
                        if progress_callback:
                            progress = TransferProgress(
                                transfer_id=metadata.transfer_id,
                                total_chunks=metadata.chunk_count,
                                completed_chunks=len(acked_chunks),
                                bytes_transferred=len(acked_chunks) * self.chunk_size,
                                total_bytes=metadata.file_size
                            )
                            progress_callback(progress)
                            
                except asyncio.TimeoutError:
                    pass
                
                # Small delay to prevent busy loop
                await asyncio.sleep(0.001)
        
        # Wait for final ACKs
        timeout = time.time() + 5
        while len(acked_chunks) < metadata.chunk_count and time.time() < timeout:
            try:
                msg_type, payload = await asyncio.wait_for(
                    protocol.received_messages.get(),
                    timeout=0.5
                )
                if msg_type == MessageType.CHUNK_ACK:
                    for acked in payload.get("acked_chunks", []):
                        acked_chunks.add(acked)
            except asyncio.TimeoutError:
                pass
        
        return total_bytes
    
    async def _complete_transfer(self, protocol: FileSenderProtocol, metadata: FileMetadata):
        """Send transfer complete and wait for verification."""
        complete_msg = build_transfer_complete(metadata.transfer_id)
        protocol._quic.send_stream_data(
            protocol.control_stream_id,
            complete_msg,
            end_stream=False
        )
        protocol.transmit()
        
        # Wait for TRANSFER_VERIFIED
        try:
            msg_type, payload = await asyncio.wait_for(
                protocol.received_messages.get(),
                timeout=30
            )
            
            if msg_type == MessageType.TRANSFER_VERIFIED:
                if payload.get("verified", False):
                    logger.info("Transfer verified by receiver")
                    protocol.transfer_state = TransferState.COMPLETE
                else:
                    logger.warning("Transfer verification failed")
                    protocol.transfer_state = TransferState.FAILED
            else:
                logger.warning(f"Unexpected message: {msg_type}")
                
        except asyncio.TimeoutError:
            logger.warning("Verification timeout, assuming success")
            protocol.transfer_state = TransferState.COMPLETE


async def send_file_cli():
    """Command-line interface for sending files."""
    import argparse
    
    parser = argparse.ArgumentParser(description="LocalStream File Sender")
    parser.add_argument("--file", "-f", required=True, help="File to send")
    parser.add_argument("--host", "-H", required=True, help="Receiver host")
    parser.add_argument("--port", "-p", type=int, default=42424, help="Receiver port")
    parser.add_argument("--name", "-n", default="CLI Sender", help="Device name")
    args = parser.parse_args()
    
    logging.basicConfig(level=logging.INFO)
    
    device = DeviceInfo.generate(args.name, "windows")
    sender = FileSender(device)
    
    def progress(p: TransferProgress):
        print(f"\rProgress: {p.progress_percent:.1f}%", end="", flush=True)
    
    result = await sender.send_file(
        file_path=Path(args.file),
        receiver_host=args.host,
        receiver_port=args.port,
        progress_callback=progress
    )
    
    print()
    if result.success:
        print(f"Transfer complete: {result.speed_mbps:.1f} MB/s")
    else:
        print(f"Transfer failed: {result.error_message}")


if __name__ == "__main__":
    asyncio.run(send_file_cli())
