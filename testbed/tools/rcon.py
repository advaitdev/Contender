#!/usr/bin/env python3
"""Minimal RCON client: rcon.py "command" ["command" ...]"""
import socket, struct, sys, time

def packet(req_id, kind, body):
    data = struct.pack('<ii', req_id, kind) + body.encode('utf-8') + b'\x00\x00'
    return struct.pack('<i', len(data)) + data

def read(sock):
    raw = b''
    while len(raw) < 4:
        chunk = sock.recv(4 - len(raw))
        if not chunk: raise ConnectionError('closed')
        raw += chunk
    length = struct.unpack('<i', raw)[0]
    data = b''
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk: raise ConnectionError('closed')
        data += chunk
    req_id, kind = struct.unpack('<ii', data[:8])
    return req_id, kind, data[8:-2].decode('utf-8', 'replace')

def main():
    host, port, password = '127.0.0.1', 25575, 'test'
    sock = socket.create_connection((host, port), timeout=30)
    sock.sendall(packet(1, 3, password))
    rid, _, _ = read(sock)
    if rid == -1:
        print('auth failed'); sys.exit(1)
    for i, cmd in enumerate(sys.argv[1:]):
        if cmd.startswith('sleep:'):
            time.sleep(float(cmd[6:])); continue
        sock.sendall(packet(10 + i, 2, cmd))
        _, _, body = read(sock)
        print(f'> {cmd}')
        if body.strip(): print(body)
    sock.close()

if __name__ == '__main__':
    main()
