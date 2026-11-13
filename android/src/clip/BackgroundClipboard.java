package clip;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import java.util.function.Consumer;
import rikka.shizuku.Shizuku;

final class BackgroundClipboard implements AutoCloseable {
    private final Shizuku.UserServiceArgs args;
    private final Consumer<Object> accept;
    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable reconnect = this::refresh;
    private boolean enabled, bound;
    private volatile IClipAgent active;
    private final Shizuku.OnBinderReceivedListener received = this::refresh;
    private final Shizuku.OnBinderDeadListener disconnected = () -> {
        bound = false;
        active = null;
        changed();
    };
    private final Shizuku.OnRequestPermissionResultListener permission = (code, result) -> refresh();
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            if (!enabled || !bound) return;
            IClipAgent agent = IClipAgent.Stub.asInterface(service);
            try {
                if (agent.watch(new IClipEvents.Stub() {
                    @Override public void onText(String text) { deliver(agent, text); }
                    @Override public void onImage(ParcelFileDescriptor image) { deliver(agent, image); }
                })) active = agent;
            } catch (RemoteException | RuntimeException ignored) { active = null; }
            changed();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            active = null;
            changed();
            if (enabled) {
                main.removeCallbacks(reconnect);
                main.postDelayed(reconnect, 1000);
            }
        }
    };

    BackgroundClipboard(Context context, Consumer<Object> accept, Runnable changed) {
        this.accept = accept;
        this.changed = changed;
        args = new Shizuku.UserServiceArgs(new ComponentName(context, ClipAgent.class))
                .processNameSuffix("clipboard_agent").daemon(false).version(2);
        Shizuku.addBinderReceivedListenerSticky(received);
        Shizuku.addBinderDeadListener(disconnected);
        Shizuku.addRequestPermissionResultListener(permission);
    }

    boolean ready() { return active != null; }

    void enable(boolean enabled) {
        this.enabled = enabled;
        refresh();
    }

    private void refresh() {
        if (!enabled || !granted()) {
            disconnect();
        } else if (!bound) {
            try {
                bound = true;
                Shizuku.bindUserService(args, connection);
            } catch (RuntimeException unavailable) { bound = false; }
        }
        changed();
    }

    static boolean granted() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException unavailable) { return false; }
    }

    private void deliver(IClipAgent agent, Object value) {
        if (active == agent) accept.accept(value);
        else if (value instanceof ParcelFileDescriptor) {
            try { ((ParcelFileDescriptor)value).close(); }
            catch (java.io.IOException ignored) { }
        }
    }

    private void disconnect() {
        active = null;
        if (!bound) return;
        bound = false;
        try { Shizuku.unbindUserService(args, connection, true); }
        catch (RuntimeException ignored) { }
    }

    private void changed() { changed.run(); }

    @Override public void close() {
        enabled = false;
        main.removeCallbacks(reconnect);
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(disconnected);
        Shizuku.removeRequestPermissionResultListener(permission);
        disconnect();
    }
}
