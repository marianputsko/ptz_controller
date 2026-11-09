package live.budeme.ptz;

/** A connection to a video switcher that keeps {@link Tally} up to date on its own thread. */
public abstract class SwitcherClient implements Runnable {
    protected final String ip;
    protected final Tally tally;
    protected volatile boolean stopped = false;
    private Thread thread;

    protected SwitcherClient(String ip, Tally tally) { this.ip = ip; this.tally = tally; }

    public void start() { thread = new Thread(this, getClass().getSimpleName()); thread.setDaemon(true); thread.start(); }
    public void stop() { stopped = true; closeSockets(); if (thread != null) thread.interrupt(); }
    protected abstract void closeSockets();

    protected static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    public static SwitcherClient create(String type, String ip, Tally tally) {
        switch (type) {
            case "atem": return new AtemClient(ip, tally);
            case "vmix": return new VmixClient(ip, tally);
            case "osee": return new OseeClient(ip, tally);
            default: return null;
        }
    }
}
