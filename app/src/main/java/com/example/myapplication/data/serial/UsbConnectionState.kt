package com.example.myapplication.data.serial

import android.hardware.usb.UsbDevice

sealed interface UsbConnectionState {
    data object NoAdapter : UsbConnectionState
    data class PermissionRequired(val device: UsbDevice) : UsbConnectionState
    data class Ready(val device: UsbDevice, val driverName: String) : UsbConnectionState
    data class Error(val reason: String) : UsbConnectionState
}



