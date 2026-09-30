package com.xpl.a2uichat.games.script

import android.app.Application
import android.content.*
import android.os.IBinder
import com.xpl.a2uichat.games.rules.RuleFault
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class GameApplication : Application() {
    val conversations by lazy { com.xpl.a2uichat.chat.FileConversationRepository(java.io.File(filesDir, "conversations.json")) }
    override fun onCreate() { super.onCreate(); ScriptSandbox.initialize(this) }
}

/** Serial, asynchronous Binder transport. Never exposes Context or BuildConfig to JS. */
class ScriptSandbox private constructor(private val context: Application) {
    private val mutex = Mutex()
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<ByteArray>>()
    @Volatile private var service: IGameSandbox? = null
    private var connection: ServiceConnection? = null
    private var connecting: CompletableDeferred<IGameSandbox>? = null
    @Volatile internal var lastServiceUid: Int = -1
        private set
    @Volatile internal var lastServicePid: Int = -1
        private set
    private val callback = object : IGameResult.Stub() {
        override fun complete(id: Long, response: ByteArray) {
            lastServiceUid = android.os.Binder.getCallingUid()
            lastServicePid = android.os.Binder.getCallingPid()
            pending.remove(id)?.let { if (response.size <= 262144) it.complete(response) else it.completeExceptionally(RuleFault("脚本 IPC 回复超限")) }
        }
    }
    private fun died(expected: IBinder? = null) {
        if (expected != null && service?.asBinder() != expected) return
        service = null
        val error = RuleFault("隔离进程已终止；原棋局已保留，请点击继续重试")
        pending.values.forEach { it.completeExceptionally(error) }; pending.clear()
        connecting?.completeExceptionally(error)
    }
    private suspend fun connect(): IGameSandbox = withContext(Dispatchers.Main.immediate) {
        service?.let { return@withContext it }
        connection?.let { runCatching { context.unbindService(it) } }
        val ready = CompletableDeferred<IGameSandbox>(); connecting = ready
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (connection !== this) return
                val remote = IGameSandbox.Stub.asInterface(binder)
                service = remote
                try { binder.linkToDeath({ died(binder) },0); ready.complete(remote) }
                catch (_: android.os.RemoteException) { died(binder) }
            }
            override fun onServiceDisconnected(name: ComponentName) { if (connection === this) died() }
            override fun onBindingDied(name: ComponentName) { if (connection === this) died() }
            override fun onNullBinding(name: ComponentName) { if (connection === this) died() }
        }
        connection = conn
        try {
            if (!context.bindService(Intent(context,GameSandboxService::class.java),conn,Context.BIND_AUTO_CREATE)) throw RuleFault("无法连接隔离进程")
            withTimeout(5000) { ready.await() }
        } catch (e: Exception) {
            runCatching { context.unbindService(conn) }; connection = null; service = null
            if (e is TimeoutCancellationException) throw RuleFault("隔离服务连接超时，请重试")
            throw e
        } finally { connecting = null }
    }
    suspend fun execute(program: ScriptProgram, op: String, state: JsonObject? = null, action: JsonObject? = null, seed: Int = 12345): ScriptSnapshot =
        withContext(Dispatchers.Default) { mutex.withLock {
            val remote = connect()
            val id = ids.incrementAndGet()
            val request = buildJsonObject {
                put("op",op); put("config",program.config); put("seed",seed)
                put("version",program.version)
                if (program.version == 2) {
                    put("players",program.json["players"]!!)
                    put("initialPlayer",program.json["initialPlayer"]!!)
                    put("turnPolicy",program.json["turnPolicy"]!!)
                }
                program.botPlayer?.let { put("botPlayer",it) }
                state?.let { put("state",it) }; action?.let { put("action",it) }
                if (op == "validate") put("tests",program.json["tests"] ?: JsonArray(emptyList()))
            }.toString().toByteArray()
            checkScript(request.size <= 196608, "请求超过 192 KiB")
            val result = CompletableDeferred<ByteArray>(); pending[id] = result
            try {
                remote.execute(id,program.source.toByteArray(),request,callback)
                val bytes = withTimeout(3500) { result.await() }
                ScriptSnapshot.parse(bytes, program.version)
            } catch (e: TimeoutCancellationException) {
                runCatching { remote.shutdown() }; died(); throw RuleFault("脚本执行超时；原棋局已保留")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { throw RuleFault(e.message?.take(300) ?: "脚本执行失败") }
            finally { pending.remove(id); if (!result.isCompleted) runCatching { remote.cancel(id) } }
        } }

    suspend fun validate(program: ScriptProgram) {
        execute(program,"validate")
        val a = execute(program,"init"); val b = execute(program,"init")
        checkScript(a == b, "相同种子初始化结果不一致")
    }
    companion object {
        @Volatile private var instance: ScriptSandbox? = null
        fun initialize(context: Application) { if (instance == null) synchronized(this) { if (instance == null) instance = ScriptSandbox(context) } }
        fun shared(): ScriptSandbox = instance ?: error("Application 未初始化脚本运行时")
    }
}
