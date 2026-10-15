package com.example.tiltmidi;

import android.os.SystemClock;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;

final class RtpMidi {
    interface Listener { void status(String text); }
    private final Listener listener;
    private volatile Session current;

    private static final class Session {
        final String host;
        final int port;
        final int token = new Random().nextInt();
        final int ssrc = new Random().nextInt();
        final ArrayBlockingQueue<byte[]> outgoing = new ArrayBlockingQueue<>(256);
        volatile boolean running = true;
        volatile boolean ready;
        InetAddress peer;
        DatagramSocket control, data;
        int peerSsrc;
        int sequence = new Random().nextInt(65536);
        boolean invited;
        Session(String host, int port) { this.host = host; this.port = port; }
    }

    RtpMidi(Listener listener) { this.listener = listener; }
    boolean connected() {
        Session session = current;
        return session != null && session.running && session.ready;
    }

    synchronized boolean connect(String host, int port) {
        disconnect();
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65534) {
            listener.status("RTP-MIDI：请填写有效 IP 和控制端口（1–65534）");
            return false;
        }
        Session session = new Session(host.trim(), port);
        current = session;
        new Thread(() -> runSession(session), "rtp-midi").start();
        return true;
    }

    private void report(Session session, String text) {
        if (current == session && session.running) listener.status(text);
    }

    private void openSockets(Session session) throws Exception {
        for (int attempt = 0; attempt < 32; attempt++) {
            DatagramSocket control = new DatagramSocket();
            int base = control.getLocalPort();
            try {
                if (base == 65535) { control.close(); continue; }
                DatagramSocket data = new DatagramSocket(base + 1);
                session.control = control;
                session.data = data;
                return;
            } catch (Exception exception) { control.close(); }
        }
        throw new java.io.IOException("无法分配 UDP 控制/数据端口");
    }

    private void runSession(Session session) {
        try {
            // All socket operations, including DNS and disconnect, run off the UI thread.
            session.peer = InetAddress.getByName(session.host);
            if (!session.running) return;
            openSockets(session);
            session.control.setSoTimeout(500);
            session.data.setSoTimeout(500);
            invite(session, session.control, session.port, false);
            session.invited = true;
            invite(session, session.data, session.port + 1, true);
            if (!session.running) return;
            session.control.setSoTimeout(5);
            session.data.setSoTimeout(5);
            sendClock(session, session.data, session.port + 1, 0, ticks(), 0, 0);
            session.ready = true;
            report(session, "RTP-MIDI 已连接 UDP " + session.port + "/" + (session.port + 1));
            long nextClock = SystemClock.elapsedRealtime() + 10000;
            while (session.running) {
                for (int count = 0; count < 64 && session.running; count++) {
                    byte[] midi = session.outgoing.poll();
                    if (midi == null) break;
                    ByteBuffer packet = ByteBuffer.allocate(13 + midi.length).order(ByteOrder.BIG_ENDIAN);
                    packet.put((byte) 0x80).put((byte) 0x61);
                    packet.putShort((short) session.sequence++).putInt((int) ticks()).putInt(session.ssrc);
                    packet.put((byte) midi.length).put(midi);
                    sendPacket(session, session.data, session.port + 1, packet.array());
                }
                receive(session, session.control, session.port);
                receive(session, session.data, session.port + 1);
                if (SystemClock.elapsedRealtime() >= nextClock) {
                    sendClock(session, session.data, session.port + 1, 0, ticks(), 0, 0);
                    nextClock = SystemClock.elapsedRealtime() + 10000;
                }
            }
        } catch (Exception exception) {
            session.ready = false;
            report(session, "RTP-MIDI：" + exception.getMessage());
        } finally {
            session.ready = false;
            if (session.invited && session.control != null && !session.control.isClosed()) {
                try { sendPacket(session, session.control, session.port,
                        ctrl("BY", session.token, session.ssrc, null)); }
                catch (Exception ignored) { }
            }
            session.running = false;
            if (session.control != null) session.control.close();
            if (session.data != null) session.data.close();
            synchronized (this) { if (current == session) current = null; }
        }
    }

    private void invite(Session session, DatagramSocket socket, int port, boolean dataPort) throws Exception {
        byte[] invitation = ctrl("IN", session.token, session.ssrc, "Glissway");
        long deadline = SystemClock.elapsedRealtime() + 7000;
        byte[] buffer = new byte[2048];
        while (session.running && SystemClock.elapsedRealtime() < deadline) {
            sendPacket(session, socket, port, invitation);
            DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(reply);
                if (!fromPeer(session, reply, port)) continue;
                if (reply.getLength() >= 16 && (cmd(buffer, "OK") || cmd(buffer, "NO"))
                        && u32(buffer, 4) == 2 && u32(buffer, 8) == Integer.toUnsignedLong(session.token)) {
                    if (cmd(buffer, "NO")) throw new java.io.IOException("电脑拒绝连接，请检查 Who may connect to me");
                    int peerSsrc = (int) u32(buffer, 12);
                    if (dataPort && peerSsrc != session.peerSsrc) continue;
                    session.peerSsrc = peerSsrc;
                    return;
                }
                handleControl(session, socket, port, reply);
            } catch (SocketTimeoutException ignored) { }
        }
        throw new java.io.IOException((dataPort ? "数据" : "控制") + "端握手超时，请检查电脑 IP、端口和防火墙");
    }

    private void receive(Session session, DatagramSocket socket, int port) throws Exception {
        byte[] buffer = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        try {
            socket.receive(packet);
            if (fromPeer(session, packet, port)) handleControl(session, socket, port, packet);
        } catch (SocketTimeoutException ignored) { }
    }

    private static boolean fromPeer(Session session, DatagramPacket packet, int port) {
        return packet.getAddress().equals(session.peer) && packet.getPort() == port;
    }

    private void handleControl(Session session, DatagramSocket socket, int port, DatagramPacket packet) throws Exception {
        if (packet.getLength() < 4) return;
        byte[] bytes = packet.getData();
        if (cmd(bytes, "BY") && packet.getLength() >= 16 && u32(bytes, 12) == Integer.toUnsignedLong(session.peerSsrc)) {
            throw new java.io.IOException("电脑已断开 RTP-MIDI，请重新连接");
        }
        if (!cmd(bytes, "CK") || packet.getLength() < 36
                || u32(bytes, 4) != Integer.toUnsignedLong(session.peerSsrc)) return;
        ByteBuffer clock = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        long first = clock.getLong(12);
        long second = clock.getLong(20);
        int count = bytes[8] & 255;
        if (count == 0) sendClock(session, socket, port, 1, first, ticks(), 0);
        else if (count == 1) sendClock(session, socket, port, 2, first, second, ticks());
    }

    private static long ticks() { return SystemClock.elapsedRealtime() * 10L; }

    private static void sendClock(Session session, DatagramSocket socket, int port, int count,
                                  long first, long second, long third) throws Exception {
        ByteBuffer packet = ByteBuffer.allocate(36).order(ByteOrder.BIG_ENDIAN);
        packet.putShort((short) 0xffff).put((byte) 'C').put((byte) 'K').putInt(session.ssrc);
        packet.put((byte) count).put(new byte[3]).putLong(first).putLong(second).putLong(third);
        sendPacket(session, socket, port, packet.array());
    }

    private static void sendPacket(Session session, DatagramSocket socket, int port, byte[] bytes) throws Exception {
        socket.send(new DatagramPacket(bytes, bytes.length, session.peer, port));
    }

    static byte[] ctrl(String command, int token, int ssrc, String name) {
        byte[] label = name == null ? new byte[0] : name.getBytes(StandardCharsets.UTF_8);
        ByteBuffer packet = ByteBuffer.allocate(16 + label.length + (name == null ? 0 : 1)).order(ByteOrder.BIG_ENDIAN);
        packet.putShort((short) 0xffff).put(command.getBytes(StandardCharsets.US_ASCII));
        packet.putInt(2).putInt(token).putInt(ssrc).put(label);
        if (name != null) packet.put((byte) 0);
        return packet.array();
    }

    static boolean cmd(byte[] bytes, String command) {
        return bytes.length >= 4 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xff
                && bytes[2] == command.charAt(0) && bytes[3] == command.charAt(1);
    }

    static long u32(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(offset));
    }

    boolean send(int channel, int cc, int value) {
        if (channel < 1 || channel > 16 || cc < 0 || cc > 127 || value < 0 || value > 127) return false;
        return sendMessage(0xb0 | (channel - 1), cc, value);
    }

    boolean sendPitchBend(int channel, int value) {
        if (channel < 1 || channel > 16 || value < 0 || value > 16383) return false;
        return sendMessage(0xe0 | (channel - 1), value & 127, (value >> 7) & 127);
    }

    private boolean sendMessage(int status, int data1, int data2) {
        Session session = current;
        if (session == null || !session.running || !session.ready) return false;
        return session.outgoing.offer(new byte[] { (byte) status, (byte) data1, (byte) data2 });
    }

    synchronized void disconnect() {
        Session session = current;
        current = null;
        if (session != null) { session.ready = false; session.running = false; }
        // The worker sends BY and closes its own sockets without blocking the UI.
    }
}
