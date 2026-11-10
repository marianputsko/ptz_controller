package live.budeme.ptz;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Built-in replacement for PTZ Bridge. Listens on 127.0.0.1 only and serves the controller page plus the
 * same JSON API as PTZ Bridge (/tally, /api/status, /api/config), so the page works unchanged.
 * Serving the page from http://127.0.0.1 makes it a secure context, which the H.265 video decoder requires.
 */
public final class LocalServer {
    public interface Store {
        String get(String key, String def);
        void put(String key, String value);
        byte[] asset(String path) throws Exception; // null when missing
    }

    public static final String VERSION = "0.9";
    private final int port;
    private final Store store;
    private final Tally tally = new Tally();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket server;
    private SwitcherClient client;
    private String swType = "none", swIp = "";

    public LocalServer(int port, Store store) {
        this.port = port; this.store = store;
        swType = store.get("swType", "none");
        swIp = store.get("swIp", "");
    }

    public synchronized void start() throws Exception {
        server = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(this::acceptLoop, "LocalServer");
        t.setDaemon(true);
        t.start();
        startSwitcher();
    }

    public synchronized void stop() {
        if (client != null) client.stop();
        client = null;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        pool.shutdownNow();
    }

    private synchronized void startSwitcher() {
        if (client != null) client.stop();
        client = null;
        tally.reset(swType);
        if (swType.equals("none")) return;
        if (swIp.isEmpty()) { tally.error("Zadaj IP adresu réžie"); return; }
        client = SwitcherClient.create(swType, swIp, tally);
        if (client != null) client.start();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try { Socket s = server.accept(); pool.execute(() -> handle(s)); }
            catch (Exception e) { if (server.isClosed()) return; }
        }
    }

    private String statusJson() {
        return "{\"app\":\"ptz-bridge\",\"embedded\":true,\"version\":" + Tally.Json.str(VERSION)
            + ",\"host\":\"Android\",\"port\":" + port + ",\"urls\":[],\"primary\":\"\",\"switcher\":" + tally.switcherJson(swIp)
            + ",\"tablets\":[]}";
    }

    private static final Pattern P_TYPE = Pattern.compile("\"type\"\\s*:\\s*\"(\\w*)\"");
    private static final Pattern P_IP = Pattern.compile("\"ip\"\\s*:\\s*\"([^\"]*)\"");

    private String config(String body) {
        Matcher mt = P_TYPE.matcher(body), mi = P_IP.matcher(body);
        String type = mt.find() ? mt.group(1) : "none";
        if (!type.matches("none|atem|vmix|osee")) type = "none";
        String ip = mi.find() ? mi.group(1).trim() : "";
        if (!ip.isEmpty() && !ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return null;
        synchronized (this) {
            swType = type; swIp = ip;
            store.put("swType", type); store.put("swIp", ip);
            startSwitcher();
        }
        return statusJson();
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(5000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] req = lines[0].split(" ");
            if (req.length < 2) return;
            String method = req[0], path = req[1];
            int q = path.indexOf('?'); if (q >= 0) path = path.substring(0, q);
            int len = 0;
            for (String l : lines) if (l.toLowerCase(Locale.ROOT).startsWith("content-length:")) len = Integer.parseInt(l.substring(15).trim());
            byte[] body = new byte[Math.min(len, 100000)];
            int got = 0; while (got < body.length) { int r = in.read(body, got, body.length - got); if (r < 0) break; got += r; }
            OutputStream out = sock.getOutputStream();

            if (method.equals("OPTIONS")) { send(out, 204, "text/plain", new byte[0]); return; }
            if (path.equals("/tally")) { send(out, 200, "application/json", tally.tallyJson().getBytes(StandardCharsets.UTF_8)); return; }
            if (path.equals("/api/status")) { send(out, 200, "application/json", statusJson().getBytes(StandardCharsets.UTF_8)); return; }
            if (path.equals("/api/config") && method.equals("POST")) {
                String r = config(new String(body, 0, got, StandardCharsets.UTF_8));
                if (r == null) send(out, 400, "application/json", "{\"error\":\"Neplatná IP adresa\"}".getBytes(StandardCharsets.UTF_8));
                else send(out, 200, "application/json", r.getBytes(StandardCharsets.UTF_8));
                return;
            }
            String file = path.equals("/") ? "index.html" : path.substring(1);
            if (file.contains("..")) { send(out, 403, "text/plain", new byte[0]); return; }
            byte[] data = store.asset("www/" + file);
            if (data == null) { send(out, 404, "text/plain; charset=utf-8", "Nenájdené".getBytes(StandardCharsets.UTF_8)); return; }
            send(out, 200, mime(file), data);
        } catch (Exception ignored) {
        }
    }

    private static String readHead(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, n = 0;
        while ((c = in.read()) >= 0) {
            b.write(c);
            n = (c == '\r' || c == '\n') ? n + 1 : 0;
            if (n == 4) return b.toString("ISO-8859-1");
            if (b.size() > 16384) return null;
        }
        return null;
    }

    private static String mime(String f) {
        if (f.endsWith(".html")) return "text/html; charset=utf-8";
        if (f.endsWith(".js")) return "text/javascript";
        if (f.endsWith(".css")) return "text/css";
        if (f.endsWith(".svg")) return "image/svg+xml";
        if (f.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    private static void send(OutputStream out, int code, String type, byte[] data) throws Exception {
        String reason = code == 200 ? "OK" : code == 204 ? "No Content" : code == 400 ? "Bad Request" : code == 403 ? "Forbidden" : "Not Found";
        String h = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: " + data.length
            + "\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS"
            + "\r\nAccess-Control-Allow-Headers: Content-Type\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        out.write(data);
        out.flush();
    }
}
