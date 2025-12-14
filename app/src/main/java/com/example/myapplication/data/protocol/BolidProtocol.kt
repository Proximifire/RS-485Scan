package com.example.myapplication.data.protocol

import com.example.myapplication.domain.model.BolidDevice

object BolidProtocol {
    
    // Код запроса типа и версии устройства
    private const val CMD_GET_TYPE = 0x0D
    
    // Код запроса смены адреса
    private const val CMD_CHANGE_ADDRESS = 0x0F
    
    // Код ответа на смену адреса
    private const val RESPONSE_CHANGE_ADDRESS = 0x10
    
    // Ключ шифрования (всегда 00)
    private const val ENCRYPTION_KEY = 0x00
    
    // Количество байт в запросе без CRC (всегда 06)
    private const val REQUEST_LENGTH = 0x06
    
    /**
     * Создает запрос для получения типа устройства по адресу
     * Формат запроса:
     * 1. Адрес прибора (1 байт) - от 1 до 127
     * 2. Количество байт в запросе без CRC (1 байт) - всегда 06
     * 3. Ключ шифрования (1 байт) - всегда 00
     * 4. Код запроса (1 байт) - 0D для запроса типа и версии
     * 5. Заполнение (1 байт) - 00
     * 6. Заполнение (1 байт) - 00
     * 7. CRC (1 байт)
     * Итого: 7 байт (6 байт данных + 1 байт CRC)
     */
    fun buildTypeRequest(address: Int): ByteArray {
        require(address in 1..127) { "Адрес должен быть в диапазоне 1-127" }
        
        // Формируем данные запроса (без CRC)
        val data = byteArrayOf(
            address.toByte(),                    // 1. Адрес прибора
            REQUEST_LENGTH.toByte(),             // 2. Количество байт (06)
            ENCRYPTION_KEY.toByte(),              // 3. Ключ шифрования (00)
            CMD_GET_TYPE.toByte(),                // 4. Код запроса (0D)
            0x00.toByte(),                        // 5. Заполнение (00)
            0x00.toByte()                         // 6. Заполнение (00)
        )
        
        // Вычисляем CRC для данных
        val crc = Crc8Bolide.calculate(data)
        
        // Возвращаем данные + CRC
        return data + crc
    }
    
    /**
     * Парсит ответ с типом устройства
     * Формат ответа:
     * 1. Адрес прибора (1 байт) - от 1 до 127
     * 2. Количество байт в ответе без CRC (1 байт) - от 5 до 11
     * 3. Код ответа (1 байт) - 0x00 для типа и версии
     * 4. Тип прибора (1 байт) - код типа устройства
     * 5. Версия прибора (1 байт) - версия в hex, переводим в десятичное
     * 6. Байт (1 байт) - неизвестно для чего
     * 7. Байт (1 байт) - неизвестно для чего
     * ... (могут быть дополнительные байты до 11)
     * CRC (1 байт)
     * 
     * Минимальный размер: 1 + 1 + 5 + 1 = 8 байт
     * Максимальный размер: 1 + 1 + 11 + 1 = 14 байт
     */
    fun parseTypeResponse(response: ByteArray, size: Int): BolidDevice? {
        try {
            // Проверка 1: Размер сообщения
            if (size < 2) {
                return null // Недостаточно данных для определения размера
            }
            
            val secondByte = response[1].toInt() and 0xFF
            // Размер всего сообщения = второй байт + 1
            val expectedSize = secondByte + 1
            
            if (size != expectedSize) {
                return null // Неверный размер сообщения
            }
            
            // Проверка 2: CRC
            val dataWithoutCrc = response.sliceArray(0 until size - 1)
            val receivedCrc = response[size - 1].toInt() and 0xFF
            val calculatedCrc = Crc8Bolide.calculate(dataWithoutCrc).toInt() and 0xFF
            
            if (receivedCrc != calculatedCrc) {
                return null // Неверная контрольная сумма ответа
            }
            
            // Обе проверки прошли - парсим ответ
            val address = response[0].toInt() and 0xFF
            val responseCode = response[2].toInt() and 0xFF
            
            // Проверяем код ответа (должен быть 0x00 для типа и версии)
            if (responseCode != 0x00) {
                return null // Неверный код ответа
            }
            
            // Тип прибора (4-й байт, индекс 3)
            val typeCode = response[3].toInt() and 0xFF
            
            // Версия прибора (5-й байт, индекс 4) - переводим из hex в десятичное
            val versionHex = response[4].toInt() and 0xFF
            val version = versionHex.toString() // Версия в десятичном виде
            
            // Имя типа устройства на основе кода
            val typeName = getTypeName(typeCode)
            
            // Hex представление ответа
            val rawHex = response.sliceArray(0 until size)
                .joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            
            // Создаем объект устройства
            val device = BolidDevice(
                address = address,
                typeCode = typeCode,
                typeName = typeName,
                version = version,
                rawHex = rawHex
            )
            
            return device
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    
    /**
     * Парсит частичный ответ и возвращает информацию о том, что удалось распознать
     */
    fun parsePartialResponse(response: ByteArray, size: Int): String {
        if (size < 1) {
            return "Неизвестный ответ (пустой)"
        }
        
        val address = response[0].toInt() and 0xFF
        
        // Проверка размера
        if (size < 2) {
            return "Адрес $address - неизвестный ответ"
        }
        
        val secondByte = response[1].toInt() and 0xFF
        // Размер всего сообщения = второй байт + 1
        val expectedSize = secondByte + 1
        
        if (size != expectedSize) {
            return "Адрес $address - Неверный размер сообщения (получено $size, ожидается $expectedSize)"
        }
        
        // Проверка CRC
        if (size >= 2) {
            val dataWithoutCrc = response.sliceArray(0 until size - 1)
            val receivedCrc = response[size - 1].toInt() and 0xFF
            val calculatedCrc = Crc8Bolide.calculate(dataWithoutCrc).toInt() and 0xFF
            
            if (receivedCrc != calculatedCrc) {
                return "Адрес $address - Неверная контрольная сумма ответа"
            }
        }
        
        // Если обе проверки прошли, но парсинг не удался - значит проблема в данных
        if (size < 3) {
            return "Адрес $address - неизвестный ответ"
        }
        
        val responseCode = response[2].toInt() and 0xFF
        
        if (responseCode != 0x00) {
            return "Адрес $address - неизвестный ответ (код ответа 0x${responseCode.toString(16).uppercase().padStart(2, '0')})"
        }
        
        if (size < 4) {
            return "Адрес $address - Тип прибора (Неизвестно)"
        }
        
        val typeCode = response[3].toInt() and 0xFF
        val typeName = getTypeName(typeCode)
        
        if (size < 5) {
            return "Адрес $address - $typeName (версия неизвестна)"
        }
        
        val version = response[4].toInt() and 0xFF
        
        return "Адрес $address - $typeName (версия $version)"
    }
    
    /**
     * Создает запрос для смены адреса прибора
     * Формат запроса:
     * 1. Адрес прибора (1 байт) - от 1 до 127 (текущий адрес)
     * 2. Количество байт в запросе без CRC (1 байт) - всегда 06
     * 3. Ключ шифрования (1 байт) - всегда 00
     * 4. Код запроса (1 байт) - 0F для смены адреса
     * 5. Новый адрес (1 байт) - от 1 до 127
     * 6. Новый адрес (1 байт) - от 1 до 127 (дубликат)
     * 7. CRC (1 байт)
     * Итого: 7 байт (6 байт данных + 1 байт CRC)
     */
    fun buildChangeAddressRequest(currentAddress: Int, newAddress: Int): ByteArray {
        require(currentAddress in 1..127) { "Текущий адрес должен быть в диапазоне 1-127" }
        require(newAddress in 1..127) { "Новый адрес должен быть в диапазоне 1-127" }
        
        // Формируем данные запроса (без CRC)
        val data = byteArrayOf(
            currentAddress.toByte(),                 // 1. Текущий адрес прибора
            REQUEST_LENGTH.toByte(),                 // 2. Количество байт (06)
            ENCRYPTION_KEY.toByte(),                  // 3. Ключ шифрования (00)
            CMD_CHANGE_ADDRESS.toByte(),              // 4. Код запроса (0F)
            newAddress.toByte(),                      // 5. Новый адрес
            newAddress.toByte()                        // 6. Новый адрес (дубликат)
        )
        
        // Вычисляем CRC для данных
        val crc = Crc8Bolide.calculate(data)
        
        // Возвращаем данные + CRC
        return data + crc
    }
    
    /**
     * Парсит ответ на смену адреса
     * Формат ответа:
     * 1. Адрес прибора (1 байт) - новый адрес прибора
     * 2. Количество байт в ответе без CRC (1 байт) - всегда 05
     * 3. Код ответа (1 байт) - 10 для смены адреса
     * 4. Новый адрес (1 байт)
     * 5. Новый адрес (1 байт) - дубликат
     * 6. CRC (1 байт)
     * Итого: 6 байт (5 байт данных + 1 байт CRC)
     */
    fun parseChangeAddressResponse(response: ByteArray, size: Int, expectedNewAddress: Int): Boolean {
        if (size < 2) return false
        
        val address = response[0].toInt() and 0xFF
        val dataLength = response[1].toInt() and 0xFF
        
        // Проверка размера: должно быть dataLength + 1 байт (данные + CRC)
        val expectedSize = dataLength + 1
        if (size != expectedSize) return false
        
        // Проверка CRC
        val dataWithoutCrc = response.sliceArray(0 until size - 1)
        val receivedCrc = response[size - 1].toInt() and 0xFF
        val calculatedCrc = Crc8Bolide.calculate(dataWithoutCrc).toInt() and 0xFF
        
        if (receivedCrc != calculatedCrc) return false
        
        // Проверка кода ответа
        if (size < 3) return false
        val responseCode = response[2].toInt() and 0xFF
        if (responseCode != RESPONSE_CHANGE_ADDRESS) return false
        
        // Проверка адреса (ответ должен прийти с нового адреса)
        if (address != expectedNewAddress) return false
        
        // Проверка нового адреса в данных
        if (size < 5) return false
        val newAddress1 = response[3].toInt() and 0xFF
        val newAddress2 = response[4].toInt() and 0xFF
        
        if (newAddress1 != expectedNewAddress || newAddress2 != expectedNewAddress) return false
        
        return true
    }
    
    /**
     * Получает имя типа устройства по коду
     * Код типа - один байт (от 0x00 до 0xFF)
     */
    private fun getTypeName(typeCode: Int): String {
        return when (typeCode) {
            0 -> "Пульт С2000М"
            1 -> "Сигнал-20"
            2 -> "Сигнал-20П"
            3 -> "С2000-СП1"
            4 -> "С2000-4"
            7 -> "С2000-К"
            8 -> "С2000-ИТ"
            9 -> "С2000-КДЛ"
            10 -> "С2000-БИ/БКИ"
            11 -> "Сигнал-20(вер. 02)"
            13 -> "С2000-КС"
            14 -> "С2000-АСПТ"
            15 -> "С2000-КПБ"
            16 -> "С2000-2"
            19 -> "УО-ОРИОН"
            20 -> "Рупор"
            21 -> "Рупор-Диспетчер исп.01"
            22 -> "С2000-ПТ"
            24 -> "УО-4С"
            25 -> "Поток-3Н"
            26 -> "Сигнал-20М"
            28 -> "С2000-БИ-01"
            30 -> "Рупор исп.01"
            31 -> "С2000-Adem"
            32 -> "Сигнал-10"
            33 -> "РИП-12 исп.50, исп.51, без исполнения"
            34 -> "Сигнал-10"
            35 -> "РИП-12-2А RS"
            36 -> "С2000-ПП"
            37 -> "РИП-24-2А RS"
            38 -> "РИП-12 исп.54"
            39 -> "РИП-24 исп.50, исп.51"
            40 -> "С2000-КДЛ-2И"
            41 -> "С2000-КДЛ-2И"
            42 -> "РИП-12 исп.50, исп.51, без исполнения"
            43 -> "С2000-PGE"
            44 -> "С2000-БКИ"
            45 -> "Поток-БКИ"
            46 -> "Рупор-200"
            47 -> "С2000-Периметр"
            48 -> "МИП-12"
            49 -> "МИП-24"
            50 -> "РИП-12 исп.54"
            51 -> "РИП-24 исп.50, исп.51"
            52 -> "С2000-Периметр"
            53 -> "РИП-48 исп.01"
            54 -> "РИП-12 исп.56"
            55 -> "РИП-24 исп.56"
            56 -> "РИП-24 исп.57"
            59 -> "Рупор исп.02"
            61 -> "С2000-КДЛ-Modbus"
            66 -> "Рупор исп.03"
            67 -> "Рупор-300"
            76 -> "С2000-PGE исп.01"
            78 -> "РИП-24 исп.57"
            79 -> "ПКВ-РИП-12 исп.56"
            80 -> "ПКВ-РИП-24 исп.56"
            81 -> "С2000-КДЛ-2И исп.01"
            82 -> "ШКП-RS"
            85 -> "Микрофонная консоль-20"
            87 -> "Рупор-Диспетчер исп.02"
            88 -> "МИП-12 исп.11"
            89 -> "МИП-24 исп.11"
            else -> "Неизвестное устройство (тип $typeCode)"
        }
    }
}

