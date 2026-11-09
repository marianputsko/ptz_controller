package live.budeme.ptz;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * vMix TCP API (port 8099). "SUBSCRIBE TALLY" makes vMix push "TALLY OK &lt;digits&gt;" on every change;
 * one digit per input: 0 = off, 1 = program, 2 = preview.
 */
public final class VmixClient extends SwitcherClient {
    private volatile Socket sock;

    public VmixClient(String ip, Tally tally) { super(ip, tally); }

    @Override protected void closeSockets() { try { if (sock != null) sock.close(); } catch (Exception ignored) {} }

    @Override public void run() {
        while (!stopped) {
            try (Socket s = new Socket()) {
                sock = s;
                s.connect(new InetSocketAddress(ip, 8099), 3000);
                s.setTcpNoDelay(true);
                s.setSoTimeout(5000);
                OutputStream out = s.getOutputStream();
                out.write("VERSION\r\nSUBSCRIBE TALLY\r\nTALLY\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                int idle = 0;
                while (!stopped) {
                    String line;
                    try { line = in.readLine(); }
                    catch (SocketTimeoutException e) { // keep-alive
                        if (++idle >= 2) throw e;
                        out.write("TALLY\r\n".getBytes(StandardCharsets.US_ASCII)); out.flush();
                        continue;
                    }
                    if (line == null) break;
                    idle = 0;
                    onLine(line.trim());
                }
                if (!stopped) tally.fail("vMix ukončil spojenie, pripájam znova…");
            } catch (ConnectException e) {
                if (!stopped) tally.fail("vMix na " + ip + " odmietol pripojenie – beží vMix?");
            } catch (Exception e) {
                if (!stopped) tally.fail("vMix na " + ip + " neodpovedá");
            }
            sock = null;
            if (!stopped) sleep(2000);
        }
    }

    private void onLine(String line) {
        if (line.startsWith("VERSION OK ")) {
            tally.model("vMix " + line.substring(11).trim());
        } else if (line.startsWith("TALLY OK")) {
            String d = line.substring(8).trim();
            if (!d.matches("\\d*")) return;
            List<Integer> pgm = new ArrayList<>(), pvw = new ArrayList<>();
            for (int i = 0; i < d.length(); i++) {
                char c = d.charAt(i);
                if (c == '1') pgm.add(i + 1); else if (c == '2') pvw.add(i + 1);
            }
            tally.state(pgm, pvw, d.length(), true);
        }
    }
}
