package com.example.myapplication.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.window.Dialog
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.serial.UsbConnectionState
import com.example.myapplication.domain.model.BolidDevice
import com.example.myapplication.ui.state.MainUiState

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Заголовок
        Text(
            text = "Сканер устройств Болид",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        // Состояние USB и служебные сообщения
        StatusCard(
            uiState = uiState,
            onPermissionRequest = { device ->
                viewModel.requestPermission(device)
            }
        )
        
        // Кнопки управления
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.startScan() },
                enabled = uiState.isUsbReady && !uiState.isSearching,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = androidx.compose.ui.graphics.Color(0xFF0060B2),
                    contentColor = androidx.compose.ui.graphics.Color.White
                )
            ) {
                Text("Начать сканирование")
            }
            
            if (uiState.isSearching) {
                Button(
                    onClick = { viewModel.stopScan() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFFED1A3B),
                        contentColor = androidx.compose.ui.graphics.Color.White
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Остановить")
                }
            }
        }
        
        
        // Заголовок списка найденных устройств
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Найденные приборы (${uiState.devices.size})",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = androidx.compose.ui.graphics.Color(0xFF0060B2)
            )
            if (uiState.devices.isNotEmpty()) {
                TextButton(
                    onClick = { viewModel.clearDevices() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = androidx.compose.ui.graphics.Color(0xFF0060B2)
                    )
                ) {
                    Text("Очистить")
                }
            }
        }
        
        // Список найденных устройств
        if (uiState.devices.isEmpty() && !uiState.isSearching) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = "Список пуст. Нажмите 'Начать сканирование' для поиска приборов.",
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = androidx.compose.ui.graphics.Color(0xFF8E9090)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.devices) { device ->
                    DeviceCard(
                        device = device,
                        onClick = { 
                            // Пульт С2000М (тип 0) не должен открывать диалог
                            if (device.typeCode != 0) {
                                viewModel.selectDevice(device)
                            }
                        },
                        enabled = device.typeCode != 0
                    )
                }
            }
        }
        
        // Диалог выбора действия для устройства
        if (uiState.showDeviceDialog && uiState.selectedDevice != null) {
            DeviceActionDialog(
                device = uiState.selectedDevice!!,
                onDismiss = { viewModel.closeDeviceDialog() },
                onChangeAddress = { 
                    viewModel.openChangeAddressDialog()
                },
                onReadEvents = { 
                    // TODO: реализовать чтение событий
                    viewModel.closeDeviceDialog()
                }
            )
        }
        
        // Диалог ввода нового адреса
        if (uiState.showChangeAddressDialog && uiState.selectedDevice != null) {
            ChangeAddressDialog(
                currentAddress = uiState.selectedDevice!!.address,
                onDismiss = { viewModel.closeChangeAddressDialog() },
                onConfirm = { newAddress ->
                    viewModel.changeAddress(newAddress)
                }
            )
        }
        
    }
}

@Composable
fun StatusCard(
    uiState: MainUiState,
    onPermissionRequest: (android.hardware.usb.UsbDevice) -> Unit
) {
    val blinkAlpha = remember { Animatable(1f) }
    val blinkKey = when {
        uiState.errorMessage != null -> "error:${uiState.errorMessage}"
        uiState.showAddressChangeSuccess -> "success"
        else -> ""
    }
    LaunchedEffect(blinkKey) {
        if (blinkKey.isNotEmpty()) {
            repeat(2) {
                blinkAlpha.animateTo(0.3f, tween(durationMillis = 100))
                blinkAlpha.animateTo(1f, tween(durationMillis = 100))
            }
        } else {
            blinkAlpha.snapTo(1f)
        }
    }

    val (content, color, showButton, buttonText, onButtonClick) = when {
        // Приоритет 1: Сканирование
        uiState.isSearching -> {
            val message = if (uiState.currentAddress != null) {
                "Проверка адреса: ${uiState.currentAddress}"
            } else {
                "Сканирование..."
            }
            StatusContent(
                content = {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = androidx.compose.ui.graphics.Color(0xFF0060B2)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                },
                color = MaterialTheme.colorScheme.primaryContainer,
                showButton = false,
                buttonText = null,
                onButtonClick = null
            )
        }
        // Приоритет 2: Смена адреса
        uiState.isChangingAddress -> {
            StatusContent(
                content = {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = androidx.compose.ui.graphics.Color(0xFF0060B2)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Выполняется смена адреса...",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                },
                color = MaterialTheme.colorScheme.primaryContainer,
                showButton = false,
                buttonText = null,
                onButtonClick = null
            )
        }
        // Приоритет 3: Сообщение об ошибке
        uiState.errorMessage != null -> {
            StatusContent(
                content = {
                    Text(
                        text = uiState.errorMessage!!,
                        style = MaterialTheme.typography.bodyLarge,
                        color = androidx.compose.ui.graphics.Color(0xFFED1A3B)
                    )
                },
                color = androidx.compose.ui.graphics.Color(0xFFED1A3B).copy(alpha = 0.1f),
                showButton = false,
                buttonText = null,
                onButtonClick = null
            )
        }
        // Приоритет 4: Сообщение об успешной смене адреса
        uiState.showAddressChangeSuccess -> {
            StatusContent(
                content = {
                    Text(
                        text = "Смена адреса прошла успешно",
                        style = MaterialTheme.typography.bodyLarge,
                        color = androidx.compose.ui.graphics.Color.White
                    )
                },
                color = androidx.compose.ui.graphics.Color(0xFF0060B2),
                showButton = false,
                buttonText = null,
                onButtonClick = null
            )
        }
        // Приоритет 5: Статус USB
        else -> {
            val state = uiState.usbState
            val (message, bgColor, action) = when (state) {
                is UsbConnectionState.NoAdapter -> Triple(
                    "USB адаптер не подключен",
                    MaterialTheme.colorScheme.errorContainer,
                    null as String?
                )
                is UsbConnectionState.PermissionRequired -> Triple(
                    "Требуется разрешение для USB устройства",
                    MaterialTheme.colorScheme.primaryContainer,
                    "Запросить разрешение"
                )
                is UsbConnectionState.Ready -> Triple(
                    "USB адаптер готов (${state.driverName})",
                    MaterialTheme.colorScheme.primaryContainer,
                    null
                )
                is UsbConnectionState.Error -> Triple(
                    state.reason,
                    MaterialTheme.colorScheme.errorContainer,
                    null
                )
            }
            StatusContent(
                content = {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = when (state) {
                            is UsbConnectionState.Error -> MaterialTheme.colorScheme.onErrorContainer
                            else -> MaterialTheme.colorScheme.onPrimaryContainer
                        }
                    )
                },
                color = bgColor,
                showButton = action != null,
                buttonText = action,
                onButtonClick = if (state is UsbConnectionState.PermissionRequired) {
                    { onPermissionRequest(state.device) }
                } else null
            )
        }
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = color
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.alpha(blinkAlpha.value)) {
                content()
            }
            if (showButton && buttonText != null && onButtonClick != null) {
                Spacer(modifier = Modifier.width(12.dp))
                TextButton(onClick = onButtonClick) {
                    Text(buttonText)
                }
            }
        }
    }
}

data class StatusContent(
    val content: @Composable () -> Unit,
    val color: androidx.compose.ui.graphics.Color,
    val showButton: Boolean,
    val buttonText: String?,
    val onButtonClick: (() -> Unit)?
)

@Composable
fun DeviceCard(
    device: BolidDevice,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${device.typeName}, Адрес: ${device.address}, Версия: ${device.version}",
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun DeviceActionDialog(
    device: BolidDevice,
    onDismiss: () -> Unit,
    onChangeAddress: () -> Unit,
    onReadEvents: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = device.typeName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Text(
                    text = "Адрес: ${device.address}   Версия: ${device.version}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Divider()
                
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onChangeAddress,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = androidx.compose.ui.graphics.Color(0xFF0060B2),
                            contentColor = androidx.compose.ui.graphics.Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Сменить адрес")
                    }
                    
                    Button(
                        onClick = onReadEvents,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = androidx.compose.ui.graphics.Color(0xFF0060B2),
                            contentColor = androidx.compose.ui.graphics.Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Считать события")
                    }
                    
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = androidx.compose.ui.graphics.Color(0xFF8E9090)
                        )
                    ) {
                        Text("Отмена")
                    }
                }
            }
        }
    }
}

@Composable
fun ChangeAddressDialog(
    currentAddress: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var addressText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Смена адреса",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                
                Text("Текущий адрес: $currentAddress")
                
                OutlinedTextField(
                    value = addressText,
                    onValueChange = { newValue ->
                        // Разрешаем только цифры
                        if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                            addressText = newValue
                            errorMessage = null
                        }
                    },
                    label = { Text("Новый адрес (1-127)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it) } }
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Отмена")
                    }
                    
                    Button(
                        onClick = {
                            val newAddress = addressText.toIntOrNull()
                            when {
                                newAddress == null -> {
                                    errorMessage = "Введите число"
                                }
                                newAddress !in 1..127 -> {
                                    errorMessage = "Адрес должен быть от 1 до 127"
                                }
                                newAddress == currentAddress -> {
                                    errorMessage = "Новый адрес совпадает с текущим"
                                }
                                else -> {
                                    onConfirm(newAddress)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = androidx.compose.ui.graphics.Color(0xFF0060B2),
                            contentColor = androidx.compose.ui.graphics.Color.White
                        )
                    ) {
                        Text("ОК")
                    }
                }
            }
        }
    }
}

