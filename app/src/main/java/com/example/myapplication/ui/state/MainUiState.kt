package com.example.myapplication.ui.state

import com.example.myapplication.data.serial.UsbConnectionState
import com.example.myapplication.domain.model.BolidDevice

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val type: LogType = LogType.INFO
)

enum class LogType {
    INFO, REQUEST, RESPONSE, ERROR, SUCCESS
}

data class MainUiState(
    val usbState: UsbConnectionState = UsbConnectionState.NoAdapter,
    val devices: List<BolidDevice> = emptyList(),
    val isSearching: Boolean = false,
    val currentAddress: Int? = null,
    val errorMessage: String? = null,
    val selectedDevice: BolidDevice? = null,
    val logs: List<LogEntry> = emptyList(),
    val showDeviceDialog: Boolean = false,
    val showChangeAddressDialog: Boolean = false,
    val isChangingAddress: Boolean = false,
    val showAddressChangeSuccess: Boolean = false
) {
    val isUsbReady: Boolean
        get() = usbState is UsbConnectionState.Ready
}

