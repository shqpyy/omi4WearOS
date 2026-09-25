package com.omi4wos.mobile.storage

import android.content.Context
import com.omi4wos.mobile.omi.OmiConfig

/**
 * 上传统一接口。三种实现：
 * - [LocalFileUploader]  写本地文件 + segments.jsonl
 * - [HttpUploader]        multipart POST 到自建服务器
 * - [S3Uploader]         S3 兼容对象存储（AWS SDK）
 *
 * 调用方（[com.omi4wos.mobile.service.AudioUploadService]）只看本接口，
 * 不关心具体后端实现，由 [OmiConfig.storageMethod] 决定走哪个。
 */
interface StorageUploader {

    /**
     * 上传一段音频。
     *
     * @param audioData   音频字节数据
     * @param uploadName  目标文件名（如 recording_fs320_12345.bin / call_20260914_153000.amr）
     * @param segmentId   watch 端 segment id（用于元数据，可空）
     * @param syncId      batch sync 会话 id（用于元数据，可空）
     * @param startTimeMs epoch ms
     * @param endTimeMs   epoch ms
     * @param confidence  VAD 置信度（watch 录音用，通话录音可传 0）
     * @param batteryLevel watch 电量（watch 录音用，通话录音传 -1）
     * @param source      来源标识： "watch" 或 "phone"
     * @return 上传成功 true / 失败 false
     */
    suspend fun upload(
        audioData: ByteArray,
        uploadName: String,
        segmentId: String,
        syncId: String,
        startTimeMs: Long,
        endTimeMs: Long,
        confidence: Float,
        batteryLevel: Int,
        source: String
    ): Boolean

    /**
     * 上传一批输入文本事件。
     *
     * 服务端契约（audio_server.py `/input-text`）：
     *   POST application/json
     *   body: {"events": [ {device_id, timestamp, package_name, text, app_name}, ... ]}
     *   events 必须为**非空数组**，否则 400；`X-API-Key` 不对则 401。
     *   服务端按 (device_id, timestamp, package_name, text) 四元组去重（重试幂等）。
     *
     * 与 [upload] 的差异：
     * - 音频走 multipart/form-data，文本走 application/json
     * - 文本上传只在选择 HTTP 后端时真正联网；其他后端（本地/S3）落盘留档即可
     *
     * @param eventsJson 一批事件的 JSON 对象字符串列表，每项形如
     *                   {"device_id":"...","timestamp":"...","package_name":"...",
     *                    "text":"...","app_name":"..."}
     *                   （由 [com.omi4wos.mobile.service.InputTextEvent.toWireJson] 生成）
     * @param fileName   来源文件名（如 input_text_events_2026-09-24.jsonl），用于日志与归组
     * @return 上传成功 true / 失败 false
     */
    suspend fun uploadInputText(eventsJson: List<String>, fileName: String): Boolean

    /**
     * 测试连通性（仅用于 Settings 页 Test 按钮）。
     * @return Pair(success, message)
     */
    suspend fun testConnection(): Pair<Boolean, String>

    companion object {
        /**
         * 工厂方法：根据 [OmiConfig.Config.storageMethod] 创建对应实现。
         * 每次调用会从 DataStore 重新读取配置，保证拿到最新值。
         */
        suspend fun create(context: Context): StorageUploader {
            val omiConfig = OmiConfig(context)
            val config = omiConfig.getConfig()
            return when (config.storageMethod) {
                OmiConfig.StorageMethod.LOCAL_FILE -> LocalFileUploader(context, config.localFile)
                OmiConfig.StorageMethod.HTTP       -> HttpUploader(config.http, context)
                OmiConfig.StorageMethod.S3         -> S3Uploader(context, config.s3)
            }
        }

        /**
         * 同步工厂：从 filesDir/speech_audio/ 读取已有的 bin 文件并重新上传。
         * 给 [com.omi4wos.mobile.service.UploadRetryRunner] 用。
         * 实际等价于 [create], 因为 HttpUploader 内部会从中转目录读 bin 文件。
         */
        suspend fun createFromCache(context: Context): StorageUploader = create(context)
    }
}
