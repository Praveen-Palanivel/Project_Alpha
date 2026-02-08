"""LocalStream Core - QUIC File Receiver (Task A4)."""

import asyncio
import logging
import time
from pathlib import Path
from typing import Optional, Dict, Set, List
from dataclasses import dataclass, field

import aiofiles
from aioquic.asyncio import serve
from aioquic.asyncio.protocol import QuicConnectionProtocol
from aioquic.quic.configuration import QuicConfiguration
from aioquic.quic.events import StreamDataReceived, ConnectionTerminated

from .types import (
    DeviceInfo, FileMetadata, TransferState, TransferResult,
    TransferProgress, ErrorCode, Constants, ProgressCallback, FileReceivedCallback
)
from .protocol import (
    MessageType, decode_message, HEADER_SIZE, CHUNK_HEADER_SIZE,
    build_hello_ack, build_file_accept, build_chunk_ack,
    build_transfer_verified, read_message_header,
    decode_chunk_header, get_chunk_data
)
from .checksum import verify_chunk, compute_file_checksum


logger = logging.getLogger(__name__)


@dataclass
class ActiveTransfer:
    """State for an active file transfer."""
    metadata: FileMetadata
    save_path: Path
    temp_path: Path
    received_chunks: Set[int] = field(default_factory=set)
    chunk_data: Dict[int, bytes] = field(default_factory=dict)
    start_time: float = field(default_factory=time.time)
    bytes_received: int = 0


class FileReceiverProtocol(QuicConnectionProtocol):
    """QUIC protocol handler for file receiving."""
    
    def __init__(self, *args, receiver: "FileReceiver", **kwargs):
        super().__init__(*args, **kwargs)
        self.receiver = receiver
        self.control_stream_id: Optional[int] = None
        self.transfer_state = TransferState.PENDING
        self.active_transfers: Dict[str, ActiveTransfer] = {}
        self._pending_control_data = b""
        self._pending_chunk_data: Dict[int, bytes] = {}
        self._remote_device: Optional[DeviceInfo] = None
    
    def quic_event_received(self, event):
        """Handle QUIC events."""
        if isinstance(event, StreamDataReceived):
            # Stream 0 = control, odd streams = data (unidirectional from client)
            if event.stream_id == 0 or (event.stream_id % 4 == 0):
                # Bidirectional stream - likely control
                if self.control_stream_id is None:
                    self.control_stream_id = event.stream_id
                self._handle_control_data(event.data)
            else:
                # Unidirectional stream - data chunk
                self._handle_chunk_data(event.stream_id, event.data, event.end_stream)
                
        elif isinstance(event, ConnectionTerminated):
            logger.info(f"Connection terminated: {event.error_code}")
            self.transfer_state = TransferState.FAILED
    
    def _handle_control_data(self, data: bytes):
        """Handle data on control stream."""
        self._pending_control_data += data
        
        while len(self._pending_control_data) >= HEADER_SIZE:
            try:
                msg_type, payload_len = read_message_header(self._pending_control_data)
                total_len = HEADER_SIZE + payload_len
                
                if len(self._pending_control_data) < total_len:
                    break
                
                msg_data = self._pending_control_data[:total_len]
                self._pending_control_data = self._pending_control_data[total_len:]
                
                msg_type, payload = decode_message(msg_data)
                asyncio.create_task(self._process_control_message(msg_type, payload))
                
            except Exception as e:
                logger.error(f"Error parsing control message: {e}")
                break
    
    async def _process_control_message(self, msg_type: MessageType, payload: dict):
        """Process a control message."""
        logger.debug(f"Received control message: {msg_type.name}")
        
        if msg_type == MessageType.HELLO:
            await self._handle_hello(payload)
        elif msg_type == MessageType.FILE_OFFER:
            await self._handle_file_offer(payload)
        elif msg_type == MessageType.TRANSFER_COMPLETE:
            await self._handle_transfer_complete(payload)
        elif msg_type == MessageType.CANCEL:
            await self._handle_cancel(payload)
    
    async def _handle_hello(self, payload: dict):
        """Handle HELLO message - respond with HELLO_ACK."""
        self._remote_device = DeviceInfo(
            device_id=payload.get("device_id", ""),
            device_name=payload.get("device_name", "Unknown"),
            platform="unknown",
            capabilities=payload.get("capabilities", []),
            protocol_version=payload.get("protocol_version", "1.0.0")
        )
        
        logger.info(f"Connection from: {self._remote_device.device_name}")
        
        # Send HELLO_ACK
        ack = build_hello_ack(self.receiver.device_info, accepted=True)
        self._quic.send_stream_data(self.control_stream_id, ack, end_stream=False)
        self.transmit()
        
        self.transfer_state = TransferState.HANDSHAKING
    
    async def _handle_file_offer(self, payload: dict):
        """Handle FILE_OFFER - accept or reject based on callback."""
        metadata = FileMetadata.from_dict(payload)
        logger.info(f"File offer: {metadata.file_name} ({metadata.file_size} bytes)")
        
        # Determine save path
        save_path = self.receiver.save_directory / metadata.file_name
        temp_path = self.receiver.save_directory / f".{metadata.file_name}.partial"
        
        # Handle duplicate filenames
        counter = 1
        while save_path.exists():
            stem = Path(metadata.file_name).stem
            suffix = Path(metadata.file_name).suffix
            save_path = self.receiver.save_directory / f"{stem} ({counter}){suffix}"
            counter += 1
        
        # Auto-accept for now (could add callback for user approval)
        self.active_transfers[metadata.transfer_id] = ActiveTransfer(
            metadata=metadata,
            save_path=save_path,
            temp_path=temp_path
        )
        
        # Send FILE_ACCEPT
        accept = build_file_accept(metadata.transfer_id, str(save_path))
        self._quic.send_stream_data(self.control_stream_id, accept, end_stream=False)
        self.transmit()
        
        self.transfer_state = TransferState.TRANSFERRING
        logger.info(f"Accepted file, saving to: {save_path}")
    
    def _handle_chunk_data(self, stream_id: int, data: bytes, end_stream: bool):
        """Handle data chunk from a data stream."""
        # Accumulate data for this stream
        if stream_id not in self._pending_chunk_data:
            self._pending_chunk_data[stream_id] = b""
        self._pending_chunk_data[stream_id] += data
        
        if not end_stream:
            return  # Wait for complete chunk
        
        chunk_data = self._pending_chunk_data.pop(stream_id)
        
        if len(chunk_data) < CHUNK_HEADER_SIZE:
            logger.error(f"Chunk too small: {len(chunk_data)} bytes")
            return
        
        try:
            transfer_id, chunk_index, chunk_checksum = decode_chunk_header(chunk_data)
            payload = get_chunk_data(chunk_data)
            
            # Verify checksum
            if not verify_chunk(payload, chunk_checksum):
                logger.error(f"Checksum mismatch for chunk {chunk_index}")
                return
            
            # Find transfer
            if transfer_id not in self.active_transfers:
                logger.error(f"Unknown transfer: {transfer_id}")
                return
            
            transfer = self.active_transfers[transfer_id]
            transfer.received_chunks.add(chunk_index)
            transfer.chunk_data[chunk_index] = payload
            transfer.bytes_received += len(payload)
            
            logger.debug(f"Received chunk {chunk_index}, total: {len(transfer.received_chunks)}/{transfer.metadata.chunk_count}")
            
            # Send ACK every N chunks
            if len(transfer.received_chunks) % Constants.CHUNK_ACK_INTERVAL == 0:
                asyncio.create_task(self._send_chunk_ack(transfer))
            
            # Update progress
            if self.receiver.progress_callback:
                progress = TransferProgress(
                    transfer_id=transfer_id,
                    total_chunks=transfer.metadata.chunk_count,
                    completed_chunks=len(transfer.received_chunks),
                    bytes_transferred=transfer.bytes_received,
                    total_bytes=transfer.metadata.file_size
                )
                self.receiver.progress_callback(progress)
                
        except Exception as e:
            logger.exception(f"Error processing chunk: {e}")
    
    async def _send_chunk_ack(self, transfer: ActiveTransfer):
        """Send chunk acknowledgment."""
        acked = sorted(list(transfer.received_chunks))
        ack = build_chunk_ack(
            transfer.metadata.transfer_id,
            acked[-Constants.CHUNK_ACK_INTERVAL:],  # Last N chunks
            acked[-1] if acked else -1
        )
        self._quic.send_stream_data(self.control_stream_id, ack, end_stream=False)
        self.transmit()
    
    async def _handle_transfer_complete(self, payload: dict):
        """Handle TRANSFER_COMPLETE - write file and verify."""
        transfer_id = payload.get("transfer_id")
        
        if transfer_id not in self.active_transfers:
            logger.error(f"Unknown transfer complete: {transfer_id}")
            return
        
        transfer = self.active_transfers[transfer_id]
        
        # Send final ACK
        await self._send_chunk_ack(transfer)
        
        # Write all chunks to file
        logger.info(f"Writing file: {transfer.save_path}")
        try:
            async with aiofiles.open(transfer.save_path, 'wb') as f:
                for i in range(transfer.metadata.chunk_count):
                    if i in transfer.chunk_data:
                        await f.write(transfer.chunk_data[i])
                    else:
                        logger.error(f"Missing chunk {i}")
                        # Send verification failure
                        verified = build_transfer_verified(transfer_id, success=False)
                        self._quic.send_stream_data(self.control_stream_id, verified, end_stream=False)
                        self.transmit()
                        return
            
            # Verify file checksum
            file_checksum = await compute_file_checksum(transfer.save_path)
            if file_checksum == transfer.metadata.file_checksum:
                logger.info(f"File verified: {transfer.save_path}")
                verified = build_transfer_verified(transfer_id, success=True)
                self.transfer_state = TransferState.COMPLETE
                
                # Notify callback
                if self.receiver.on_file_received:
                    self.receiver.on_file_received(transfer.metadata, transfer.save_path)
            else:
                logger.error(f"File checksum mismatch!")
                verified = build_transfer_verified(transfer_id, success=False)
                self.transfer_state = TransferState.FAILED
            
            self._quic.send_stream_data(self.control_stream_id, verified, end_stream=False)
            self.transmit()
            
            # Cleanup
            del self.active_transfers[transfer_id]
            
        except Exception as e:
            logger.exception(f"Error writing file: {e}")
            verified = build_transfer_verified(transfer_id, success=False)
            self._quic.send_stream_data(self.control_stream_id, verified, end_stream=False)
            self.transmit()
    
    async def _handle_cancel(self, payload: dict):
        """Handle transfer cancellation."""
        transfer_id = payload.get("transfer_id")
        if transfer_id in self.active_transfers:
            transfer = self.active_transfers[transfer_id]
            logger.info(f"Transfer cancelled: {transfer.metadata.file_name}")
            
            # Cleanup temp file
            if transfer.temp_path.exists():
                transfer.temp_path.unlink()
            
            del self.active_transfers[transfer_id]
        
        self.transfer_state = TransferState.CANCELLED


class FileReceiver:
    """
    High-speed file receiver using QUIC protocol.
    
    Usage:
        receiver = FileReceiver(device_info, save_directory)
        await receiver.start_server(
            host="0.0.0.0",
            port=42424,
            on_file_received=lambda m, p: print(f"Received: {p}")
        )
    """
    
    def __init__(
        self,
        device_info: DeviceInfo,
        save_directory: Path
    ):
        self.device_info = device_info
        self.save_directory = Path(save_directory)
        self.save_directory.mkdir(parents=True, exist_ok=True)
        
        self.progress_callback: Optional[ProgressCallback] = None
        self.on_file_received: Optional[FileReceivedCallback] = None
        self._server = None
    
    async def start_server(
        self,
        host: str = "0.0.0.0",
        port: int = Constants.DEFAULT_QUIC_PORT,
        progress_callback: Optional[ProgressCallback] = None,
        on_file_received: Optional[FileReceivedCallback] = None,
        cert_path: Optional[Path] = None,
        key_path: Optional[Path] = None
    ) -> None:
        """
        Start the QUIC receiver server.
        
        Args:
            host: Host to bind to
            port: Port to listen on
            progress_callback: Called with progress updates
            on_file_received: Called when a file is completely received
            cert_path: Path to TLS certificate (generates self-signed if None)
            key_path: Path to TLS private key
        """
        self.progress_callback = progress_callback
        self.on_file_received = on_file_received
        
        # Generate self-signed cert if not provided
        if cert_path is None or key_path is None:
            cert_path, key_path = await self._generate_self_signed_cert()
        
        config = QuicConfiguration(
            is_client=False,
            alpn_protocols=["localstream-1"],
        )
        config.load_cert_chain(str(cert_path), str(key_path))
        
        logger.info(f"Starting receiver on {host}:{port}")
        
        self._server = await serve(
            host,
            port,
            configuration=config,
            create_protocol=lambda *args, **kwargs: FileReceiverProtocol(
                *args, receiver=self, **kwargs
            )
        )
        
        logger.info(f"Receiver listening on {host}:{port}")
        
        # Keep server running
        await asyncio.Future()
    
    async def _generate_self_signed_cert(self) -> tuple:
        """Generate self-signed certificate for TLS."""
        from cryptography import x509
        from cryptography.x509.oid import NameOID
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        from datetime import datetime, timedelta
        
        cert_dir = self.save_directory / ".certs"
        cert_dir.mkdir(exist_ok=True)
        cert_path = cert_dir / "cert.pem"
        key_path = cert_dir / "key.pem"
        
        if cert_path.exists() and key_path.exists():
            return cert_path, key_path
        
        # Generate key
        key = rsa.generate_private_key(
            public_exponent=65537,
            key_size=2048
        )
        
        # Generate certificate
        subject = issuer = x509.Name([
            x509.NameAttribute(NameOID.COMMON_NAME, "LocalStream"),
        ])
        
        cert = (
            x509.CertificateBuilder()
            .subject_name(subject)
            .issuer_name(issuer)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(datetime.utcnow())
            .not_valid_after(datetime.utcnow() + timedelta(days=365))
            .sign(key, hashes.SHA256())
        )
        
        # Write to files
        with open(key_path, "wb") as f:
            f.write(key.private_bytes(
                encoding=serialization.Encoding.PEM,
                format=serialization.PrivateFormat.TraditionalOpenSSL,
                encryption_algorithm=serialization.NoEncryption()
            ))
        
        with open(cert_path, "wb") as f:
            f.write(cert.public_bytes(serialization.Encoding.PEM))
        
        logger.info(f"Generated self-signed certificate: {cert_path}")
        return cert_path, key_path
    
    async def stop(self):
        """Stop the receiver server."""
        if self._server:
            self._server.close()
            logger.info("Receiver stopped")


async def receive_files_cli():
    """Command-line interface for receiving files."""
    import argparse
    
    parser = argparse.ArgumentParser(description="LocalStream File Receiver")
    parser.add_argument("--save-dir", "-d", default="./received", help="Directory to save files")
    parser.add_argument("--port", "-p", type=int, default=42424, help="Port to listen on")
    parser.add_argument("--name", "-n", default="CLI Receiver", help="Device name")
    args = parser.parse_args()
    
    logging.basicConfig(level=logging.INFO)
    
    device = DeviceInfo.generate(args.name, "windows")
    receiver = FileReceiver(device, Path(args.save_dir))
    
    def on_progress(p: TransferProgress):
        print(f"\rProgress: {p.progress_percent:.1f}%", end="", flush=True)
    
    def on_received(metadata: FileMetadata, path: Path):
        print(f"\nReceived: {path}")
    
    try:
        await receiver.start_server(
            port=args.port,
            progress_callback=on_progress,
            on_file_received=on_received
        )
    except KeyboardInterrupt:
        await receiver.stop()


if __name__ == "__main__":
    asyncio.run(receive_files_cli())
