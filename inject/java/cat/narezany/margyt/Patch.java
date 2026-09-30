package cat.narezany.margyt;

import android.app.Activity;
import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexClassLoader;

/**
 * Fixes that arrive without an apk.
 *
 * A release is three hundred and eighty megabytes, almost all of it TikTok's
 * own assets, and installing one takes a minute of somebody's evening. Most of
 * what goes wrong is in the mod's own code, which is under a megabyte. So that
 * part can travel on its own: a small signed file, downloaded in the
 * background, loaded at the next start.
 *
 * A patch is a zip holding `manifest.json`, `classes.dex` and `signature`. The
 * signature is over the other two, made with a key that lives on one machine
 * and never on the server. Anything else is refused: the server answers over
 * plain http, so a file that becomes running code inside somebody's TikTok
 * cannot be trusted for arriving from the right address.
 *
 * What a patch can do is bounded by `Mend`: the hooks into TikTok are rewritten
 * call sites inside the installed apk, and nothing downloaded afterwards can
 * add one. A patch acts where the mod asks it to, and it can reach everything
 * the mod makes public.
 *
 * A patch that throws while starting is deleted rather than kept: the marker
 * written before it runs is still there on the next start, and that is taken
 * as the patch having cost an app rather than fixed one.
 */
public final class Patch {

    private Patch() {}

    /** Where a patch lives once it has been checked. */
    private static final String HOME = "margyt/patch";

    /** As much of one as is ever sensible. */
    private static final int MOST = 8 * 1024 * 1024;

    private static volatile Mend mending;
    private static volatile String version = "";
    private static volatile boolean started;

    /** Whether patches are wanted at all. On unless somebody says otherwise. */
    public static boolean wanted(Context context) {
        try {
            return context.getSharedPreferences(Margy.PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ON, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void setWanted(Context context, boolean on) {
        try {
            context.getSharedPreferences(Margy.PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_ON, on).apply();
        } catch (Throwable ignored) {
        }
        if (!on) drop(context);
    }

    static final String KEY_ON = "patches_on";

    /** Whether one is waiting for the next start. */
    public static boolean onShelf(Context context) {
        try {
            return new File(new File(context.getFilesDir(), HOME), "classes.dex").isFile();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------ the loading

    /** What is running, if anything, for the settings to show. */
    public static String running() {
        return mending == null ? "" : version;
    }

    /**
     * Load the patch on the shelf, if there is one and it did not kill the app.
     *
     * Called before TikTok has drawn anything, so a patch can stand in front
     * of the first frame rather than a second later.
     */
    public static synchronized void start(Context context) {
        if (started) return;
        started = true;
        if (!wanted(context)) {
            drop(context);
            return;
        }
        try {
            File home = new File(context.getFilesDir(), HOME);
            File dex = new File(home, "classes.dex");
            File trying = new File(home, "trying");
            if (!dex.isFile()) return;

            if (trying.exists()) {
                // it was being started when the app last went down
                drop(context);
                Diary.note("patch: dropped, it did not survive its own start");
                return;
            }

            JSONObject said = new JSONObject(text(new File(home, "manifest.json")));
            if (!Version.MOD.equals(said.optString("mod", ""))) {
                drop(context);
                Diary.note("patch: dropped, it was built for " + said.optString("mod", "?"));
                return;
            }

            new FileOutputStream(trying).close();
            ClassLoader loader = new DexClassLoader(
                    dex.getAbsolutePath(), context.getCodeCacheDir().getAbsolutePath(),
                    null, Patch.class.getClassLoader());
            String entry = said.optString("entry", "");
            if (!entry.startsWith("cat.narezany.margyt.patch.")) {
                drop(context);
                Diary.note("patch: dropped, the entry is not where it should be");
                return;
            }
            Object made = loader.loadClass(entry).newInstance();
            if (!(made instanceof Mend)) {
                drop(context);
                Diary.note("patch: dropped, that is not a patch");
                return;
            }

            Mend mend = (Mend) made;
            mend.started(context);
            mending = mend;
            version = said.optString("version", "?");
            trying.delete();
            Diary.note("patch: " + version + " running");
        } catch (Throwable error) {
            Diary.note("patch: " + error);
            try {
                drop(context);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Take the patch off the shelf. */
    public static void drop(Context context) {
        try {
            File home = new File(context.getFilesDir(), HOME);
            File[] all = home.listFiles();
            if (all != null) {
                for (File one : all) one.delete();
            }
            home.delete();
        } catch (Throwable ignored) {
        }
        mending = null;
        version = "";
    }

    // ------------------------------------------------------------- the hooks

    public static void resumed(Activity activity) {
        Mend mend = mending;
        if (mend == null) return;
        try {
            mend.resumed(activity);
        } catch (Throwable error) {
            broke(error);
        }
    }

    /** A name on its way to a view, or null to leave it alone. */
    public static String name(String uid, String plain) {
        Mend mend = mending;
        if (mend == null) return null;
        try {
            return mend.name(uid, plain);
        } catch (Throwable error) {
            broke(error);
            return null;
        }
    }

    /** Anything else the mod thinks a patch might want a say in. */
    public static Object ask(String what, Object... with) {
        Mend mend = mending;
        if (mend == null) return null;
        try {
            return mend.ask(what, with);
        } catch (Throwable error) {
            broke(error);
            return null;
        }
    }

    /** A patch that throws is not asked again this run. */
    private static void broke(Throwable error) {
        mending = null;
        Diary.note("patch: stopped, " + error);
    }

    // ---------------------------------------------------------- the fetching

    public interface Said {
        void said(boolean got, String trouble);
    }

    /**
     * Ask the server whether there is a patch for this build, and take it.
     *
     * The answer names a version, an address and the length the file should
     * be. Nothing is believed until the signature is checked.
     */
    public static void check(final Context context, final Said then) {
        if (!wanted(context)) {
            answer(then, false, "");
            return;
        }
        Net.away("patch: check", new Runnable() {
            @Override
            public void run() {
                boolean got = false;
                String trouble = "";
                try {
                    Net.Said asked = Net.talk(Badges.SERVER + "/patches",
                            new JSONObject().put("mod", Version.MOD)
                                    .put("tiktok", Version.TIKTOK)
                                    .put("have", running()).toString());
                    if (!asked.ok()) {
                        answer(then, false, "");
                        return;
                    }
                    JSONObject told = new JSONObject(asked.body);
                    String where = told.optString("url", "");
                    String which = told.optString("version", "");
                    if (where.length() == 0) {
                        // the server has none for this build: whatever is on
                        // the shelf was taken back, so it goes. Without this,
                        // recalling a patch means publishing another one
                        if (onShelf(context)) {
                            drop(context);
                            Diary.note("patch: taken back, the server offers none");
                        }
                        answer(then, false, "");
                        return;
                    }
                    if (which.equals(running())) {
                        answer(then, false, "");
                        return;
                    }
                    got = take(context, Badges.SERVER + where, which);
                    if (!got) trouble = Text.PATCH_REFUSED;
                } catch (Throwable error) {
                    Diary.note("patch: " + error);
                    trouble = Text.PATCH_REFUSED;
                }
                answer(then, got, trouble);
            }
        });
    }

    /** Fetch one, check it, and put it on the shelf for the next start. */
    private static boolean take(Context context, String where, String which) {
        File home = new File(context.getFilesDir(), HOME);
        File box = new File(context.getCacheDir(), "patch.margyupd");
        try {
            byte[] raw = Net.bytes(where);
            if (raw == null || raw.length == 0 || raw.length > MOST) return false;
            try (FileOutputStream out = new FileOutputStream(box)) {
                out.write(raw);
            }

            byte[] manifest = null, dex = null, signature = null;
            try (ZipFile zip = new ZipFile(box)) {
                manifest = read(zip, "manifest.json");
                dex = read(zip, "classes.dex");
                signature = read(zip, "signature");
            }
            if (manifest == null || dex == null || signature == null) return false;
            if (!signed(manifest, dex, signature)) {
                Diary.note("patch: the signature is not ours");
                return false;
            }

            JSONObject said = new JSONObject(new String(manifest, "UTF-8"));
            if (!Version.MOD.equals(said.optString("mod", ""))) {
                Diary.note("patch: built for another version of the mod");
                return false;
            }
            if (!which.equals(said.optString("version", ""))) return false;

            drop(context);
            home.mkdirs();
            File file = new File(home, "classes.dex");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(dex);
            }
            // Android will not load a dex it can still write to
            file.setReadOnly();
            try (FileOutputStream out = new FileOutputStream(new File(home, "manifest.json"))) {
                out.write(manifest);
            }
            Diary.note("patch: " + which + " is on the shelf for the next start");
            return true;
        } catch (Throwable error) {
            Diary.note("patch: " + error);
            return false;
        } finally {
            box.delete();
        }
    }

    /**
     * Whether this was signed by the one key that may sign a patch.
     *
     * The public half is built into the apk; the private half is on one
     * machine and is not on the server, so a server somebody else is
     * pretending to be has nothing to sign with.
     */
    private static boolean signed(byte[] manifest, byte[] dex, byte[] signature) {
        try {
            if (Patchkey.KEY.length() == 0) return false;
            byte[] raw = android.util.Base64.decode(Patchkey.KEY, android.util.Base64.DEFAULT);
            PublicKey key = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(raw));
            Signature check = Signature.getInstance("SHA256withRSA");
            check.initVerify(key);
            check.update(manifest);
            check.update(dex);
            return check.verify(signature);
        } catch (Throwable error) {
            Diary.note("patch: " + error);
            return false;
        }
    }

    private static byte[] read(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.getSize() > MOST) return null;
        InputStream in = zip.getInputStream(entry);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int got;
            while ((got = in.read(buffer)) > 0) {
                out.write(buffer, 0, got);
                if (out.size() > MOST) return null;
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static String text(File file) throws Exception {
        byte[] raw = Net.read(file);
        return raw == null ? "{}" : new String(raw, "UTF-8");
    }

    private static void answer(final Said then, final boolean got, final String trouble) {
        if (then == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                then.said(got, trouble);
            }
        });
    }
}
