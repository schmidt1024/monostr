package com.monostr.app.translate

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import com.monostr.nostr.PerfTrace
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.davidv.translator.ErrorType
import dev.davidv.translator.ITranslationCallback
import dev.davidv.translator.ITranslationService
import dev.davidv.translator.TranslationError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** One translation request to the device translator; [DeviceTranslator] keeps them one at a time. */
fun interface DeviceChannel {
    suspend fun call(text: String, from: String, to: String): DeviceReply

    /** Drop any binding now (memory trim); the next [call] binds again. */
    fun release() {}
}

sealed interface DeviceReply {
    data class Text(val text: String) : DeviceReply
    /** `DETECTED_BUT_UNAVAILABLE`: no language pack for the pair. */
    data object PackMissing : DeviceReply
    /** The service could not be bound (app missing, disabled or refusing). */
    data object Unbound : DeviceReply
    /** `UNEXPECTED`, a remote failure or the service died during the call. */
    data class Error(val message: String?) : DeviceReply
}

/**
 * On-device translation through the Offline Translator app (release 0.42.0, backlog 89). Never a network request and
 * never a fallback to a server: what the app cannot do fails. Each HTML text goes through [DevicePlain] (an untranslatable
 * text fails without being sent), one request per text, strictly one at a time ([mutex]: the upstream model is
 * single-threaded), each bounded by the caller's timeout or [ArticleTranslation.timeoutMs] of its length. The first
 * failing text stops the rest.
 */
@Singleton
class DeviceTranslator internal constructor(
    private val channel: DeviceChannel,
    private val installed: () -> Boolean = { false },
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        BinderChannel(context.applicationContext, CoroutineScope(SupervisorJob() + Dispatchers.Default)),
        { isInstalled(context.applicationContext) },
    )

    private val mutex = Mutex()

    /** Whether Offline Translator offers its service. A package query: on demand only (Settings, consent), never on the feed path. */
    fun isInstalled(): Boolean = installed()

    /** Unbinds now and stops the idle timer (process trim); a later [translate] binds again. */
    fun release() = channel.release()

    suspend fun translate(texts: List<String>, source: String, target: String, timeoutMs: Long?): TranslateResult {
        // the source is always ours: an empty one would make upstream guess the language
        if (source.isBlank()) return TranslateResult.Failed
        return mutex.withLock {
            val out = ArrayList<String>(texts.size)
            for (html in texts) {
                val plain = DevicePlain.toPlain(html) ?: return@withLock TranslateResult.Failed
                val reply = if (plain.text.isBlank()) {
                    DeviceReply.Text(plain.text)
                } else {
                    withTimeoutOrNull(timeoutMs ?: ArticleTranslation.timeoutMs(plain.text.length)) {
                        channel.call(plain.text, source, target)
                    }
                }
                when (reply) {
                    is DeviceReply.Text -> out += DevicePlain.fromPlain(reply.text, plain) ?: return@withLock TranslateResult.Failed
                    DeviceReply.PackMissing -> return@withLock TranslateResult.PackMissing
                    DeviceReply.Unbound -> return@withLock TranslateResult.AppMissing
                    is DeviceReply.Error, null -> return@withLock TranslateResult.Failed
                }
            }
            TranslateResult.Ok(out)
        }
    }

    companion object {
        const val PKG = "dev.davidv.translator"
        const val ACTION = "dev.davidv.translator.ITranslationService"
        const val IDLE_UNBIND_MS = 60_000L

        internal fun serviceIntent(): Intent = Intent(ACTION).setPackage(PKG)

        private fun isInstalled(context: Context): Boolean =
            runCatching { context.packageManager.resolveService(serviceIntent(), 0) != null }.getOrDefault(false)
    }
}

/** Runs [onIdle] once, [delayMs] after the last [touch]; [cancel] while busy. */
internal class IdleTimer(private val scope: CoroutineScope, private val delayMs: Long, private val onIdle: suspend () -> Unit) {
    private var job: Job? = null

    @Synchronized fun touch() {
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            onIdle()
        }
    }

    @Synchronized fun cancel() {
        job?.cancel()
        job = null
    }
}

/**
 * The real [DeviceChannel]: binds Offline Translator's exported service on the first call, keeps the binding while used
 * and unbinds [DeviceTranslator.IDLE_UNBIND_MS] after the last call or on [release]. The binder's death (or a disconnect)
 * fails the running call with [DeviceReply.Error] and drops the binding; the next call binds afresh. Callbacks of an
 * earlier binding (its death recipient, its connection) are ignored once it is no longer the current one.
 */
private class BinderChannel(private val context: Context, private val scope: CoroutineScope) : DeviceChannel {
    private val lock = Mutex()
    private val idle = IdleTimer(scope, DeviceTranslator.IDLE_UNBIND_MS) { lock.withLock { unbind() } }

    // guarded by this (the connection callbacks arrive on the main thread)
    private var connection: ServiceConnection? = null
    private var binder: IBinder? = null
    private var death: IBinder.DeathRecipient? = null
    private var service: ITranslationService? = null
    private var pending: ((DeviceReply) -> Unit)? = null

    /** A bind's outcome: the service, or the reply the call gets instead. */
    private sealed interface Bound {
        class Ok(val service: ITranslationService) : Bound
        class Failed(val reply: DeviceReply) : Bound
    }

    override suspend fun call(text: String, from: String, to: String): DeviceReply = lock.withLock {
        idle.cancel()
        try {
            val svc = synchronized(this) { service } ?: when (val b = bind()) {
                is Bound.Ok -> b.service
                is Bound.Failed -> return@withLock b.reply
            }
            val t0 = SystemClock.elapsedRealtime()
            val reply = request(svc, text, from, to)
            PerfTrace.mark("translate device call chars=${text.length} ms=${SystemClock.elapsedRealtime() - t0}")
            reply
        } finally {
            idle.touch()
        }
    }

    override fun release() {
        idle.cancel()
        // after a running call (it touches the timer again in its finally, which this cancels)
        scope.launch {
            lock.withLock {
                idle.cancel()
                unbind()
            }
        }
    }

    private suspend fun bind(): Bound {
        val t0 = SystemClock.elapsedRealtime()
        return suspendCancellableCoroutine { cont ->
            val once = AtomicBoolean()
            fun done(b: Bound) {
                if (once.compareAndSet(false, true)) cont.resume(b)
            }
            val conn = object : ServiceConnection {
                private fun current() = synchronized(this@BinderChannel) { connection === this }

                override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
                    if (b == null) {
                        unbindIf(this)
                        done(Bound.Failed(DeviceReply.Unbound))
                        return
                    }
                    val svc = ITranslationService.Stub.asInterface(b)
                    val recipient = IBinder.DeathRecipient { droppedIf(b, DeviceReply.Error("Offline Translator died")) }
                    val linked = try {
                        b.linkToDeath(recipient, 0); true
                    } catch (e: RemoteException) {
                        false // died before we could watch it
                    }
                    if (!linked) {
                        unbindIf(this)
                        done(Bound.Failed(DeviceReply.Error("Offline Translator died while binding")))
                        return
                    }
                    val isCurrent = synchronized(this@BinderChannel) {
                        (connection === this).also { if (it) { binder = b; death = recipient; service = svc } }
                    }
                    if (!isCurrent) { // unbound meanwhile (the caller gave up)
                        runCatching { b.unlinkToDeath(recipient, 0) }
                        done(Bound.Failed(DeviceReply.Error("binding dropped")))
                        return
                    }
                    PerfTrace.mark("translate device bind ${SystemClock.elapsedRealtime() - t0}")
                    done(Bound.Ok(svc))
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    if (current()) dropped(DeviceReply.Error("Offline Translator disconnected"))
                }

                override fun onBindingDied(name: ComponentName?) {
                    if (current()) dropped(DeviceReply.Error("Offline Translator binding died"))
                    done(Bound.Failed(DeviceReply.Error("Offline Translator binding died")))
                }

                override fun onNullBinding(name: ComponentName?) {
                    unbindIf(this)
                    done(Bound.Failed(DeviceReply.Unbound))
                }
            }
            synchronized(this) { connection = conn }
            val bound = try {
                context.bindService(DeviceTranslator.serviceIntent(), conn, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) {
                false
            }
            if (!bound) {
                unbindIf(conn) // the docs ask for an unbind even when bindService returned false
                done(Bound.Failed(DeviceReply.Unbound))
            }
            cont.invokeOnCancellation { unbindIf(conn) } // timed out while binding
        }
    }

    private suspend fun request(svc: ITranslationService, text: String, from: String, to: String): DeviceReply =
        suspendCancellableCoroutine { cont ->
            val once = AtomicBoolean()
            lateinit var finish: (DeviceReply) -> Unit
            finish = { r ->
                if (once.compareAndSet(false, true)) {
                    synchronized(this) { if (pending === finish) pending = null }
                    cont.resume(r)
                }
            }
            synchronized(this) { pending = finish }
            cont.invokeOnCancellation { synchronized(this) { if (pending === finish) pending = null } }
            val callback = object : ITranslationCallback.Stub() {
                override fun onTranslationResult(translatedText: String?) =
                    finish(translatedText?.let { DeviceReply.Text(it) } ?: DeviceReply.Error("no text"))

                override fun onTranslationError(error: TranslationError?) = finish(
                    if (error?.type == ErrorType.DETECTED_BUT_UNAVAILABLE) DeviceReply.PackMissing else DeviceReply.Error(error?.message),
                )
            }
            try {
                svc.translate(text, from, to, callback)
            } catch (e: RemoteException) { // DeadObjectException: the service is gone
                dropped(DeviceReply.Error(e.message))
            } catch (e: RuntimeException) { // an exception the service threw back over the binder
                finish(DeviceReply.Error(e.message))
            }
        }

    /** [b] died: act only while it is still the current binder (a late death of an earlier one is ignored). */
    private fun droppedIf(b: IBinder, reply: DeviceReply) {
        if (synchronized(this) { binder === b }) dropped(reply)
    }

    /** The service is gone: fail the running call and forget the binding, so the next call binds afresh. */
    private fun dropped(reply: DeviceReply) {
        val p = synchronized(this) { pending.also { pending = null } }
        unbind()
        p?.invoke(reply)
    }

    /** Unbinds [conn] if it is still the current connection (an earlier one was unbound when it stopped being current). */
    private fun unbindIf(conn: ServiceConnection) {
        if (synchronized(this) { connection === conn }) unbind()
    }

    private fun unbind() {
        val (conn, b, d) = synchronized(this) {
            val c = Triple(connection, binder, death)
            connection = null; binder = null; death = null; service = null
            c
        }
        if (b != null && d != null) runCatching { b.unlinkToDeath(d, 0) }
        if (conn != null) runCatching { context.unbindService(conn) }
    }
}
