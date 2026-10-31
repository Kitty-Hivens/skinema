package dev.hivens.skinema.android

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri

/**
 * The application context the Android player is built with.
 *
 * The shared API takes no context, so that a consumer's shared code opens a
 * player the same way on both platforms. The context arrives instead through
 * [AppContextProvider], which the library's manifest declares and the
 * platform starts with the application, before any of the consumer's code
 * runs. A consumer that removes the provider from its merged manifest calls
 * [install] itself.
 */
object AppContext {

    @Volatile
    private var context: Context? = null

    /** Records [context]'s application context. Idempotent; the first one stays. */
    fun install(context: Context) {
        if (this.context == null) this.context = context.applicationContext ?: context
    }

    internal fun get(): Context = context ?: throw IllegalStateException(
        "skinema has no Android context: its provider was removed from the manifest and AppContext.install was not called",
    )
}

/**
 * Hands the application context to [AppContext] at startup and does nothing
 * else: it answers no queries and stores no data.
 */
class AppContextProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let(AppContext::install)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
