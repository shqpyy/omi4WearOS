package com.omi4wos.mobile.service

import android.content.Context
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.omi4wos.shared.DataLayerPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * 通话状态监听：手机接通/拨出电话时，命令手表暂停录音；挂断后恢复。
 *
 * 为何要做：手表整日麦克风录音，通话那段时间手表只会录到用户说话的环境声 +
 * 隐约电话声——既重复又劣质。通话内容已由系统通话录音完整录到并上传，手表这段
 * 是无用冗余。由此在通话期间主动暂停手表录音，结束再恢复。
 *
 * 行为：
 *  - [TelephonyManager.CALL_STATE_OFFHOOK]（接通/拨出）：若手表当前在录 -> 发 stop，
 *    并标记"需要恢复"。来电响铃(RINGING)不触发，符合"接通才暂停"。
 *  - [TelephonyManager.CALL_STATE_IDLE]（挂断/空闲）：若之前在录音 -> 发 start 恢复。
 *  - 通过 watchRecordingEnabled 判断手表是否在录，避免把用户手动关闭的手表录音误开。
 *
 * 注意：需要 READ_PHONE_STATE 运行时权限；无权限时 listen 抛 SecurityException，
 * 已在 register() 内就地拦截（功能静默不生效）。
 */
class CallStateListener(context: Context) : PhoneStateListener() {

    private val app = context.applicationContext
    private val telephony = app.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val messageClient: MessageClient = Wearable.getMessageClient(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var resumeNeeded = false

    fun register() {
        runCatching { telephony.listen(this, PhoneStateListener.LISTEN_CALL_STATE) }
            .onSuccess {
                Log.i(TAG, "call state listener registered")
                AppLog.i(TAG, "通话监听已注册")
            }
            .onFailure {
                Log.w(TAG, "register listener failed (无 READ_PHONE_STATE 权限?)", it)
                AppLog.w(TAG, "通话监听注册失败(可能缺权限): ${it.message}")
            }
    }

    fun unregister() {
        runCatching { telephony.listen(this, PhoneStateListener.LISTEN_NONE) }
        Log.i(TAG, "call state listener unregistered")
    }

    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                // 接通/拨出：手表在录才暂停，避免误停未录的场景
                if (AudioReceiverService.watchRecordingEnabled.value) {
                    resumeNeeded = true
                    sendCommand(DataLayerPaths.CMD_STOP_RECORDING)
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                // 挂断/空闲：若这次通话确实暂停过，才恢复
                if (resumeNeeded) {
                    resumeNeeded = false
                    sendCommand(DataLayerPaths.CMD_START_RECORDING)
                }
            }
            else -> Unit // RINGING 等状态不处理
        }
    }

    private fun sendCommand(command: String) {
        scope.launch {
            try {
                val nodes = Wearable.getNodeClient(app).connectedNodes.await()
                val watch = nodes.firstOrNull()
                    ?: run { Log.w(TAG, "No watch connected, cannot send: $command"); return@launch }
                messageClient.sendMessage(
                    watch.id,
                    DataLayerPaths.AUDIO_CONTROL_PATH,
                    command.toByteArray(Charsets.UTF_8)
                ).await()
                Log.i(TAG, "Sent '$command' to ${watch.displayName}")
                AppLog.i(TAG, "通话暂停逻辑下发 '$command' 给 ${watch.displayName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send '$command'", e)
                AppLog.e(TAG, "下发 '$command' 失败", e)
            }
        }
    }

    fun destroy() {
        unregister()
        scope.cancel()
    }

    companion object {
        private const val TAG = "CallStateListener"
    }
}