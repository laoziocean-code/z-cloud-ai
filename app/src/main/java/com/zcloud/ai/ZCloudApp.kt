package com.zcloud.ai

import android.app.Application
import com.zcloud.ai.data.SettingsStore
import com.zcloud.ai.ptp.HexLog
import com.zcloud.ai.ptp.NikonPtpDriver

/**
 * 全局单例宿主。
 * PTP 驱动必须是进程级单例——USB 通道被独占，多个驱动实例会互相踩。
 */
class ZCloudApp : Application() {

    lateinit var driver: NikonPtpDriver
        private set

    lateinit var store: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        driver = NikonPtpDriver(this)
        store = SettingsStore(this)
        // 一开始就把日志写盘：真机上只有 USB-C 一个口，接了电脑就没法接相机，
        // 必须先让 App 自己留证据，用户拔线后还能取到。
        HexLog.startFileLogging(this)
        HexLog.info("日志落盘已启动", HexLog.currentLogFile()?.absolutePath ?: "(不可用)")
    }

    companion object {
        lateinit var instance: ZCloudApp
            private set
    }
}
