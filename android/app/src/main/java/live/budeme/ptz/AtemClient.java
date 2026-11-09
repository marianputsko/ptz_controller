package live.budeme.ptz;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Blackmagic ATEM client (UDP 9910): handshake, acknowledgements and the
 * "TlIn" (tally by input), "_pin" (product name) and "InCm" (initial state done) commands.
 */
public final class AtemClient extends SwitcherClient {
    private DatagramSocket sock;
    private InetAddress addr;
    private int session = 0, lastRemoteId = -1;
    private boolean connected = false;
    private long lastRx = 0, helloAt = 0;

    public AtemClient(String ip, Tally tally) { super(ip, tally); }

    @Override protected void closeSockets() { if (sock != null) sock.close(); }

    @Override public void run() {
        try {
            addr = InetAddress.getByName(ip);
            sock = new DatagramSocket();
            sock.setSoTimeout(250);
        } catch (Exception e) { tally.error("Sieťová chyba: " + e.getMessage()); return; }
        hello();
        byte[] buf = new byte[2048];
        while (!stopped) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                sock.receive(p);
                onMessage(buf, p.getLength());
            } catch (SocketTimeoutException e) {
                // fall through to the timers
            } catch (Exception e) {
                if (stopped) break;
                tally.error("Sieťová chyba: " + e.getMessage());
                sleep(1000);
            }
            long now = System.currentTimeMillis();
            if (connected && now - lastRx > 3000) {
                tally.fail("Réžia prestala odpovedať, pripájam znova…");
                hello();
            } else if (!connected && now - helloAt > 2000) {
                tally.error("Réžia na " + ip + " neodpovedá");
                hello();
            }
        }
    }

    private void send(byte[] b) {
        try { sock.send(new DatagramPacket(b, b.length, addr, 9910)); } catch (Exception e) { tally.error("Odoslanie zlyhalo: " + e.getMessage()); }
    }

    private void hello() {
        connected = false; lastRemoteId = -1; helloAt = System.currentTimeMillis();
        send(new byte[]{0x10, 0x14, 0x53, (byte) 0xab, 0, 0, 0, 0, 0, 0x3a, 0, 0, 0x01, 0, 0, 0, 0, 0, 0, 0});
    }

    private void ack(int remoteId) {
        byte[] b = new byte[12];
        int h = (0x10 << 11) | 12;
        b[0] = (byte) (h >> 8); b[1] = (byte) h;
        b[2] = (byte) (session >> 8); b[3] = (byte) session;
        b[4] = (byte) (remoteId >> 8); b[5] = (byte) remoteId;
        send(b);
    }

    private static int u16(byte[] b, int o) { return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff); }

    private void onMessage(byte[] m, int len) {
        if (len < 12) return;
        int flags = (m[0] & 0xff) >> 3;
        int length = u16(m, 0) & 0x07ff;
        session = u16(m, 2);
        int remoteId = u16(m, 10);
        lastRx = System.currentTimeMillis();
        if ((flags & 0x02) != 0) { // hello answer
            if (len > 12 && m[12] == 0x03) { tally.error("Réžia odmietla pripojenie (príliš veľa klientov)"); return; }
            connected = true; lastRemoteId = remoteId; ack(remoteId);
            return;
        }
        if (!connected) return;
        if ((flags & 0x01) != 0) { // reliable packet, must be acknowledged
            int expected = (lastRemoteId + 1) & 0x7fff;
            ack(remoteId);
            if (lastRemoteId < 0 || remoteId == expected) {
                lastRemoteId = remoteId;
                parseCommands(m, 12, Math.min(length, len));
            }
        }
    }

    private void parseCommands(byte[] b, int off, int end) {
        while (off + 8 <= end) {
            int len = u16(b, off);
            if (len < 8 || off + len > end) break;
            String name = new String(b, off + 4, 4, StandardCharsets.US_ASCII);
            onCommand(name, b, off + 8, off + len);
            off += len;
        }
    }

    private void onCommand(String name, byte[] b, int start, int end) {
        if (name.equals("_pin")) {
            int z = start; while (z < end && b[z] != 0) z++;
            tally.model(new String(b, start, z - start, StandardCharsets.UTF_8).trim());
        } else if (name.equals("InCm")) {
            synchronized (tally) { tally.connected = true; tally.error = ""; }
        } else if (name.equals("TlIn") && end - start >= 2) {
            int n = u16(b, start);
            List<Integer> pgm = new ArrayList<>(), pvw = new ArrayList<>();
            for (int i = 0; i < n && start + 2 + i < end; i++) {
                int f = b[start + 2 + i];
                if ((f & 0x01) != 0) pgm.add(i + 1);
                if ((f & 0x02) != 0) pvw.add(i + 1);
            }
            tally.state(pgm, pvw, n, true);
        }
    }
}
