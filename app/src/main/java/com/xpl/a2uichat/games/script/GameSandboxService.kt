package com.xpl.a2uichat.games.script

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.*

internal object NativeScript {
    init { System.loadLibrary("gamejs") }
    external fun run(source: ByteArray, runner: ByteArray, request: ByteArray, budgetMs: Long): ByteArray
    external fun cancel()
}

/** Only this isolated UID loads native code. No app data, network permission or Java bridge. */
class GameSandboxService : Service() {
    private val worker = ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,ArrayBlockingQueue(16))
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val active = AtomicLong(-1)
    private val cancelled = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    private val runner by lazy { assets.open("script/runner.js").use { it.readBytes() } }
    private val binder = object : IGameSandbox.Stub() {
        override fun execute(id: Long, source: ByteArray, request: ByteArray, callback: IGameResult) {
            if (source.size > 65536 || request.size > 196608) { fail(id,callback,"IPC 大小超限"); return }
            try { worker.execute task@{
                if (cancelled.remove(id)) return@task
                active.set(id)
                var kill = watchdog.schedule({ if (active.get() == id) Process.killProcess(Process.myPid()) }, 2600,TimeUnit.MILLISECONDS)
                try {
                    val op = Json.parseToJsonElement(request.decodeToString()).jsonObject.word("op")
                    val budget = when (op) { "validate" -> 2000L; "bot" -> 1000L; else -> 200L }
                    kill.cancel(false)
                    kill = watchdog.schedule({ if (active.get() == id) Process.killProcess(Process.myPid()) }, budget+500,TimeUnit.MILLISECONDS)
                    val result = NativeScript.run(source,runner,request,budget)
                    if (!cancelled.remove(id)) callback.complete(id,result)
                } catch (_: Exception) { fail(id,callback,"隔离运行时失败") }
                finally { active.compareAndSet(id,-1); kill.cancel(false); cancelled.remove(id) }
            } } catch (_: java.util.concurrent.RejectedExecutionException) { fail(id,callback,"隔离队列已满") }
        }
        override fun cancel(id: Long) {
            if (cancelled.size < 32) cancelled.add(id)
            if (active.get() == id) NativeScript.cancel()
        }
        override fun shutdown() { Process.killProcess(Process.myPid()) }
    }
    private fun fail(id: Long, callback: IGameResult, message: String) {
        try { callback.complete(id,"{\"ok\":false,\"error\":\"$message\"}".toByteArray()) } catch (_: Exception) { }
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onDestroy() {
        worker.shutdownNow(); watchdog.shutdownNow(); super.onDestroy()
        // Interrupting a Java worker cannot stop native C work. Never leave an orphaned VM
        // after the final binding disappears; this service owns its entire isolated process.
        Process.killProcess(Process.myPid())
    }
}
