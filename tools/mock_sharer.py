#!/usr/bin/env python3
"""Minimal MoonShare sharer for testing MoonView without an Android phone.

It speaks the real v1 control/UDP protocol and replays an Annex-B H.264 file that
contains AUD NAL units (ffmpeg -x264-params aud=1). This is a development tool,
not part of the app.
"""
from __future__ import annotations

import argparse
import json
import secrets
import socket
import struct
import threading
import time
from pathlib import Path

MAGIC = b"MSV1"
UDP_MAGIC = b"MSU1"
HEADER = struct.Struct(">4sBBHQIHHHHQ")
UDP_HELLO = struct.Struct(">4sBBH Q 16s")


def split_access_units(data: bytes) -> list[bytes]:
    nals: list[tuple[int, int, int]] = []
    i = 0
    while True:
        j = data.find(b"\x00\x00\x01", i)
        if j < 0:
            break
        start = j
        if j > 0 and data[j - 1] == 0:
            start = j - 1
        nals.append((start, j + 3, data[j + 3] & 0x1F))
        i = j + 3

    if not nals:
        return [data]

    units: list[bytes] = []
    current = nals[0][0]
    for start, _, nal_type in nals:
        if nal_type == 9 and start > current:
            units.append(data[current:start])
            current = start
    units.append(data[current:])
    return [unit for unit in units if unit]


def key_frame(unit: bytes) -> bool:
    i = 0
    while True:
        j = unit.find(b"\x00\x00\x01", i)
        if j < 0:
            return False
        if (unit[j + 3] & 0x1F) == 5:
            return True
        i = j + 3


def build_welcome(code: str, session: int, token: bytes, width: int, height: int, fps: int, bitrate: int) -> dict:
    return {
        "t": "welcome",
        "v": 1,
        "session": f"{session:016x}",
        "token": token.hex(),
        "name": "Mock Pixel",
        "width": width,
        "height": height,
        "fps": fps,
        "codec": "h264",
        "profile": "baseline",
        "bitrate": bitrate,
        "videoPort": 47801,
        "tcpPort": 47800,
    }


class MockSharer:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.units = split_access_units(Path(args.file).read_bytes())
        self.key_indices = [i for i, unit in enumerate(self.units) if key_frame(unit)]
        self.session = secrets.randbits(63) or 1
        self.token = secrets.token_bytes(16)
        self.viewer = None
        self.viewer_port = None
        self.running = True
        self.send_lock = threading.Lock()
        self.force_key = threading.Event()
        self.stop_event = threading.Event()

        if not self.key_indices:
            raise SystemExit("test stream has no IDR frame")

    def serve(self) -> None:
        tcp = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
        tcp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        tcp.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
        tcp.bind(("::", self.args.port))
        tcp.listen(4)
        udp = socket.socket(socket.AF_INET6, socket.SOCK_DGRAM)
        udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        udp.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
        udp.bind(("::", self.args.video_port))
        udp.settimeout(0.5)
        print(f"mock sharer listening tcp={self.args.port} udp={self.args.video_port} code={self.args.code}", flush=True)

        threading.Thread(target=self.udp_loop, args=(udp,), daemon=True).start()
        threading.Thread(target=self.discovery_loop, daemon=True).start()
        try:
            while self.running:
                conn, addr = tcp.accept()
                print(f"control connection from {addr}", flush=True)
                threading.Thread(target=self.control_loop, args=(conn,), daemon=True).start()
        except KeyboardInterrupt:
            pass
        finally:
            tcp.close()
            udp.close()

    def discovery_loop(self) -> None:
        discovery = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        discovery.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            discovery.bind(("0.0.0.0", 47802))
            discovery.settimeout(0.5)
        except OSError as exc:
            print(f"discovery bind failed: {exc}", flush=True)
            return
        response = json.dumps({
            "app": "MoonShare",
            "v": 1,
            "name": "Mock Pixel",
            "port": self.args.port,
            "videoPort": self.args.video_port,
            "sharing": True,
            "ips": ["127.0.0.1"],
        }).encode()
        while self.running:
            try:
                data, addr = discovery.recvfrom(1024)
            except socket.timeout:
                continue
            except OSError:
                return
            if data.strip() == b"MOONSHARE/1 DISCOVER":
                try:
                    discovery.sendto(response, addr)
                except OSError:
                    pass
        discovery.close()

    def control_loop(self, conn: socket.socket) -> None:
        conn.settimeout(30)
        reader = conn.makefile("r", encoding="utf-8", newline="\n")
        writer = conn.makefile("w", encoding="utf-8", newline="\n")
        try:
            line = reader.readline()
            if not line:
                return
            hello = json.loads(line)
            if hello.get("code") != self.args.code:
                writer.write(json.dumps({"t": "error", "code": "bad_code", "message": "Pairing code is incorrect"}) + "\n")
                writer.flush()
                time.sleep(0.25)
                return
            # A new control session gets a fresh video endpoint. The old viewer's
            # UDP mapping is no longer valid.
            self.viewer = None
            self._send_family = None
            welcome = build_welcome(
                self.args.code,
                self.session,
                self.token,
                self.args.width,
                self.args.height,
                self.args.fps,
                self.args.bitrate,
            )
            writer.write(json.dumps(welcome) + "\n")
            writer.flush()
            print("viewer paired", flush=True)
            while self.running:
                line = reader.readline()
                if not line:
                    break
                try:
                    message = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if message.get("t") == "ping":
                    writer.write(json.dumps({"t": "pong", "ts": message.get("ts", 0)}) + "\n")
                    writer.flush()
                elif message.get("t") == "keyframe":
                    self.force_key.set()
        except Exception as exc:
            print(f"control loop ended: {exc}", flush=True)
        finally:
            try:
                conn.close()
            except OSError:
                pass

    def udp_loop(self, udp: socket.socket) -> None:
        while self.running:
            try:
                data, addr = udp.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError:
                return
            if len(data) != 32 or data[:4] != UDP_MAGIC or data[4] != 1 or data[5] != 1:
                continue
            session = struct.unpack(">Q", data[8:16])[0]
            token = data[16:32]
            if session != self.session or token != self.token:
                continue
            if self.viewer is None:
                self.viewer = addr
                print(f"video path ready: {addr}", flush=True)
            ack = UDP_MAGIC + bytes([1, 2, 0, 0]) + struct.pack(">Q", self.session) + self.token
            try:
                udp.sendto(ack, addr)
            except OSError:
                pass

    def stream_loop(self) -> None:
        start = time.monotonic()
        frame_id = 0
        index = 0
        pts = 0
        interval = 1.0 / self.args.fps
        try:
            while self.running:
                if self.viewer is None:
                    time.sleep(0.05)
                    continue
                if self.force_key.is_set():
                    self.force_key.clear()
                    index = self.key_indices[0]
                unit = self.units[index]
                key = key_frame(unit)
                self.send_unit(unit, key, frame_id, pts)
                frame_id = (frame_id + 1) & 0x7FFFFFFF
                pts += int(1_000_000 / self.args.fps)
                index = (index + 1) % len(self.units)
                deadline = start + (frame_id * interval)
                delay = deadline - time.monotonic()
                if delay > 0:
                    time.sleep(delay)
                elif delay < -0.5:
                    start = time.monotonic() - frame_id * interval
        except Exception as exc:
            print(f"stream loop ended: {exc}", flush=True)

    def send_unit(self, unit: bytes, key: bool, frame_id: int, pts: int) -> None:
        if self.viewer is None:
            return
        count = max(1, (len(unit) + 1163) // 1164)
        if count > 0xFFFF:
            return
        for i in range(count):
            payload = unit[i * 1164:(i + 1) * 1164]
            header = HEADER.pack(
                MAGIC,
                1,
                1 if key else 0,
                36,
                self.session,
                frame_id,
                i,
                count,
                len(payload),
                0,
                pts,
            )
            try:
                with self.send_lock:
                    self.viewer_send(header + payload)
            except OSError:
                return

    def viewer_send(self, packet: bytes) -> None:
        # Reply on the same address family the viewer used. Sending an IPv6-mapped
        # address from an AF_INET6 socket is not reliably received by an AF_INET
        # viewer socket on Windows.
        host = self.viewer[0]
        port = self.viewer[1]
        if host.startswith("::ffff:"):
            if getattr(self, "_send_family", None) != socket.AF_INET:
                self._send_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                self._send_family = socket.AF_INET
            self._send_socket.sendto(packet, (host[7:], port))
        else:
            if getattr(self, "_send_family", None) != socket.AF_INET6:
                self._send_socket = socket.socket(socket.AF_INET6, socket.SOCK_DGRAM)
                self._send_family = socket.AF_INET6
            self._send_socket.sendto(packet, (host, port))

    def run(self) -> None:
        threading.Thread(target=self.stream_loop, daemon=True).start()
        self.serve()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", default="test_pattern.h264")
    parser.add_argument("--code", default="123456")
    parser.add_argument("--port", type=int, default=47800)
    parser.add_argument("--video-port", type=int, default=47801)
    parser.add_argument("--width", type=int, default=1280)
    parser.add_argument("--height", type=int, default=720)
    parser.add_argument("--fps", type=int, default=30)
    parser.add_argument("--bitrate", type=int, default=5_000_000)
    args = parser.parse_args()
    MockSharer(args).run()


if __name__ == "__main__":
    main()
