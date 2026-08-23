package dev.livefork.app.app

import dev.livefork.app.app.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 延迟节流器 —— 对应 Dart `DelayedThrottle`（lib/app/custom_throttle.dart）。
 * 节流语义：正在执行时不重复执行，保存最近一次调用；每个回调间隔 [eachDelayMilli] ms
 * 后才允许下一次调用，并且最后一次保存的方法会补执行。
 */
class DelayedThrottle(
    val eachDelayMilli: Long,
    private val onError: ((Throwable) -> Unit)? = null,
) {
    @Volatile
    private var isInvokingValue = false

    @Volatile
    private var storeFuncValue: (suspend () -> Unit)? = null

    private var timerJob: Job? = null
    private var generation = 0
    private val scope = CoroutineScope(SupervisorJob())

    val isInvoking: Boolean get() = isInvokingValue

    fun cancel() {
        synchronized(this) {
            generation += 1
            timerJob?.cancel()
            timerJob = null
            isInvokingValue = false
            storeFuncValue = null
        }
    }

    fun invoke(longCostFunc: suspend () -> Unit) {
        synchronized(this) {
            if (isInvokingValue) {
                storeFuncValue = longCostFunc
                return
            }
            storeFuncValue = null
            isInvokingValue = true
            val gen = generation
            scope.launch { runSafely(longCostFunc, gen) }
        }
    }

    private suspend fun runSafely(longCostFunc: suspend () -> Unit, gen: Int) {
        try {
            try {
                longCostFunc()
            } catch (error: Throwable) {
                val handler = onError
                if (handler == null) {
                    Log.e(error.toString())
                } else {
                    try {
                        handler(error)
                    } catch (handlerError: Throwable) {
                        Log.e(handlerError.toString())
                    }
                }
            }
        } finally {
            scheduleNext(gen)
        }
    }

    private fun scheduleNext(gen: Int) {
        synchronized(this) {
            if (gen != generation) return
            timerJob = scope.launch {
                delay(eachDelayMilli)
                var pending: (suspend () -> Unit)? = null
                synchronized(this@DelayedThrottle) {
                    if (gen != generation) return@launch
                    isInvokingValue = false
                    pending = storeFuncValue
                    storeFuncValue = null
                }
                if (pending != null) {
                    invoke(pending)
                }
            }
        }
    }
}