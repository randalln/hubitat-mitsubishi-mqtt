// Copyright (c) 2022 Seth Kinast
// SPDX-FileCopyrightText: 2025 Randall Norviel <randallndev@gmail.com>
// SPDX-FileCopyrightText: 2022 Seth Kinast <seth@cogdev.net>
//
// SPDX-License-Identifier: MIT

/**
 * Hubitat Device Driver
 * Mitsubishi Heat Pump MQTT
 * v1.3.1
 *
 * https://github.com/randalln/hubitat-mitsubishi-mqtt/
 *
 * This work is derived from the original project at https://github.com/sethkinast/hubitat-mitsubishi-mqtt/
 *
 * Control Mitsubishi heat pumps using HeatPump.cpp via MQTT
 * More info: https://github.com/SwiCago/HeatPump
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
@Field static BigDecimal avoidImmediateCycleDegreesC = 2.0

metadata {
    definition(
        name: 'Mitsubishi Heat Pump MQTT',
        namespace: 'randalln',
        author: 'Randall Norviel <randallndev@gmail.com>',
        importUrl:
            'https://raw.githubusercontent.com/randalln/hubitat-mitsubishi-mqtt/main/src/main/groovy/hubitat-mitsubishi-mqtt.groovy'
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
        input name: "avoidImmediateCycle", type: "bool", title: "Adjust setpoint to avoid an immediate cycle when turned on"
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
    if (gradualAdjustment && state.intermediateSetpoint && payload.operating) {
        BigDecimal currentSetpoint = getSetpointForMode(currentMode)
        BigDecimal targetSetpointC = getTemperatureScale() == 'C' ? currentSetpoint : fahrenheitToCelsius(currentSetpoint)
        BigDecimal gradualSetpointC = calculateGradualSetpoint(currentMode, tempC, targetSetpointC)
        if (gradualSetpointC != state.intermediateSetpoint) {
            graduallyAdjustSetpoint(currentMode, tempC, targetSetpointC)
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
    String rawMode = thermostatModeMapping[payload.mode]
    String fanMode = getThermostatFanMode(payload.power, payload.fan)

    List events = [
        [name: 'thermostatMode', value: mode],
        [name: 'thermostatFanMode', value: fanMode],
    ]

    // in FAN/DRY modes the temperature is meaningless
    if (payload.power == "OFF" || mode == "fan" || mode == "dry") {
        // `operating` is always false in these modes
        events << [name: 'thermostatOperatingState', value: thermostatOperatingStateMapping[mode]]
        state.remove("intermediateSetpoint")
        clearRestoredSetpoint()
    }

    if (rawMode == "cool") {
        if (payload.power != "OFF") {
            // Override the setpoint reported by the heat pump while we're gradually adjusting
            if (state.intermediateSetpoint || state.restoredSetpoint) {
                temperature = device.currentValue('coolingSetpoint')
            }
            updateRunningMode(rawMode)
        }
        events << [name: 'coolingSetpoint', value: temperature, unit: getTemperatureUnit()]
        events << [name: 'thermostatSetpoint', value: temperature, unit: getTemperatureUnit()]

    } else if (rawMode == "heat") {
        if (payload.power != "OFF") {
            // Override the setpoint reported by the heat pump while we're gradually adjusting
            if (state.intermediateSetpoint || state.restoredSetpoint) {
                temperature = device.currentValue('heatingSetpoint')
            }
            updateRunningMode(rawMode)
        }
        events << [name: 'heatingSetpoint', value: temperature, unit: getTemperatureUnit()]
        events << [name: 'thermostatSetpoint', value: temperature, unit: getTemperatureUnit()]

    } else if (payload.power != "OFF") {
        events << [name: 'thermostatSetpoint', value: temperature, unit: getTemperatureUnit()]
        // TODO: read auto mode out of payload and set lastRunningMode
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
    setThermostatMode('cool')
}
void emergencyHeat() {
    heat()
    logDebug 'emergency heat not supported; falling back to heat'
}
void heat() {
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
    runInMillis(2000, "debouncedSetSetpoint", [data: [setpoint: setpoint, thermostatMode: "cool"]])
}

void setHeatingSetpoint(BigDecimal setpoint) {
    runInMillis(2000, "debouncedSetSetpoint", [data: [setpoint: setpoint, thermostatMode: "heat"]])
}

private void debouncedSetSetpoint(Map map) {
    Map data = map.data ?: map // TODO: The hubitat_ci (test) library passes the outer map instead
    final BigDecimal setpointC = convertToHalfCelsius(data.setpoint)
    final String thermostatMode = data.thermostatMode
    if (setpointC && thermostatMode) {
        sendEvent(
                [
                        name: "${thermostatMode}ingSetpoint",
                        value: new BigDecimal(convertTemperatureIfNeeded(setpointC, 'C', 1)),
                        unit : getTemperatureUnit()
                ]
        )
        final String currentMode = device.currentValue('thermostatMode', true)
        if (currentMode == 'auto' && getRunningMode() != thermostatMode // won't work until HeatPump sends x09 payload
        ) {
            logDebug "Not publishing ${thermostatMode}ingSetpoint to ${setpointC}"
        } else {
            clearRestoredSetpoint()
            if (gradualAdjustment) {
                BigDecimal currentTempC = convertInputToCelsius(device.currentValue('temperature'))
                graduallyAdjustSetpoint(thermostatMode, currentTempC, setpointC)
            } else {
                publish(['temperature': setpointC.floatValue()])
            }
        }
    } else {
        String error = !setpointC ? "setpoint" : "thermostatMode"
        log.error "debouncedSetSetpoint missing data $error"
    }
}

private void clearRestoredSetpoint() {
    if (state.restoredSetpoint) {
        logDebug("Clearing saved setpoint")
        state.remove("restoredSetpoint")
    }
}

private BigDecimal graduallyAdjustSetpoint(String mode, BigDecimal tempC, BigDecimal setpointC) {
    BigDecimal gradualSetpointC

    if (mode == "heat") {
        gradualSetpointC = calculateGradualSetpoint("heat", tempC, setpointC)
    } else if (mode == "cool") {
        gradualSetpointC = calculateGradualSetpoint("cool", tempC, setpointC)
    }
    setStateIntermediateSetpoint(gradualSetpointC, setpointC)
    publish(['temperature': gradualSetpointC.floatValue()])

    return gradualSetpointC
}

private static def calculateGradualSetpoint(String mode, BigDecimal currentTempC, BigDecimal targetSetpointC) {
    BigDecimal currentTemp = roundToHalf(currentTempC)
    BigDecimal newSetpoint = roundToHalf(targetSetpointC)

    if (mode == "heat" && newSetpoint > currentTemp + 0.5) {
        return currentTemp + 0.5
    } else if (mode == "cool" && newSetpoint < currentTemp - 0.5) {
        return currentTemp - 0.5
    } else {
        return newSetpoint
    }
}

private void setStateIntermediateSetpoint(BigDecimal gradualSetpointC,  BigDecimal targetSetpointC) {
    if (gradualSetpointC != targetSetpointC) {
        log.info "Gradual setpoint: ${gradualSetpointC.floatValue()}, target setpoint: ${targetSetpointC.floatValue()}"
        state.intermediateSetpoint = gradualSetpointC
    } else {
        if (state.intermediateSetpoint) {
            log.info "Gradual setpoint reached target setpoint: ${gradualSetpointC.floatValue()}"
            state.remove("intermediateSetpoint")
        }
    }
}

/**
 * Send "remoteTemp" to the heat pump, overriding the internal sensor. "0" is the magic value that tells the heat pump to use the internal sensor
 * @param temperature
 */
void setRemoteTemperature(BigDecimal temperature) {
    temperature.with {
        BigDecimal remoteTemperature = 0
        BigDecimal remoteTempC = 0

        if (it != 0) {
            remoteTemperature = it.setScale(1, RoundingMode.HALF_UP)
            remoteTempC = convertToHalfCelsius(it)
            def thermostatOperatingState = device.currentValue('thermostatOperatingState')
            // Add or subtract 0.5C while operating to actually get it to turn off closer to the desired setpoint, but only after the
            // gradualAdjustment period has ended
            if (!state.intermediateSetpoint) {
                if (thermostatOperatingState == "heating") {
                    remoteTempC += 0.5
                } else if (thermostatOperatingState == "cooling") {
                    remoteTempC -= 0.5
                }
            }
        }

        if (remoteTemperature && remoteTempC) {
            sendEvent([name: 'remoteTemperature', value: remoteTemperature])
            publish(['remoteTemp': remoteTempC.floatValue()])
        }
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
            BigDecimal setpointC
            if (avoidImmediateCycle) {
                setpointC = calculateSetback(mode)
                runIn(60, "restoreSetpoint")
            } else if (gradualAdjustment) {
                setpointC = calculateGradualSetpoint(mode)
            } else {
                setpointC = convertToHalfCelsius(getSetpointForMode(mode))
            }
            publish(powerOn([
                'mode': mappedMode,
                'temperature': setpointC.floatValue()
            ]))
            if (mappedMode != 'auto') {
                sendEvent([name: 'thermostatOperatingState', value: 'pending ' + mode])
            }
            break
        case 'fan':
        case 'dry':
            publish(powerOn(['mode': mappedMode]))
            break
    }
}

private BigDecimal calculateGradualSetpoint(String mode) {
    BigDecimal gradualSetpointC = convertToHalfCelsius(getSetpointForMode(mode))

    if (mode == "heat") {
        BigDecimal currentTempC = convertInputToCelsius(device.currentValue('temperature'))
        def currentSetpoint = device.currentValue("heatingSetpoint")
        BigDecimal targetSetpointC = getTemperatureScale() == 'C' ? currentSetpoint : fahrenheitToCelsius(currentSetpoint)
        gradualSetpointC = calculateGradualSetpoint("heat", currentTempC, targetSetpointC)
        setStateIntermediateSetpoint(gradualSetpointC, targetSetpointC)
    }

    return gradualSetpointC
}

private BigDecimal calculateSetback(String thermostatMode) {
    BigDecimal setpoint = getSetpointForMode(thermostatMode)
    BigDecimal setpointC = convertToHalfCelsius(setpoint)
    BigDecimal currentTempC = convertToHalfCelsius(device.currentValue("temperature"))

    // Set the setpoint low enough to not trigger an immediate cycle
    if (thermostatMode == "heat") {
        def tempSetpoint = currentTempC - avoidImmediateCycleDegreesC
        if (tempSetpoint < setpointC) {
            saveSetpoint(setpoint)
            setpointC = tempSetpoint
        }
    } else if (thermostatMode == "cool") {
        def tempSetpoint = currentTempC + avoidImmediateCycleDegreesC
        if (tempSetpoint > setpointC) {
            saveSetpoint(setpoint)
            setpointC = tempSetpoint
        }
    }

    return setpointC
}

private void saveSetpoint(def setpoint) {
    logDebug "Saving setpoint to restore: ${setpoint}"
    state.restoredSetpoint = setpoint
}

private void restoreSetpoint() {
    if (state.restoredSetpoint) {
        logDebug "Restoring setpoint ${state.restoredSetpoint}"
        String mode = device.currentValue("thermostatMode", true)

        if (mode == "heat" || (mode == "auto" && getRunningMode() == "heat")) {
            debouncedSetSetpoint([data: [setpoint: state.restoredSetpoint, thermostatMode: "heat"]])
        } else if (mode == "cool" || (mode == "auto" && getRunningMode() == "cool")) {
            debouncedSetSetpoint([data: [setpoint: state.restoredSetpoint, thermostatMode: "cool"]])
        }
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
