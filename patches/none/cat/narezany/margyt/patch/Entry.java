package cat.narezany.margyt.patch;

import android.app.Activity;
import android.content.Context;

import cat.narezany.margyt.Mend;

/**
 * A patch that does nothing, for taking another one back.
 *
 * A phone keeps the patch it has until a newer one replaces it, so deleting a
 * file from the server does not reach the phones that already took it. Until
 * the mod learns to drop a patch the server no longer offers, this is how one
 * is recalled: publish nothing, in patch form.
 */
public final class Entry implements Mend {

    @Override
    public void started(Context context) {
    }

    @Override
    public void resumed(Activity activity) {
    }

    @Override
    public String name(String uid, String plain) {
        return null;
    }

    @Override
    public Object ask(String what, Object[] with) {
        return null;
    }
}
