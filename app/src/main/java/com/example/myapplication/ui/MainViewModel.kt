package com.example.myapplication.ui

import android.app.Application
import android.hardware.usb.UsbDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.serial.UsbConnectionState
import com.example.myapplication.data.serial.UsbSerialRepository
import com.example.myapplication.domain.model.BolidDevice
import com.example.myapplication.ui.state.LogEntry
import com.example.myapplication.ui.state.LogType
import com.example.myapplication.ui.state.MainUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val usbRepository = UsbSerialRepository(application)
    
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    
    init {
        // Начинаем мониторинг USB устройств
        usbRepository.startMonitoring()
        
        // Подписываемся на изменения состояния USB
        viewModelScope.launch {
            usbRepository.connectionState.collect { state ->
                _uiState.update { it.copy(usbState = state, errorMessage = null) }
                
                // Автоматически запрашиваем разрешение для нового устройства
                if (state is UsbConnectionState.PermissionRequired) {
                    // Минимальная задержка для стабильности
                    delay(100)
                    requestPermission(state.device)
                }
            }
        }
    }
    
    fun requestPermission(device: UsbDevice) {
        usbRepository.requestPermission(device)
    }
    
    fun startScan() {
        if (!_uiState.value.isUsbReady) {
            _uiState.update { it.copy(errorMessage = "USB адаптер не готов") }
            return
        }
        
        if (_uiState.value.isSearching) {
            return
        }
        
        viewModelScope.launch {
            _uiState.update { 
                it.copy(
                    isSearching = true,
                    devices = emptyList(),
                    errorMessage = null,
                    currentAddress = null
                )
            }
            
            try {
                addLog("Начало сканирования устройств", LogType.INFO)
                usbRepository.scanDevices(
                    onDeviceFound = { device ->
                        _uiState.update { state ->
                            // Проверяем, не добавлено ли уже устройство с таким адресом и типом
                            val alreadyExists = state.devices.any { 
                                it.address == device.address && it.typeCode == device.typeCode 
                            }
                            if (!alreadyExists) {
                                state.copy(
                                    devices = state.devices + device
                                )
                            } else {
                                state
                            }
                        }
                    },
                    onAddressProbe = { address ->
                        _uiState.update { it.copy(currentAddress = address) }
                    },
                    onLog = { request, response ->
                        if (response.isNotEmpty()) {
                            addLog("$request", LogType.REQUEST)
                            addLog("$response", LogType.RESPONSE)
                        } else {
                            addLog(request, LogType.INFO)
                        }
                    }
                )
                addLog("Сканирование завершено. Найдено устройств: ${_uiState.value.devices.size}", LogType.INFO)
            } catch (e: Exception) {
                val errorMessage = if (e.message?.contains("Ошибка обмена с прибором") == true) {
                    "Ошибка обмена с прибором"
                } else {
                    "Ошибка сканирования: ${e.message}"
                }
                addLog(errorMessage, LogType.ERROR)
                _uiState.update { 
                    it.copy(
                        errorMessage = errorMessage,
                        isSearching = false
                    )
                }
            } finally {
                _uiState.update { 
                    it.copy(
                        isSearching = false,
                        currentAddress = null
                    )
                }
            }
        }
    }
    
    fun stopScan() {
        usbRepository.stopScan()
        _uiState.update { 
            it.copy(
                isSearching = false,
                currentAddress = null
            )
        }
    }
    
    fun selectDevice(device: BolidDevice) {
        // Останавливаем сканирование при выборе устройства
        if (_uiState.value.isSearching) {
            stopScan()
        }
        _uiState.update { 
            it.copy(
                selectedDevice = device,
                showDeviceDialog = true
            )
        }
    }
    
    fun closeDeviceDialog() {
        _uiState.update { 
            it.copy(
                showDeviceDialog = false,
                selectedDevice = null,
                showChangeAddressDialog = false
            )
        }
    }
    
    fun openChangeAddressDialog() {
        _uiState.update { 
            it.copy(showChangeAddressDialog = true)
        }
    }
    
    fun closeChangeAddressDialog() {
        _uiState.update { 
            it.copy(showChangeAddressDialog = false)
        }
    }
    
    
    fun changeAddress(newAddress: Int) {
        val device = _uiState.value.selectedDevice ?: return
        
        if (newAddress !in 1..127) {
            addLog("Некорректный адрес: $newAddress (должен быть от 1 до 127)", LogType.ERROR)
            return
        }
        
        if (newAddress == device.address) {
            addLog("Новый адрес совпадает с текущим", LogType.ERROR)
            return
        }
        
        viewModelScope.launch {
            // Закрываем диалог устройства сразу после отправки команды
            _uiState.update { 
                it.copy(
                    isChangingAddress = true,
                    showChangeAddressDialog = false,
                    showDeviceDialog = false
                )
            }
            
            try {
                val success = usbRepository.changeAddress(
                    currentAddress = device.address,
                    newAddress = newAddress,
                    onLog = { _, _ -> }
                )
                
                if (success) {
                    // Обновляем устройство в списке
                    _uiState.update { state ->
                        val updatedDevice = device.copy(address = newAddress)
                        val updatedDevices = state.devices.map { 
                            if (it.address == device.address && it.typeCode == device.typeCode) {
                                updatedDevice
                            } else {
                                it
                            }
                        }
                        state.copy(
                            devices = updatedDevices,
                            selectedDevice = null,
                            isChangingAddress = false,
                            showAddressChangeSuccess = true
                        )
                    }
                    // Автоматически скрываем сообщение об успехе через 5 секунд
                    kotlinx.coroutines.delay(5000)
                    _uiState.update { it.copy(showAddressChangeSuccess = false) }
                } else {
                    val errorMessage = "Смена адреса не удалась"
                    _uiState.update { 
                        it.copy(
                            errorMessage = errorMessage,
                            isChangingAddress = false
                        )
                    }
                }
            } catch (e: Exception) {
                val errorMessage = if (e.message?.contains("Ошибка обмена с прибором") == true) {
                    "Ошибка обмена с прибором"
                } else {
                    "Ошибка при смене адреса: ${e.message}"
                }
                _uiState.update { 
                    it.copy(
                        errorMessage = errorMessage,
                        isChangingAddress = false
                    )
                }
            }
        }
    }
    
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
    
    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
    }
    
    fun clearDevices() {
        _uiState.update { it.copy(devices = emptyList()) }
    }
    
    private fun addLog(message: String, type: LogType = LogType.INFO) {
        _uiState.update { state ->
            val newLog = LogEntry(message = message, type = type)
            // Ограничиваем количество логов до 1000
            val updatedLogs = (state.logs + newLog).takeLast(1000)
            state.copy(logs = updatedLogs)
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        usbRepository.stopMonitoring()
    }
}

