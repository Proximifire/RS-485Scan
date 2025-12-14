package com.example.myapplication.data.serial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.myapplication.data.protocol.BolidProtocol
import com.example.myapplication.domain.model.BolidDevice
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class UsbSerialRepository(private val context: Context) {

    companion object {
        private const val ACTION_USB_PERMISSION =
            "com.example.myapplication.USB_PERMISSION"
        private const val BASE_RESPONSE_TIMEOUT_MS = 120
        private const val CONTINUATION_TIMEOUT_MS = 200
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) },
        PendingIntent.FLAG_IMMUTABLE
    )

    private val stopScanFlag = AtomicBoolean(false)

    private val _connectionState = MutableStateFlow<UsbConnectionState>(
        UsbConnectionState.NoAdapter
    )
    val connectionState: StateFlow<UsbConnectionState> = _connectionState

    private var receiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                        val handler = Handler(Looper.getMainLooper())
                        // Первая проверка через небольшую задержку
                        handler.postDelayed({
                            refreshDriver(device)
                            // Если разрешение еще не применено, повторяем проверку через еще одну задержку
                            handler.postDelayed({
                                if (!usbManager.hasPermission(device)) {
                                    refreshDriver(device)
                                }
                            }, 300)
                        }, 300)
                    } else {
                        _connectionState.value = UsbConnectionState.Error("Доступ к USB отклонён")
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> refreshDriver()
            }
        }
    }

    fun startMonitoring() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            context,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
        // Проверяем сразу и повторно через задержку
        refreshDriver()
        Handler(Looper.getMainLooper()).postDelayed({
            refreshDriver()
        }, 500)
    }

    fun stopMonitoring() {
        if (!receiverRegistered) return
        context.unregisterReceiver(usbReceiver)
        receiverRegistered = false
    }

    fun requestPermission(device: UsbDevice) {
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun refreshDriver(target: UsbDevice? = null) {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver = when {
            target != null -> drivers.firstOrNull { it.device.deviceId == target.deviceId }
            else -> drivers.firstOrNull()
        }
        if (driver == null) {
            _connectionState.value = UsbConnectionState.NoAdapter
            return
        }
        val device = driver.device
        if (usbManager.hasPermission(device)) {
            val driverName = driver.javaClass.simpleName ?: "USB Serial"
            _connectionState.value = UsbConnectionState.Ready(device, driverName)
        } else {
            _connectionState.value = UsbConnectionState.PermissionRequired(device)
        }
    }

    suspend fun scanDevices(
        onDeviceFound: suspend (BolidDevice) -> Unit,
        onAddressProbe: suspend (Int) -> Unit,
        onLog: suspend (String, String) -> Unit = { _, _ -> }, // request, response
        baudRate: Int = 9600
    ) = withContext(Dispatchers.IO) {
        val readyState = _connectionState.value
        if (readyState !is UsbConnectionState.Ready) {
            throw IllegalStateException("USB адаптер не готов")
        }

        val driver = UsbSerialProber.getDefaultProber()
            .findAllDrivers(usbManager)
            .firstOrNull { it.device.deviceId == readyState.device.deviceId }
            ?: throw IllegalStateException("Драйвер USB не найден")

        val connection = usbManager.openDevice(driver.device)
            ?: throw IllegalStateException("Не удалось открыть USB устройство")

        val port = driver.ports.firstOrNull()
            ?: throw IllegalStateException("Последовательный порт недоступен")

        try {
            port.open(connection)
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true
            stopScanFlag.set(false)
            
            // Сначала проверяем адрес 127, потом 1-126
            val addresses = listOf(127) + (1..126).toList()
            
            // Множество для отслеживания уже найденных устройств (адрес + тип)
            val foundDevices = mutableSetOf<Pair<Int, Int>>()
            // Множество для отслеживания адресов, для которых уже выведено сообщение "Ответ не получен"
            val noResponseLogged = mutableSetOf<Int>()
            
            for (address in addresses) {
                if (stopScanFlag.get()) break
                
                // Каждый адрес проверяем 2 раза
                repeat(2) {
                    if (stopScanFlag.get()) return@repeat
                    
                    onAddressProbe(address)
                    val request = BolidProtocol.buildTypeRequest(address)
                    try {
                        port.write(request, 100)
                    } catch (e: Exception) {
                        // Ошибка отправки - останавливаем весь обмен
                        throw Exception("Ошибка обмена с прибором: ${e.message}")
                    }
                    val response = readResponse(port)
                    if (response == null) {
                        // Выводим сообщение только один раз для каждого адреса
                        if (!noResponseLogged.contains(address)) {
                            onLog("Адрес $address: Ответ не получен", "")
                            noResponseLogged.add(address)
                        }
                        return@repeat
                    }
                    val responseHex = response.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                    
                    // Пытаемся распознать ответ
                    val device = BolidProtocol.parseTypeResponse(response, response.size)
                    
                    if (device != null) {
                        // Проверяем, не добавлено ли уже устройство с таким адресом и типом
                        val deviceKey = Pair(device.address, device.typeCode)
                        if (!foundDevices.contains(deviceKey)) {
                            foundDevices.add(deviceKey)
                            // Успешно распознано - выводим только один раз
                            onLog("Адрес ${device.address}: ${device.typeName} (версия ${device.version})", responseHex)
                            onDeviceFound(device)
                        }
                    } else {
                        // Пытаемся понять что удалось распознать
                        val logMessage = BolidProtocol.parsePartialResponse(response, response.size)
                        onLog(logMessage, responseHex)
                    }
                }
            }
        } finally {
            try {
                port.close()
            } catch (_: Exception) {
            }
            try {
                connection.close()
            } catch (_: Exception) {
            }
        }
    }

    fun stopScan() {
        stopScanFlag.set(true)
    }

    suspend fun changeAddress(
        currentAddress: Int,
        newAddress: Int,
        onLog: suspend (String, String) -> Unit = { _, _ -> },
        baudRate: Int = 9600
    ): Boolean = withContext(Dispatchers.IO) {
        val readyState = _connectionState.value
        if (readyState !is UsbConnectionState.Ready) {
            throw IllegalStateException("USB адаптер не готов")
        }

        val driver = UsbSerialProber.getDefaultProber()
            .findAllDrivers(usbManager)
            .firstOrNull { it.device.deviceId == readyState.device.deviceId }
            ?: throw IllegalStateException("Драйвер USB не найден")

        val connection = usbManager.openDevice(driver.device)
            ?: throw IllegalStateException("Не удалось открыть USB устройство")

        val port = driver.ports.firstOrNull()
            ?: throw IllegalStateException("Последовательный порт недоступен")

        try {
            port.open(connection)
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true

            val request = BolidProtocol.buildChangeAddressRequest(currentAddress, newAddress)
            val requestHex = request.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            onLog("Отправка команды смены адреса: $requestHex", "")
            
            try {
                port.write(request, 100)
            } catch (e: Exception) {
                // Ошибка отправки - останавливаем весь обмен
                throw Exception("Ошибка обмена с прибором: ${e.message}")
            }
            
            val response = readResponse(port)
            if (response == null) {
                onLog("Ответ на смену адреса не получен", "")
                return@withContext false
            }
            
            val responseHex = response.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            onLog("Получен ответ на смену адреса: $responseHex", "")
            
            val success = BolidProtocol.parseChangeAddressResponse(response, response.size, newAddress)
            if (success) {
                onLog("Смена адреса прошла успешно", "")
            } else {
                onLog("Смена адреса не удалась (неверный ответ)", "")
            }
            
            return@withContext success
        } finally {
            try {
                port.close()
            } catch (e: Exception) {
                // Игнорируем ошибки закрытия
            }
            connection.close()
        }
    }

    private fun readResponse(port: UsbSerialPort): ByteArray? {
        val data = ArrayList<Byte>(32)
        var totalExpected = -1
        var timeout = BASE_RESPONSE_TIMEOUT_MS
        
        while (true) {
            val chunk = ByteArray(64)
            val len = try {
                port.read(chunk, timeout)
            } catch (_: Exception) {
                break
            }
            if (len <= 0) break
            
            for (i in 0 until len) {
                data.add(chunk[i])
            }
            
            // После получения второго байта вычисляем ожидаемый размер всего сообщения
            if (data.size >= 2 && totalExpected == -1) {
                val secondByte = data[1].toInt() and 0xFF
                // Размер всего сообщения = второй байт + 1
                totalExpected = secondByte + 1
                timeout = CONTINUATION_TIMEOUT_MS
            }
            
            // Если получили все ожидаемые байты - выходим
            if (totalExpected != -1 && data.size >= totalExpected) {
                break
            }
            
            timeout = CONTINUATION_TIMEOUT_MS
            if (data.size >= 64) break // Защита от бесконечного цикла
        }
        
        if (data.isEmpty()) return null
        return data.toByteArray()
    }

    private fun List<Byte>.toByteArray(): ByteArray {
        val array = ByteArray(size)
        for (i in indices) {
            array[i] = this[i]
        }
        return array
    }

}

