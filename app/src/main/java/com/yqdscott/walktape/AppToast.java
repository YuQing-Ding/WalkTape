package com.yqdscott.walktape;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

/**
 * Every toast this app shows, routed away from the AppCompat view inflater.
 *
 * <p>{@link Toast#makeText} inflates the framework's own {@code transient_notification} layout with
 * whatever {@code LayoutInflater} the given context owns. An Activity's inflater carries AppCompat's
 * factory, so the framework layout's TextView is swapped for a {@code MaterialTextView}, and
 * {@code AppCompatTextHelper} then reads {@code android:fontFamily} out of it through
 * {@code TintTypedArray}. On Android 7.x that string is resolved against the layout's own pool
 * rather than the framework resource table, which throws
 * {@code ArrayIndexOutOfBoundsException: length=16; index=990} and takes down the process — the app
 * dies while trying to tell the listener that a track would not decode, so the message never
 * arrives and the decode failure looks like the crash.</p>
 *
 * <p>The application context has no AppCompat factory installed, so the plain framework TextView is
 * inflated and the helper never runs. The message is logged as well as shown: a toast is not a
 * reliable place to read an error from, and some of these carry the only description of why a file
 * would not play.</p>
 */
final class AppToast {

    private static final String TAG = "WalkTapeToast";

    private AppToast() {
    }

    static void show(Context context, CharSequence message, int duration) {
        if (context == null || message == null) {
            return;
        }
        Log.i(TAG, message.toString());
        try {
            Toast.makeText(context.getApplicationContext(), message, duration).show();
        } catch (Throwable toastUnavailable) {
            // A toast is never worth a crash. The message is already in the log above.
            Log.w(TAG, "toast could not be shown", toastUnavailable);
        }
    }
}
