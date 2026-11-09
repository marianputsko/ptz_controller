package live.budeme.ptz;

import java.util.ArrayList;
import java.util.List;

/** Shared tally state, written by the switcher clients and read by the local HTTP server. */
public final class Tally {
    public String type = "none";
    public boolean connected = false;
    public String model = "";
    public List<Integer> program = new ArrayList<>();
    public List<Integer> preview = new ArrayList<>();
    public int inputs = 0;
    public String error = "";

    public synchronized void reset(String type) {
        this.type = type; connected = false; model = ""; program = new ArrayList<>(); preview = new ArrayList<>(); inputs = 0; error = "";
    }
    public synchronized void fail(String err) { connected = false; program = new ArrayList<>(); preview = new ArrayList<>(); error = err; }
    public synchronized void error(String err) { connected = false; error = err; }
    public synchronized void model(String m) { model = m; }
    public synchronized void state(List<Integer> pgm, List<Integer> pvw, int n, boolean conn) {
        program = new ArrayList<>(pgm); preview = new ArrayList<>(pvw); inputs = Math.max(inputs, n); connected = conn; if (conn) error = "";
    }

    public synchronized String tallyJson() {
        return "{\"connected\":" + connected + ",\"switcher\":" + Json.str(type) + ",\"model\":" + Json.str(model)
            + ",\"program\":" + Json.arr(program) + ",\"preview\":" + Json.arr(preview) + "}";
    }
    public synchronized String switcherJson(String ip) {
        return "{\"type\":" + Json.str(type) + ",\"ip\":" + Json.str(ip) + ",\"connected\":" + connected + ",\"model\":" + Json.str(model)
            + ",\"error\":" + Json.str(error) + ",\"program\":" + Json.arr(program) + ",\"preview\":" + Json.arr(preview) + ",\"inputs\":" + inputs + "}";
    }

    /** Tiny JSON helpers (no dependencies). */
    public static final class Json {
        public static String str(String s) {
            if (s == null) return "null";
            StringBuilder b = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"': b.append("\\\""); break;
                    case '\\': b.append("\\\\"); break;
                    case '\n': b.append("\\n"); break;
                    case '\r': b.append("\\r"); break;
                    case '\t': b.append("\\t"); break;
                    default: if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
                }
            }
            return b.append('"').toString();
        }
        public static String arr(List<Integer> l) {
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) { if (i > 0) b.append(','); b.append(l.get(i)); }
            return b.append(']').toString();
        }
    }
}
