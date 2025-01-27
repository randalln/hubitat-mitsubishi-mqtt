/**
 * Hubitat Device Driver
 * Mitsubishi Heat Pump + MQTT
 * v1.1.4
 * https://github.com/sethkinast/hubitat-mitsubishi-mqtt/
 *
 * Control Mitsubishi heat pumps using HeatPump.cpp via MQTT
 * More info: https://github.com/SwiCago/HeatPump
 *
 * MIT License
 *
 * Copyright (c) 2022 Seth Kinast
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

import java.math.RoundingMode

@Field static List<String> supportedThermostatFanModes = ['auto', 'quiet', '1', '2', '3', '4']
@Field static List<String> supportedThermostatModes = ['auto', 'off', 'cool', 'heat', 'fan', 'dry']

@Field static Map thermostatModeMapping = [
    'HEAT': 'heat',
    'COOL': 'cool',
    'FAN': 'fan',
    'DRY': 'dry',
    'AUTO': 'auto'
]
@Field static Map thermostatOperatingStateMapping = [
    'heat': 'heating',
    'cool': 'cooling',
    'dry': 'cooling',
    'fan': 'fan only',
    'off': 'idle'
]
@Field static Map thermostatFanModeMapping = [
    'QUIET': 'circulate',
    '1': '1',
    '2': '2',
    '3': '3',
    '4': '4',
    'AUTO': 'auto'
]
@Field static Map fanControlSpeedMapping = [
    'QUIET': 'low',
    '1': 'medium-low',
    '2': 'medium',
    '3': 'medium-high',
    '4': 'high',
    'AUTO': 'auto'
]
@Field static List vanePositions = ['AUTO', '1', '2', '3', '4', '5', 'SWING']
@Field static List wideVanePositions = ['<<', '<', '|', '>', '>>', '<>', 'SWING']

metadata {
    definition(
        name: 'Mitsubishi Heat Pump MQTT',
        namespace: 'cogdev',
        author: 'Seth Kinast <seth@cogdev.net>',
        importUrl:
            'https://raw.githubusercontent.com/sethkinast/hubitat-mitsubishi-mqtt/master/hubitat-mitsubishi-mqtt.groovy'
    ) {
        capability 'Configuration'
        capability 'Initialize'
        capability 'TemperatureMeasurement'
        capability 'Thermostat'

        attribute 'vane', 'enum', vanePositions
        attribute 'wideVane', 'enum', wideVanePositions
        attribute 'remoteTemperature', 'number'

        command 'setThermostatFanMode', [[name: 'Set Thermostat Fan Mode', type: 'ENUM', constraints: supportedThermostatFanModes]]
        command 'setThermostatMode', [[name: 'Set Thermostat Mode', type: 'ENUM', constraints: supportedThermostatModes]]

        command 'dry'
        command 'vane', [[name: 'Position*', type: 'ENUM', constraints: vanePositions]]
        command 'wideVane', [[name: 'Position*', type: 'ENUM', constraints: wideVanePositions]]
        command 'setRemoteTemperature',
                [[name: "Temperature ${getTemperatureUnit()} (0 for internal sensor)", type: 'NUMBER']]
    }
}

preferences {
    section('MQTT') {
        input name: 'brokerIP', type: 'string', title: 'MQTT Broker IP Address', required: true
        input name: 'brokerPort', type: 'string', title: 'MQTT Broker Port', required: true, defaultValue: 1883
        input name: 'topicRoot', type: 'string', title: 'MQTT Topic Root',
            description: '(as defined in your header file, e.g. climate/office)', required: true
        input name: 'brokerUsername', type: 'string', title: 'MQTT User'
        input name: 'brokerPassword', type: 'password', title: 'MQTT Password'
        input name: 'gradualAdjustment', type: 'bool', title: 'Gradual Temperature Adjustment'
    }
    section('Advanced') {
        input name: 'debugLoggingEnabled', type: 'bool', title: 'Enable debug logging', defaultValue: true
    }
}

void initialize() {
    if (!getRunningMode()) {
        configure()
    }

    state.remove('connectDelay')
    connect()

    if (debugLoggingEnabled) {
        runIn(7200, disableDebugLogging)
    }
}

void configure() {
    updateRunningMode('cool')
    sendEvent(name: 'temperature', value: convertTemperatureIfNeeded(68.0, 'F', 1), unit: getTemperatureUnit())
    sendEvent(name: 'thermostatSetpoint', value: convertTemperatureIfNeeded(70.0, 'F', 1), unit: getTemperatureUnit())
    sendEvent(name: 'heatingSetpoint', value: convertTemperatureIfNeeded(70.0, 'F', 1), unit: getTemperatureUnit())
    sendEvent(name: 'coolingSetpoint', value: convertTemperatureIfNeeded(70.0, 'F', 1), unit: getTemperatureUnit())
    sendEvent(name: 'supportedThermostatFanModes', value: JsonOutput.toJson(supportedThermostatFanModes))
    sendEvent(name: 'supportedThermostatModes', value: JsonOutput.toJson(supportedThermostatModes))
    sendEvent(name: 'thermostatOperatingState', value: 'idle')
    sendEvent(name: 'vane', value: 'AUTO')
    sendEvent(name: 'wideVane', value: '|')
}

void updated() {
    initialize()
}

void parse(String message) {
    Map parsedMessage = interfaces.mqtt.parseMessage(message)
    logDebug parsedMessage.toString()
    String topic = parsedMessage.topic
    Map payload = new JsonSlurper().parseText(parsedMessage.payload)

    List events

    switch (topic) {
        case getStatusTopic():
            events = processTemperatureUpdate(payload)
            break
        case topicRoot:
            events = processOperatingUpdate(payload)
            break
    }

    logDebug events.toString()
    events.each { event ->
        sendEvent(event)
    }
}

private List processTemperatureUpdate(Map payload) {
    BigDecimal temperature = new BigDecimal(convertTemperatureIfNeeded(payload.roomTemperature, 'C', 1))
    BigDecimal tempC = new BigDecimal(payload.roomTemperature)
    String currentMode = device.currentValue('thermostatMode')
    // As the heat pump reports back temp changes, gradually adjust the intermediate setpoint
    if (gradualAdjustment && state.intermediateSetpoint && shouldPublishSetpoint(currentMode)) {
        def currentSetpoint = device.currentValue("heatingSetpoint")
        BigDecimal targetSetpointC = getTemperatureScale() == 'C' ? currentSetpoint : fahrenheitToCelsius(currentSetpoint)
        BigDecimal gradualSetpointC = calculateGradualSetpointHeating(tempC, targetSetpointC)
        logTrace "currentSetpointC: $targetSetpointC, gradualSetpointC: $gradualSetpointC, intermediateSetpoint: $state.intermediateSetpoint"
        if (gradualSetpointC != state.intermediateSetpoint) {
            graduallyAdjustSetpointHeating(tempC, targetSetpointC)
        }
    }

    return [
        [
            name: 'temperature',
            value: temperature,
            unit: getTemperatureUnit(),
            descriptionText: "Temperature is ${temperature}${getTemperatureUnit()}"
        ],
        [
            name: 'thermostatOperatingState',
            value: currentMode == 'fan' || payload.operating ?
                    thermostatOperatingStateMapping[currentMode] ?:
                    thermostatOperatingStateMapping[getRunningMode()] :
                'idle',
        ]
    ]
}

private List processOperatingUpdate(Map payload) {
    BigDecimal temperature = new BigDecimal(convertTemperatureIfNeeded(payload.temperature, 'C', 1))
    String mode = getThermostatMode(payload.power, payload.mode)
    String fanMode = getThermostatFanMode(payload.power, payload.fan)

    List events = [
        [name: 'thermostatMode', value: mode],
        [name: 'thermostatFanMode', value: fanMode],
    ]

    // in FAN/DRY modes the temperature is meaningless
    switch (mode) {
        case 'off':
        case 'fan':
        case 'dry':
            // `operating` is always false in these modes
            events << [name: 'thermostatOperatingState', value: thermostatOperatingStateMapping[mode]]
            break
        case 'cool':
            // Override the setpoint reported by the heat pump while we're gradually adjusting
            if (gradualAdjustment) {
                temperature = device.currentValue('heatingSetpoint')
            }
            events << [name: 'coolingSetpoint', value: temperature, unit: getTemperatureUnit()]
            events << [name: 'thermostatSetpoint', value: temperature, unit: getTemperatureUnit()]
            updateRunningMode('cool')
            break
        case 'heat':
            // Override the setpoint reported by the heat pump while we're gradually adjusting
            if (gradualAdjustment) {
                temperature = device.currentValue('heatingSetpoint')
            }
            events << [name: 'heatingSetpoint', value: temperature, unit: getTemperatureUnit()]
            events << [name: 'thermostatSetpoint', value: temperature, unit: getTemperatureUnit()]
            updateRunningMode('heat')
            break
        default:
            events << [name: 'thermostatSetpoint', value: temperature, unit: getTemperatureUnit()]
            // TODO: read auto mode out of payload and set lastRunningMode
            break
    }

    return events
}

private String getThermostatMode(String power, String mode) {
    return power == 'OFF' ? 'off' : thermostatModeMapping[mode]
}

private String getThermostatFanMode(String power, String mode) {
    return power == 'OFF' ? 'auto' : thermostatFanModeMapping[mode]
}

private void updateRunningMode(String lastRunningMode) {
    state.remove("intermediateSetpoint")
    updateDataValue('lastRunningMode', lastRunningMode)
}

private String getRunningMode() {
    getDataValue('lastRunningMode')
}

private BigDecimal getSetpointForMode(String mode) {
    switch (mode) {
        case 'heat':
            return device.currentValue('heatingSetpoint')
        case 'cool':
            return device.currentValue('coolingSetpoint')
        case 'auto':
            return device.currentValue('thermostatSetpoint')
    }
}

private BigDecimal convertInputToCelsius(BigDecimal inputTemperature) {
    return getTemperatureScale() == 'C' ?
            inputTemperature :
            fahrenheitToCelsius(inputTemperature).setScale(1, RoundingMode.HALF_UP)
}

/**
 * Convert temperature to Celsius as needed and round to the nearest 0.5
 * @param inputTemperature
 * @return temperature
 */
private BigDecimal convertToHalfCelsius(BigDecimal inputTemperature) {
    return getTemperatureScale() == 'C' ? roundToHalf(inputTemperature) : roundToHalf(fahrenheitToCelsius(inputTemperature))
}

private static BigDecimal roundToHalf(BigDecimal temperature) {
    return (temperature * 2).setScale(0, RoundingMode.HALF_UP) / 2.0
}

/* Commands */
void auto() { setThermostatMode('auto') }
void cool() {
    sendEvent([name: 'thermostatOperatingState', value: 'pending cool'])
    setThermostatMode('cool')
}
void emergencyHeat() {
    heat()
    logDebug 'emergency heat not supported; falling back to heat'
}
void heat() {
    sendEvent([name: 'thermostatOperatingState', value: 'pending heat'])
    setThermostatMode('heat')
}
void dry() { setThermostatMode('dry') }
void off() { setThermostatMode('off') }

void fanAuto() { setThermostatFanMode('auto') }
void fanCirculate() { setThermostatFanMode('circulate') }
void fanOn() { setThermostatFanMode('on') }

void vane(String position) {
    sendEvent([name: 'vane', value: position])
    publish(['vane': position])
}

void wideVane(String position) {
    sendEvent([name: 'wideVane', value: position])
    publish(['wideVane': position])
}

void setCoolingSetpoint(BigDecimal setpoint) {
    runInMillis(2000, "debouncedSetCoolingSetpoint", [data: [setpoint: setpoint]])
}

private def debouncedSetCoolingSetpoint(data) {
    final BigDecimal setpoint = data.setpoint
    sendEvent([name: 'coolingSetpoint', value: setpoint.setScale(1, RoundingMode.HALF_UP), unit: getTemperatureUnit()])
    String currentMode = device.currentValue('thermostatMode', true)
    if (
        ['pending cool', 'cool'].contains(currentMode) ||
        currentMode == 'auto' && getRunningMode() == 'cool' // won't work until HeatPump sends x09 payload
    ) {
        publish(['temperature': convertToHalfCelsius(setpoint)])
    } else {
        logDebug "Current mode is ${currentMode} so not publishing coolingSetpoint change to ${setpoint}"
    }
}

void setHeatingSetpoint(BigDecimal setpoint) {
    runInMillis(2000, "debouncedSetHeatingSetpoint", [data: [setpoint: setpoint]])
}

private def debouncedSetHeatingSetpoint(data) {
    // TODO: Figure out why the unit test requires data.data.setpoint instead
    final BigDecimal setpointC = convertToHalfCelsius(data.setpoint)
    sendEvent(
            [
                    name : 'heatingSetpoint',
                    value: new BigDecimal(convertTemperatureIfNeeded(setpointC, 'C', 1)),
                    unit : getTemperatureUnit()
            ]
    )
    final String currentMode = device.currentValue('thermostatMode', true)
    if (shouldPublishSetpoint(currentMode)) {
        if (gradualAdjustment) {
            BigDecimal currentTempC = convertInputToCelsius(device.currentValue('temperature'))
            graduallyAdjustSetpointHeating(currentTempC, setpointC)
        } else {
            publish(['temperature': setpointC.floatValue()])
        }
    } else {
        logDebug "Current mode is ${currentMode} so not publishing heatingSetpoint to ${setpointC}"
    }
}

private boolean shouldPublishSetpoint(String currentMode) {
    return ['pending heat', 'heat'].contains(currentMode) ||
            currentMode == 'auto' && getRunningMode() == 'heat' // won't work until HeatPump sends x09 payload
}

private def graduallyAdjustSetpointHeating(BigDecimal tempC, BigDecimal setpointC) {
    logTrace("tempC: ${tempC}, setpointC: ${setpointC}, intermediateSetpoint: ${state.intermediateSetpoint}")
    BigDecimal gradualSetpointC = calculateGradualSetpointHeating(tempC, setpointC)
    if (gradualSetpointC != setpointC) {
        log.info "Intermediate setpoint: ${tempC} < ${gradualSetpointC.floatValue()} > ${setpointC}"
        state.intermediateSetpoint = gradualSetpointC
        publish(['temperature': gradualSetpointC.floatValue()])
    } else {
        if (state.intermediateSetpoint) {
            log.info "Setpoint reached target setpoint: ${gradualSetpointC.floatValue()}"
            state.remove("intermediateSetpoint")
        }
        publish(['temperature': gradualSetpointC.floatValue()])
    }

    return gradualSetpointC
}

/**
 *
 * @param currentTempC current temperature in Celsius
 * @param targetSetpointC target setpoint in Celsius
 * @return new setpoint
 */
private static def calculateGradualSetpointHeating(BigDecimal currentTempC, BigDecimal targetSetpointC) {
    BigDecimal currentTemp = roundToHalf(currentTempC)
    BigDecimal newSetpoint = roundToHalf(targetSetpointC)

    if (newSetpoint > currentTemp + 0.5) {
        return currentTemp + 0.5
    } else {
        return newSetpoint
    }
}

void setRemoteTemperature(BigDecimal temperature) {
    if (temperature != null) {
        // 0 tells the code on the HP to switch to the internal sensor
        BigDecimal remoteTemp = temperature == 0 ? 0 : convertToHalfCelsius(temperature)
        def thermostatOperatingState = device.currentValue('thermostatOperatingState')
        // Add or subtract 0.5C while operating to actually get it to turn off closer to the desired setpoint
        if (remoteTemp != 0) {
            if (thermostatOperatingState == "heating") {
                remoteTemp += 0.5
            } else if (thermostatOperatingState == "cooling") {
                remoteTemp -= 0.5
            }
        }
        sendEvent([name: 'remoteTemperature', value: remoteTemp])
        publish(['remoteTemp': remoteTemp])
    }
}

void setThermostatMode(String mode) {
    String mappedMode = thermostatModeMapping.find { m -> m.value == mode }?.key
    switch (mode) {
        case 'off':
            publish(powerOff())
            break
        case 'auto':
        case 'cool':
        case 'heat':
            publish(powerOn([
                'mode': mappedMode,
                'temperature': convertToHalfCelsius(getSetpointForMode(mode))
            ]))
            break
        case 'fan':
        case 'dry':
            publish(powerOn(['mode': mappedMode]))
            break
    }
}

void setThermostatFanMode(String fanMode) {
    switch (fanMode) {
        case 'on':
            // The fan is never "off", so we'll treat this command like
            // "set the unit to fan mode" since otherwise it has no meaning
            // Default the fan to whatever speed it was on previous run
            publish(powerOn(['mode': 'FAN']))
            break
        case 'auto':
        case 'circulate':
            String mappedMode = thermostatFanModeMapping.find { m -> m.value == fanMode }?.key
            publish(powerOn(['fan': mappedMode]))
            break
        case 'quiet':
            publish(powerOn(['fan': 'QUIET']))
            break
        case '1':
        case '2':
        case '3':
        case '4':
            publish(powerOn(['fan': fanMode]))
            break
    }
}

private Map powerOn(Map command = [:]) {
    ['power': 'ON'] + command
}

private Map powerOff(Map command = [:]) {
    ['power': 'OFF'] + command
}

/* MQTT */

void connect() {
    try {
        logDebug "Connecting to MQTT broker at ${brokerIP}:${brokerPort}"
        interfaces.mqtt.connect(getMQTTConnectURI(), "hubitat_${device.id}", brokerUsername, brokerPassword)
    } catch (e) {
        log.error "Error connecting to MQTT broker: ${e.message}"
        reconnect()
    }
}

void reconnect() {
    state.connectDelay = state.connectDelay ?: 0
    state.connectDelay = Math.min(state.connectDelay + 1, 5)

    runIn(state.connectDelay * 60, connect)
}

void publish(Map command) {
    String payload = JsonOutput.toJson(command)
    logDebug "Publishing ${payload} to ${getSetTopic()}"
    interfaces.mqtt.publish(getSetTopic(), payload)
}

void subscribe() {
    interfaces.mqtt.subscribe(topicRoot)
    interfaces.mqtt.subscribe(getStatusTopic())
}

void mqttClientStatus(String status) {
    logDebug status
    if (status.startsWith('Error')) {
        try {
           interfaces.mqtt.disconnect()
        } catch (e) {}
        reconnect()
    } else {
        state.remove('connectDelay')
        runIn(1, subscribe)
    }
}

/* Helpers */

String getMQTTConnectURI() {
    "tcp://${brokerIP}:${brokerPort}"
}

String getStatusTopic() {
    "${topicRoot}/status"
}

String getSetTopic() {
    "${topicRoot}/set"
}

String getTemperatureUnit() {
    "°${getTemperatureScale()}"
}

void disableDebugLogging() {
    device.updateSetting('debugLoggingEnabled', [value: false, type: 'bool'])
}

void logDebug(String msg) {
    if (debugLoggingEnabled) {
        log.debug msg
    }
}

void logTrace(String msg) {
    if (debugLoggingEnabled) {
        log.trace msg
    }
}
