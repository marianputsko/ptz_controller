package live.budeme.ptz;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OSEE GoStream network protocol (UDP 19018, JSON). Send {"id":"pgmTally","type":"get","value":[]}
 * (and pvwTally); the switcher answers with type "res" and pushes type "pus" on every change.
 * Values are source IDs: IN1 = 1 … IN8 = 8, Player 1/2 = 9/10, Aux 3/4 = 11/12.
 * GoStream answers to port 19018 and sends pretty-printed JSON over several lines.
 */
public final class OseeClient extends SwitcherClient {
    private DatagramSocket sock;
    private List<Integer> pgm = null, pvw = null;
    private long lastRx = 0;
    private int tx = 0, rx = 0;
    private String raw = "";

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"(\\w+)\"");
    private static final Pattern TYPE = Pattern.compile("\"type\"\\s*:\\s*\"(\\w+)\"");
    private static final Pattern VALUE = Pattern.compile("\"value\"\\s*:\\s*(\\[[^\\]]*\\]|\"[^\"]*\"|-?\\d+)");
    private static final Pattern NUM = Pattern.compile("\\d+");

    public OseeClient(String ip, Tally tally) { super(ip, tally); }

    @Override protected void closeSockets() { if (sock != null) sock.close(); }

    private DatagramSocket open() throws Exception {
        try {
            DatagramSocket s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(19018));
            return s;
        } catch (Exception e) {
            return new DatagramSocket(); // 19018 taken: random port (works if GoStream answers to the source port)
        }
    }

    @Override public void run() {
        tally.model("OSEE GoStream");
        InetAddress addr;
        try {
            addr = InetAddress.getByName(ip);
            sock = open();
            sock.setSoTimeout(250);
        } catch (Exception e) { tally.error("Sieťová chyba: " + e.getMessage()); return; }
        byte[] buf = new byte[8192];
        long lastAsk = 0;
        while (!stopped) {
            long now = System.currentTimeMillis();
            if (now - lastAsk >= 1000) {
                lastAsk = now;
                ask(addr);
                if (now - lastRx > 3000) {
                    String why = rx > 0 ? "prišlo " + rx + " správ, ale nie tally. Posledná: " + raw.substring(0, Math.min(160, raw.length()))
                        : "odoslaných " + tx + " požiadaviek na port 19018, odpoveď 0 (počúvam na porte " + sock.getLocalPort() + ")";
                    tally.fail("OSEE GoStream na " + ip + " neodpovedá – " + why);
                    pgm = pvw = null;
                }
            }
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                sock.receive(p);
                String txt = new String(buf, 0, p.getLength(), StandardCharsets.UTF_8).replace("\u0000", "").trim();
                rx++; raw = txt.replaceAll("\\s+", " ");
                onMessage(txt);
            } catch (SocketTimeoutException e) {
                // next round
            } catch (Exception e) {
                if (stopped) break;
                tally.error("Sieťová chyba: " + e.getMessage());
                sleep(1000);
            }
        }
    }

    private void ask(InetAddress addr) {
        for (String id : new String[]{"pgmTally", "pvwTally"}) {
            byte[] b = ("{\"id\":\"" + id + "\",\"type\":\"get\",\"value\":[]}").getBytes(StandardCharsets.UTF_8);
            try { sock.send(new DatagramPacket(b, b.length, addr, 19018)); tx++; } catch (Exception e) { tally.error("Odoslanie zlyhalo: " + e.getMessage()); }
        }
    }

    /** Split a datagram into top-level JSON objects. */
    static List<String> jsonObjects(String t) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = -1; boolean str = false, esc = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (str) { if (esc) esc = false; else if (c == '\\') esc = true; else if (c == '"') str = false; continue; }
            if (c == '"') str = true;
            else if (c == '{') { if (depth++ == 0) start = i; }
            else if (c == '}' && depth > 0 && --depth == 0) out.add(t.substring(start, i + 1));
        }
        return out;
    }

    void onMessage(String txt) {
        for (String o : jsonObjects(txt)) {
            Matcher mi = ID.matcher(o), mt = TYPE.matcher(o), mv = VALUE.matcher(o);
            if (!mi.find() || !mt.find()) continue;
            String type = mt.group(1);
            if (!type.equals("res") && !type.equals("pus")) continue;
            List<Integer> v = new ArrayList<>();
            if (mv.find()) { Matcher n = NUM.matcher(mv.group(1)); while (n.find()) { int x = Integer.parseInt(n.group()); if (x > 0) v.add(x); } }
            String id = mi.group(1);
            if (id.equals("pgmTally")) pgm = v; else if (id.equals("pvwTally")) pvw = v; else continue;
            lastRx = System.currentTimeMillis();
            int max = 0;
            for (int x : pgm != null ? pgm : new ArrayList<Integer>()) max = Math.max(max, x);
            for (int x : pvw != null ? pvw : new ArrayList<Integer>()) max = Math.max(max, x);
            tally.state(pgm != null ? pgm : new ArrayList<Integer>(), pvw != null ? pvw : new ArrayList<Integer>(), max, pgm != null && pvw != null);
        }
    }
}
