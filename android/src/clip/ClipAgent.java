package clip;

import android.content.ClipData;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

/** Shizuku launches this class in its authorized shell process. */
public final class ClipAgent extends IClipAgent.Stub {
    private static final String SHELL = "com.android.shell";
    private static final String LISTENER = "android.content.IOnPrimaryClipChangedListener";
    private final HandlerThread thread = new HandlerThread("clipboard-events");
    private final Handler worker;
    private Object clipboard;
    private Method getClip, removeListener;
    private Object[] callArgs, listenerArgs;
    private volatile IClipEvents events;
    private int owner = -1;
    private int user;
    private final Runnable read = this::readChanged;

    public ClipAgent() {
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    @Override public synchronized boolean watch(IClipEvents client) throws RemoteException {
        int uid = Binder.getCallingUid();
        if (client == null || (owner != -1 && uid != owner)) return false;
        if (events != null) return false;
        owner = uid;
        user = uid / 100000;
        long identity = Binder.clearCallingIdentity();
        try {
            Class<?> services = Class.forName("android.os.ServiceManager");
            IBinder service = (IBinder)services.getMethod("getService", String.class).invoke(null, "clipboard");
            Class<?> contract = Class.forName("android.content.IClipboard");
            clipboard = Class.forName("android.content.IClipboard$Stub").getMethod("asInterface", IBinder.class)
                    .invoke(null, service);
            Class<?> listenerType = Class.forName(LISTENER);
            Binder callback = new Binder() {
                @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                        throws RemoteException {
                    if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
                    data.enforceInterface(LISTENER);
                    worker.removeCallbacks(read);
                    worker.post(read);
                    return true;
                }
            };
            Object listener = Class.forName(LISTENER + "$Stub").getMethod("asInterface", IBinder.class)
                    .invoke(null, callback);
            Class<?>[] signature = findSignature(contract);
            Class<?>[] extended = new Class<?>[signature.length + 1];
            extended[0] = listenerType;
            System.arraycopy(signature, 0, extended, 1, signature.length);
            listenerArgs = new Object[callArgs.length + 1];
            listenerArgs[0] = listener;
            System.arraycopy(callArgs, 0, listenerArgs, 1, callArgs.length);
            removeListener = contract.getMethod("removePrimaryClipChangedListener", extended);
            contract.getMethod("addPrimaryClipChangedListener", extended).invoke(clipboard, listenerArgs);
            client.asBinder().linkToDeath(this::shutdown, 0);
            events = client;
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w("ClipAgent", "Clipboard listener unavailable", error);
            return false;
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    private Class<?>[] findSignature(Class<?> contract) throws NoSuchMethodException {
        Class<?>[][] variants = {{String.class, String.class, int.class, int.class},
                {String.class, String.class, int.class}, {String.class, int.class}};
        Object[][] arguments = {{SHELL, null, user, 0}, {SHELL, null, user}, {SHELL, user}};
        for (int i = 0; i < variants.length; i++) {
            try {
                getClip = contract.getMethod("getPrimaryClip", variants[i]);
                callArgs = arguments[i];
                return variants[i];
            } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException("getPrimaryClip");
    }

    private void readChanged() {
        IClipEvents target = events;
        if (target == null) return;
        try {
            ClipData clip = (ClipData)getClip.invoke(clipboard, callArgs);
            if (clip == null || clip.getItemCount() == 0 ||
                    "clip.bridge.received".contentEquals(String.valueOf(clip.getDescription().getLabel()))) return;
            ClipData.Item item = clip.getItemAt(0);
            Uri uri = item.getUri();
            if (uri != null && "content".equals(uri.getScheme()) && clip.getDescription().hasMimeType("image/*")) {
                sendImage(target, uri);
            } else if (item.getText() != null && item.getText().length() <= Wire.TEXT_LIMIT / 2) {
                target.onText(item.getText().toString());
            }
        } catch (Exception error) { Log.w("ClipAgent", "Clipboard item unavailable", error); }
    }

    private void sendImage(IClipEvents target, Uri uri) throws Exception {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        java.lang.Process process = null;
        try (ParcelFileDescriptor readEnd = pipe[0];
             OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
            process = new ProcessBuilder("/system/bin/content", "read", "--user",
                    Integer.toString(user), "--uri", uri.toString())
                    .redirectError(new File("/dev/null")).start();
            target.onImage(readEnd);
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[16384];
                int total = 0;
                for (int size; (size = input.read(buffer)) != -1;) {
                    total += size;
                    output.write(buffer, 0, size);
                    if (total > Wire.IMAGE_LIMIT) return;
                }
            }
        } finally { if (process != null) process.destroy(); }
    }

    @Override public void destroy() {
        int uid = Binder.getCallingUid();
        if (uid == owner || uid == android.os.Process.myUid() || uid == 0 || uid == 2000) shutdown();
    }

    private void shutdown() {
        events = null;
        long identity = Binder.clearCallingIdentity();
        try {
            if (removeListener != null) removeListener.invoke(clipboard, listenerArgs);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        } finally {
            Binder.restoreCallingIdentity(identity);
            System.exit(0);
        }
    }
}
