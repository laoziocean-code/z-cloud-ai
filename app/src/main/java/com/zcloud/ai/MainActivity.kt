package com.zcloud.ai

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zcloud.ai.ptp.HexLog
import com.zcloud.ai.ui.AppRoot
import com.zcloud.ai.ui.MainViewModel
import com.zcloud.ai.ui.ZCloudTheme

class MainActivity : ComponentActivity() {

    private var pendingDevice: UsbDevice? = null

    /** 主线程 Handler：用于 USB 授权兜底轮询与延迟回调。 */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            onImagePicked?.invoke(uri)
        }
    }

    private val pickNcp = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onNcpPicked?.invoke(uri)
        }
    }

    companion object {
        var onImagePicked: ((android.net.Uri) -> Unit)? = null
        var onNcpPicked: ((android.net.Uri) -> Unit)? = null
        const val ACTION_USB_PERMISSION = "com.zcloud.ai.USB_PERMISSION"
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            HexLog.info("USB 权限", if (granted) "已授权 ${dev?.productName ?: ""}" else "用户拒绝")
            if (granted) {
                // 部分 ROM 在 FLAG_IMMUTABLE 下不会回填 EXTRA_DEVICE，
                // 这时用请求时缓存的 pendingDevice 兜底。
                val target = dev ?: pendingDevice
                pendingDevice = null
                if (target != null) viewModelRef?.connectDevice(target)
            }
        }
    }

    private var viewModelRef: MainViewModel? = null

    /**
     * 热插拔：用户插上/拔掉 Z30 时系统会发广播。
     * 插上 → 若还没授权就先请求，已授权直接连；拔掉 → 清状态。
     */
    private val usbHotplugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    HexLog.info("USB 热插", "检测到设备接入 ${dev?.productName ?: ""} VID=0x%04X".format(dev?.vendorId ?: 0))
                    val vm = viewModelRef
                    if (dev != null && dev.vendorId == 1200) {
                        if (vm == null) {
                            pendingDevice = dev
                        } else if (vm.hasPermission(dev)) {
                            vm.connectDevice(dev)
                        } else {
                            requestUsbPermission(dev)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    HexLog.warn("USB 热插", "设备已拔出，连接状态复位")
                    viewModelRef?.onDeviceDetached()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as ZCloudApp
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        // USB 授权广播由系统进程发出；用 NOT_EXPORTED 在部分 ROM 上会被直接丢弃，
        // 表现就是「点了授权，App 像没反应」。这里用 EXPORTED + setPackage 收敛来源。
        ContextCompat.registerReceiver(
            this, usbPermissionReceiver, filter, ContextCompat.RECEIVER_EXPORTED
        )

        // 热插拔监听：这类广播必须带 EXPORTED，否则收不到系统事件
        val hotplugFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            this, usbHotplugReceiver, hotplugFilter, ContextCompat.RECEIVER_EXPORTED
        )

        setContent {
            ZCloudTheme {
                val vm: MainViewModel = viewModel()
                viewModelRef = vm

                LaunchedEffect(Unit) {
                    onImagePicked = { uri -> vm.pickReference(uri) }
                    onNcpPicked = { uri -> vm.importNcp(uri) }
                    // 冷启动即插着相机的情况：自动请求权限
                    // 用 scanUsbDevices() 而不是 nikonDevices()：前者会把「这一轮枚举到了什么」写进日志文件，
                    // 用户拔线接相机跑一轮后，我们只看文件就知道卡在物理层还是协议层。
                    val dev = vm.scanUsbDevices().firstOrNull()
                    if (dev != null && !vm.hasPermission(dev)) {
                        requestUsbPermission(dev)
                    } else if (dev != null) {
                        vm.connectDevice(dev)
                    }
                }

                AppRoot(
                    vm = vm,
                    onPickImage = { pickImage.launch(arrayOf("image/*")) },
                    onPickNcp = { pickNcp.launch(arrayOf("*/*")) },
                    onRequestUsbPermission = { dev -> requestUsbPermission(dev) },
                )
            }
        }
    }

    /** 触发热插拔扫描（用户点「刷新设备」时调）。 */
    fun requestUsbPermission(dev: UsbDevice) {
        pendingDevice = dev
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        // ★ 必须用 FLAG_MUTABLE：系统要往这个 PendingIntent 里回填
        //   EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED。用 FLAG_IMMUTABLE 时
        //   回填被丢弃，广播里 granted 永远是 false —— 表现就是「点了允许，
        //   App 一点反应都没有」。setPackage 让它是显式 Intent，符合 API 34 要求。
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
        )
        HexLog.info("USB 权限", "请求授权 ${dev.productName} (PID=0x%04X)".format(dev.productId))
        usbManager.requestPermission(dev, pi)

        // 兜底：某些 ROM 不回调授权广播（或者回调被系统吞掉），
        // 这里主动轮询 hasPermission，授权成功就照样连。
        handler.postDelayed({
            val d = pendingDevice ?: return@postDelayed
            if (usbManager.hasPermission(d)) {
                HexLog.info("USB 权限", "轮询确认已授权，直接连接")
                pendingDevice = null
                viewModelRef?.connectDevice(d)
            }
        }, 2500)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbPermissionReceiver) }
        runCatching { unregisterReceiver(usbHotplugReceiver) }
        onImagePicked = null
        onNcpPicked = null
        super.onDestroy()
    }
}
