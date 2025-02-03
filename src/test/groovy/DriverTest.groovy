// SPDX-FileCopyrightText: 2025 Randall Norviel <randallndev@gmail.com>
//
// SPDX-License-Identifier: MIT

import me.biocomp.hubitat_ci.api.common_api.DeviceWrapper
import me.biocomp.hubitat_ci.api.common_api.InterfaceHelper
import me.biocomp.hubitat_ci.api.common_api.Log
import me.biocomp.hubitat_ci.api.common_api.Mqtt
import me.biocomp.hubitat_ci.api.device_api.DeviceExecutor
import me.biocomp.hubitat_ci.device.HubitatDeviceSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

import java.math.RoundingMode

class DriverTest extends Specification {
    HubitatDeviceSandbox sandbox = new HubitatDeviceSandbox(new File("src/main/groovy/hubitat-mitsubishi-mqtt.groovy"))
    Log logger = Mock {
        trace(_) >> { args -> println(args) }
        debug(_) >> { args -> println(args) }
        info(_) >> { args -> println(args) }
        warn(_) >> { args -> println(args) }
        error(_) >> { args -> println(args) }
    }
    DeviceWrapper device
    Mqtt mqtt
    InterfaceHelper interfaceHelper
    DeviceExecutor executorApi
    Map data = [:]
    Map state = [:]

    void initState(String temperatureScale = "F") {
        device = Mock {
        }
        mqtt = Mock {
        }
        interfaceHelper = Mock {
            getMqtt() >> mqtt
        }
        executorApi = Mock {
            celsiusToFahrenheit(*_) >> { args ->
                (args[0] * 1.8 + 32).setScale(1, RoundingMode.HALF_UP)
            }
            convertTemperatureIfNeeded(*_) >> { args ->
                def ret = args[0]
                if (args[1] != temperatureScale) {
                    if (temperatureScale == 'F') {
                        ret = args[0] * 1.8 + 32
                    } else {
                        ret = (args[0] - 32) / 1.8
                    }
                }
                ret.setScale(1, RoundingMode.HALF_UP)
            }
            getDataValue(_) >> { args -> data[args[0]] }
            getDevice() >> device
            getProperty("topicRoot") >> "heatpump"
            getState() >> state
            getTemperatureScale() >> temperatureScale
            fahrenheitToCelsius(_) >> { args -> (args[0] - 32) / 1.8 }
            log >> logger
            interfaces >> interfaceHelper
        }
    }

    def "convertToHalfCelsius() from Fahrenheit"() {
        given:
        initState()
        final def script = sandbox.run(
                api: executorApi,
                validationFlags: [
                        Flags.DontRestrictGroovy,
                        Flags.DontRunScript
                ]
        )

        expect:
        script.convertToHalfCelsius(tempF) == tempC

        where:
        tempF | tempC
        66.6  | 19
        66.7  | 19.5
        67.1  | 19.5
        67.6  | 20
        68    | 20
    }

    def "convertToHalfCelsius() from Celsius"() {
        given:
        initState("C")
        final def script = sandbox.run(
                api: executorApi,
                userSettingValues: [
                        Input3: "debugLoggingEnabled", debugLoggingEnabled: true
                ],
                validationFlags: [
                        Flags.DontRestrictGroovy,
                        Flags.DontRunScript
                ]
        )

        expect:
        script.convertToHalfCelsius(tempC) == tempRounded

        where:
        tempC | tempRounded
        19.2  | 19
        19.3  | 19.5
        19.5  | 19.5
        19.8  | 20
    }

    def calculateGradualSetpoint() {
        given:
        initState()
        final def script = sandbox.run(
                api: executorApi,
                customizeScriptBeforeRun: { script ->
                    script.getMetaClass().logDebug = { args -> println(args) }
                },
                userSettingValues: [Input1: "Provided value", debugLoggingEnabled: true],
                validationFlags: [
                        Flags.DontRestrictGroovy,
                        Flags.DontRunScript
                ]
        )

        expect:
        script.calculateGradualSetpoint(thermostatMode, currentTempC, targetSetpointC) == newSetpoint

        where:
        thermostatMode | currentTempC | targetSetpointC | newSetpoint
        "heat"         | 18.2         | 20              | 18.5
        "heat"         | 18.5         | 20              | 19.0
        "heat"         | 18.9         | 20              | 19.5
        "heat"         | 19.8         | 20              | 20.0
        "heat"         | 21.0         | 20              | 20.0
        "cool"         | 21.8         | 20              | 21.5
        "cool"         | 21.5         | 20              | 21.0
        "cool"         | 21.1         | 20              | 20.5
        "cool"         | 20.2         | 20              | 20.0
        "cool"         | 19.0         | 20              | 20.0
    }

    def "graduallyAdjustSetpoint heat"() {
        given:
        initState()
        device.currentValue("thermostatMode") >> "heat"
        final def script = sandbox.run(
                api: executorApi,
                userSettingValues: [
                        Input1: "topicRoot", topicRoot: "heatpump",
                        Input2: "gradualAdjustment", gradualAdjustment: true,
                        Input3: "debugLoggingEnabled", debugLoggingEnabled: true
                ],
                validationFlags: [
                        Flags.DontRestrictGroovy,
                        Flags.DontRunScript
                ]
        )

        when:
        script.graduallyAdjustSetpoint("heat", currentTempC, targetSetpointC) == newSetpoint

        then:
        publishes * mqtt.publish("heatpump/set", "{\"temperature\":${newSetpoint}}")
        state.intermediateSetpoint == intermediateSetpoint

        where:
        currentTempC | targetSetpointC | newSetpoint | intermediateSetpoint | publishes
        18.2         | 20              | 18.5        | newSetpoint          | 1
        18.5         | 20              | 19.0        | newSetpoint          | 1
        18.9         | 20              | 19.5        | newSetpoint          | 1
        19.8         | 20              | 20.0        | null                 | 1
        20.0 | 20 | 20.0 | null | 1
        21.0 | 20 | 20.0 | null | 1
    }

    def "graduallyAdjustSetpoint cool"() {
        given:
        initState()
        device.currentValue("thermostatMode") >> "cool"
        final def script = sandbox.run(
                api: executorApi,
                userSettingValues: [
                        Input1: "topicRoot", topicRoot: "heatpump",
                        Input2: "gradualAdjustment", gradualAdjustment: true,
                        Input3: "debugLoggingEnabled", debugLoggingEnabled: true
                ],
                validationFlags: [
                        Flags.DontRestrictGroovy,
                        Flags.DontRunScript
                ]
        )

        when:
        script.graduallyAdjustSetpoint("cool", currentTempC, targetSetpointC) == newSetpoint

        then:
        publishes * mqtt.publish("heatpump/set", "{\"temperature\":${newSetpoint}}")
        state.intermediateSetpoint == intermediateSetpoint

        where:
        currentTempC | targetSetpointC | newSetpoint | intermediateSetpoint | publishes
        21.8         | 20              | 21.5        | newSetpoint          | 1
        21.5         | 20              | 21.0        | newSetpoint          | 1
        21.1         | 20              | 20.5        | newSetpoint          | 1
        20.2         | 20              | 20.0        | null                 | 1
        20.0         | 20              | 20.0        | null                 | 1
        19.0         | 20              | 20.0        | null                 | 1
    }

    def "processTemperatureUpdate() Celsius"() {
        given:
        initState("C")
        String mode
        device.currentValue("thermostatMode") >> { mode }
        device.currentValue("heatingSetpoint") >> 20.5
        device.currentValue("coolingSetpoint") >> 20.5
        final def script = sandbox.run(
                api: executorApi,
                userSettingValues: [
                        Input1: "topicRoot", topicRoot: "heatpump",
                        Input2: "gradualAdjustment", gradualAdjustment: true,
                        Input3: "debugLoggingEnabled", debugLoggingEnabled: true
                ],
                validationFlags: [
                        Flags.DontRestrictGroovy,
                        Flags.DontRunScript
                ]
        )

        when:
        mode = thermostatMode
        state.intermediateSetpoint = intermediateSetpointBefore
        def events = script.processTemperatureUpdate([roomTemperature: tempC, operating: true])

        then:
        events == [
                [name: "temperature", value: tempC, unit: "°C", descriptionText: "Temperature is ${tempC}°C"],
                [name: "thermostatOperatingState", value: "${thermostatMode}ing"]
        ]
        publishes * mqtt.publish("heatpump/set", "{\"temperature\":${gradualTempC}}") // gradualAdjustment side-effect
        state.intermediateSetpoint == intermediateSetpointAfter

        where:
        thermostatMode | tempC | gradualTempC | publishes | intermediateSetpointBefore | intermediateSetpointAfter
        "heat"         | 18.5  | 19.0         | 1         | tempC                      | gradualTempC
        "heat"         | 19.0  | 19.5         | 1         | tempC                      | gradualTempC
        "heat"         | 19.5  | 20.0         | 1         | tempC                      | gradualTempC
        "heat"         | 20.0  | 20.5         | 1         | tempC                      | null
        "heat"         | 20.5  | 20.5         | 0         | null                       | null
        "heat"         | 21.0  | 20.5         | 0         | null                       | null
        "cool"         | 22.5  | 22.0         | 1         | tempC                      | gradualTempC
        "cool"         | 22.0  | 21.5         | 1         | tempC                      | gradualTempC
        "cool"         | 21.5  | 21.0         | 1         | tempC                      | gradualTempC
        "cool"         | 21.0  | 20.5         | 1         | tempC                      | null
        "cool"         | 20.5  | 20.5         | 0         | null                       | null
        "cool"         | 20.0  | 20.5         | 0         | null                       | null
    }

    def "processTemperatureUpdate() Fahrenheit heating"() {
        given:
        initState()
        device.currentValue("thermostatMode") >> "heat"
        device.currentValue("heatingSetpoint") >> 68.9
        final def script = sandbox.run(
                api: executorApi,
                userSettingValues: [
                        Input1: "topicRoot", topicRoot: "heatpump",
                        Input2: "gradualAdjustment", gradualAdjustment: true,
                        Input3: "debugLoggingEnabled", debugLoggingEnabled: true
                ],
                validationFlags: [
                        Flags.DontRestrictGroovy,
                        Flags.DontRunScript
                ]
        )

        when:
        state.intermediateSetpoint = intermediateSetpoint
        def events = script.processTemperatureUpdate([roomTemperature: tempC, operating: true])

        then:
        events == [
                [name: "temperature", value: tempF, unit: "°F", descriptionText: "Temperature is ${tempF}°F"],
                [name: "thermostatOperatingState", value: "heating"]
        ]
        publishes * mqtt.publish("heatpump/set", "{\"temperature\":${gradualTempC}}") // gradualAdjustment side-effect

        where:
        tempC | tempF | gradualTempC | publishes | intermediateSetpoint
        19.5  | 67.1  | 20.0         | 1         | 19.5
        20.0  | 68.0  | 20.5         | 1         | 20.0
        20.5  | 68.9  | 20.5         | 0         | null
    }

    def "parse processTemperatureUpdate"() {
        given:
        initState()
        mqtt.parseMessage(_) >> { arg ->
            def matcher = arg =~ /: *([0-9]+\.?[0-9]*),/
            [topic: "heatpump/status", payload: "{\"roomTemperature\":${matcher[0][1]},\"operating\":false}"]
        }
        final def script = sandbox.run(
                api: executorApi,
                customizeScriptBeforeRun: { script ->
                    script.getMetaClass().logDebug = { args -> println(args) }
                },
                userSettingValues: [
                        Input1: "topicRoot", topicRoot: "heatpump",
                        Input2: "gradualAdjustment", gradualAdjustment: false,
                        Input3: "debugLoggingEnabled", debugLoggingEnabled: true
                ],
                validationFlags: [
                        Flags.DontRestrictGroovy,
                        Flags.DontRunScript
                ]
        )

        when:
        script.parse('{"roomTemperature":19.5,"operating":false}')

        then:
        1 * executorApi.sendEvent(["name": "temperature", "value": 67.1, "unit": "°F", "descriptionText": "Temperature is 67.1°F"])
        1 * executorApi.sendEvent(["name": "thermostatOperatingState", "value": "idle"])
    }

    def "processOperatingUpdate Celsius"() {
        given:
        initState("C")
        device.currentValue("thermostatMode") >> "heat"
        device.currentValue("heatingSetpoint") >> 20.5
        final def script = sandbox.run(
                api: executorApi,
                userSettingValues: [
                        Input1: "topicRoot", topicRoot: "heatpump",
                        Input2: "gradualAdjustment", gradualAdjustment: false,
                        Input3: "debugLoggingEnabled", debugLoggingEnabled: true
                ],
                validationFlags: [
                        Flags.DontRestrictGroovy,
                        Flags.DontRunScript
                ]
        )

        when:
        def events = script.processOperatingUpdate(
                ["power": "ON", "mode": "HEAT", "temperature": temperature,
                 "fan"  : "3", "vane": "4", "wideVane": "<<"]
        )

        then:
        events[0] == [name: "thermostatMode", value: "heat"]
        events[1] == [name: "thermostatFanMode", value: "3"]
        events[2] == [name: "heatingSetpoint", value: setpoint, unit: "°C"]
        events[3] == [name: "thermostatSetpoint", value: setpoint, unit: "°C"]

        where:
        temperature | setpoint
        17.5        | 17.5
        18.0        | 18.0
    }

    def "processOperatingUpdate Celsius gradualAdjustment"() {
        given:
        initState("C")
        device.currentValue("thermostatMode") >> "heat"
        device.currentValue("heatingSetpoint") >> 20.5
        final def script = sandbox.run(
                api: executorApi,
                userSettingValues: [
                        Input1: "topicRoot", topicRoot: "heatpump",
                        Input2: "gradualAdjustment", gradualAdjustment: true,
                        Input3: "debugLoggingEnabled", debugLoggingEnabled: true
                ],
                validationFlags: [
                        Flags.DontRestrictGroovy,
                        Flags.DontRunScript
                ]
        )

        when:
        state.intermediateSetpoint = intermediateSetpoint
        def events = script.processOperatingUpdate(
                ["power": "ON", "mode": "HEAT", "temperature": temperature,
                 "fan"  : "3", "vane": "4", "wideVane": "<<"]
        )

        then:
        events[0] == [name: "thermostatMode", value: "heat"]
        events[1] == [name: "thermostatFanMode", value: "3"]
        events[2] == [name: "heatingSetpoint", value: setpoint, unit: "°C"]
        events[3] == [name: "thermostatSetpoint", value: setpoint, unit: "°C"]

        where:
        temperature | setpoint | intermediateSetpoint
        19.5        | 20.5     | 20.0
        20.0        | 20.5     | 20.0
        20.5        | 20.5     | 20.0
        21.0        | 20.5     | 20.0
    }

    def "processOperatingUpdate Fahrenheit"() {
        given:
        initState()
        device.currentValue("thermostatMode") >> "heat"
        device.currentValue("heatingSetpoint") >> 68.9
        final def script = sandbox.run(
                api: executorApi,
                userSettingValues: [
                        Input1: "topicRoot", topicRoot: "heatpump",
                        Input2: "gradualAdjustment", gradualAdjustment: false,
                        Input3: "debugLoggingEnabled", debugLoggingEnabled: true
                ],
                validationFlags: [
                        Flags.DontRestrictGroovy,
                        Flags.DontRunScript
                ]
        )

        when:
        def events = script.processOperatingUpdate(
                ["power": "ON", "mode": "HEAT", "temperature": temperature,
                 "fan"  : "3", "vane": "4", "wideVane": "<<"]
        )

        then:
        events[0] == [name: "thermostatMode", value: "heat"]
        events[1] == [name: "thermostatFanMode", value: "3"]
        events[2] == [name: "heatingSetpoint", value: setpointF, unit: "°F"]
        events[3] == [name: "thermostatSetpoint", value: setpointF, unit: "°F"]

        where:
        temperature | setpointF
        17.5        | 63.5
        18.0        | 64.4
    }

    def "processOperatingUpdate Fahrenheit gradualAdjustment"() {
        given:
        initState()
        device.currentValue("thermostatMode") >> "heat"
        device.currentValue("heatingSetpoint") >> 68.9
        final def script = sandbox.run(
                api: executorApi,
                userSettingValues: [
                        Input1: "topicRoot", topicRoot: "heatpump",
                        Input2: "gradualAdjustment", gradualAdjustment: true,
                        Input3: "debugLoggingEnabled", debugLoggingEnabled: true
                ],
                validationFlags: [
                        Flags.DontRestrictGroovy,
                        Flags.DontRunScript
                ]
        )

        when:
        state.intermediateSetpoint = intermediateSetpoint
        def events = script.processOperatingUpdate(
                ["power": "ON", "mode": "HEAT", "temperature": temperature,
                 "fan"  : "3", "vane": "4", "wideVane": "<<"]
        )

        then:
        events[0] == [name: "thermostatMode", value: "heat"]
        events[1] == [name: "thermostatFanMode", value: "3"]
        events[2] == [name: "heatingSetpoint", value: setpointF, unit: "°F"]
        events[3] == [name: "thermostatSetpoint", value: setpointF, unit: "°F"]

        where:
        temperature | setpointF | intermediateSetpoint
        19.5        | 68.9      | 20.0
        20.0        | 68.9      | 20.0
        21.0        | 68.9      | 20.0
        22.0        | 68.9      | 20.0
    }

    def "debouncedSetSetpoint Fahrenheit"() {
        given:
        initState()
        device.currentValue("thermostatMode", *_) >> "heat"
        device.currentValue("heatingSetpoint") >> 68.9
        final def script = sandbox.run(
                api: executorApi,
                userSettingValues: [
                        Input1: "topicRoot", topicRoot: "heatpump",
                        Input2: "gradualAdjustment", gradualAdjustment: false,
                        Input3: "debugLoggingEnabled", debugLoggingEnabled: true
                ],
                validationFlags: [
                        Flags.DontRestrictGroovy,
                        Flags.DontRunScript
                ]
        )

        when:
        script.debouncedSetSetpoint([data: [setpoint: 67.0, thermostatMode: "heat"]])

        then:
        1 * executorApi.sendEvent(["name": "heatingSetpoint", "value": 67.1, "unit": "°F"])
        state.restoredSetpoint == null
    }

    def "debouncedSetSetpoint Fahrenheit gradualAdjustment"() {
        given:
        def temp
        initState()
        device.currentValue("thermostatMode", *_) >> "heat"
        device.currentValue("temperature") >> { temp }
        final def script = sandbox.run(
                api: executorApi,
                userSettingValues: [
                        Input1: "topicRoot", topicRoot: "heatpump",
                        Input2: "gradualAdjustment", gradualAdjustment: true,
                        Input3: "debugLoggingEnabled", debugLoggingEnabled: true
                ],
                validationFlags: [
                        Flags.DontRestrictGroovy,
                        Flags.DontRunScript
                ]
        )

        when:
        temp = currentTemp
        state.intermediateSetpoint = intermediateBefore
        script.debouncedSetSetpoint([data: [setpoint: setpointF, thermostatMode: "heat"]])

        then:
        1 * executorApi.sendEvent(["name": "heatingSetpoint", "value": newSetpointF, "unit": "°F"])
        1 * mqtt.publish("heatpump/set", "{\"temperature\":${newSetpointC}}")
        state.intermediateSetpoint == intermediateAfter
        state.restoredSetpoint == null

        where:
        currentTemp | setpointF | newSetpointF | newSetpointC | intermediateBefore | intermediateAfter
        65.3        | 66.0      | 66.2         | 19.0         | null               | null
        66.2        | 68.0      | 68.0         | 19.5         | null               | newSetpointC
        66.2        | 68.0      | 68.0         | 19.5         | 67.1               | newSetpointC
        68.0        | 63.0      | 62.6         | 17.0         | 19.5               | null
    }

    def "debouncedSetSetpoint Celsius gradualAdjustment"() {
        given:
        initState("C")
        device.currentValue("thermostatMode", *_) >> "heat"
        device.currentValue("temperature") >> 19.0
        final def script = sandbox.run(
                api: executorApi,
                userSettingValues: [
                        Input1: "topicRoot", topicRoot: "heatpump",
                        Input2: "gradualAdjustment", gradualAdjustment: true,
                        Input3: "debugLoggingEnabled", debugLoggingEnabled: true
                ],
                validationFlags: [
                        Flags.DontRestrictGroovy,
                        Flags.DontRunScript
                ]
        )

        when:
        script.debouncedSetSetpoint([data: [setpoint: 20.5, thermostatMode: "heat"]])

        then:
        1 * executorApi.sendEvent(["name": "heatingSetpoint", "value": 20.5, "unit": "°C"])
        1 * mqtt.publish("heatpump/set", "{\"temperature\":19.5}")
        state.restoredSetpoint == null
    }

    def "setRemoteTemperature Celsius"() {
        given:
        def thermostatOperatingState
        initState("C")
        device.currentValue("thermostatOperatingState") >> { thermostatOperatingState }
        final def script = sandbox.run(
                api: executorApi,
                userSettingValues: [
                        Input1: "topicRoot", topicRoot: "heatpump",
                        Input2: "gradualAdjustment", gradualAdjustment: true,
                        Input3: "debugLoggingEnabled", debugLoggingEnabled: true
                ],
                validationFlags: [
                        Flags.DontRestrictGroovy,
                        Flags.DontRunScript
                ]
        )

        when:
        state.intermediateSetpoint = intermediateSetpoint
        thermostatOperatingState = operatingState
        script.setRemoteTemperature(inputTemp)

        then:
        1 * executorApi.sendEvent(["name": "remoteTemperature", "value": remoteTemperature])
        1 * mqtt.publish("heatpump/set", "{\"remoteTemp\":${remoteTempC}}")

        where:
        inputTemp | remoteTemperature | remoteTempC | operatingState | intermediateSetpoint
        18.33     | 18.3              | 18.5        | "idle"         | null
        18.33     | 18.3              | 19.0        | "heating"      | null
        18.33     | 18.3              | 18.0        | "cooling"      | null
        18.33     | 18.3              | 18.5        | "heating"      | 19.0
        18.33     | 18.3              | 18.5        | "cooling"      | 19.0

    }

    def "setRemoteTemperature Fahrenheit"() {
        given:
        def thermostatOperatingState
        initState()
        device.currentValue("thermostatOperatingState") >> { thermostatOperatingState }
        final def script = sandbox.run(
                api: executorApi,
                userSettingValues: [
                        Input1: "topicRoot", topicRoot: "heatpump",
                        Input2: "gradualAdjustment", gradualAdjustment: true,
                        Input3: "debugLoggingEnabled", debugLoggingEnabled: true
                ],
                validationFlags: [
                        Flags.DontRestrictGroovy,
                        Flags.DontRunScript
                ]
        )

        when:
        state.intermediateSetpoint = intermediateSetpoint
        thermostatOperatingState = operatingState
        script.setRemoteTemperature(inputTemp)

        then:
        1 * executorApi.sendEvent(["name": "remoteTemperature", "value": remoteTemperature])
        1 * mqtt.publish("heatpump/set", "{\"remoteTemp\":${remoteTempC}}")

        where:
        inputTemp | remoteTemperature | remoteTempC | operatingState | intermediateSetpoint
        66.33     | 66.3              | 19.0        | "idle"         | null
        66.33     | 66.3              | 19.5        | "heating"      | null
        66.33     | 66.3              | 18.5        | "cooling"      | null
        66.33     | 66.3              | 19.0        | "heating"      | 19.0
        66.33     | 66.3              | 19.0        | "cooling"      | 19.0
    }

    def "setRemoteTemperature Fahrenheit revert to internal sensor"() {
        given:
        def thermostatOperatingState
        initState()
        device.currentValue("thermostatOperatingState") >> { thermostatOperatingState }
        final def script = sandbox.run(
                api: executorApi,
                userSettingValues: [
                        Input1: "topicRoot", topicRoot: "heatpump",
                        Input2: "gradualAdjustment", gradualAdjustment: true,
                        Input3: "debugLoggingEnabled", debugLoggingEnabled: true
                ],
                validationFlags: [
                        Flags.DontRestrictGroovy,
                        Flags.DontRunScript
                ]
        )

        when:
        state.intermediateSetpoint = intermediateSetpoint
        thermostatOperatingState = operatingState
        script.setRemoteTemperature(inputTemp)

        then:
        1 * executorApi.sendEvent(["name": "remoteTemperature", "value": remoteTemperature])
        1 * mqtt.publish("heatpump/set", "{\"remoteTemp\":${remoteTempC}}")

        where:
        inputTemp | remoteTemperature | remoteTempC | operatingState | intermediateSetpoint
        66.33     | 66.3              | 19.0        | "idle"         | null
        66.33     | 66.3              | 19.5        | "heating"      | null
        66.33     | 66.3              | 18.5        | "cooling"      | null
        66.33     | 66.3              | 19.0        | "heating"      | 19.0
        66.33     | 66.3              | 19.0        | "cooling"      | 19.0
    }

    def setThermostatMode() {
        given:
        initState()
        BigDecimal setpoint
        BigDecimal temp
        device.currentValue("coolingSetpoint") >> { setpoint }
        device.currentValue("heatingSetpoint") >> { setpoint }
        device.currentValue("temperature") >> { temp }
        final def script = sandbox.run(
                api: executorApi,
                userSettingValues: [
                        Input1: "topicRoot", topicRoot: "heatpump",
                        Input2: "gradualAdjustment", gradualAdjustment: false,
                        Input3: "debugLoggingEnabled", debugLoggingEnabled: true
                ],
                validationFlags: [
                        Flags.DontRestrictGroovy,
                        Flags.DontRunScript
                ]
        )

        when:
        setpoint = setpointF
        temp = currentTemp
        script.setThermostatMode(mode)

        then:
        1 * mqtt.publish("heatpump/set", "{\"power\":\"ON\",\"mode\":\"${hpMode}\",\"temperature\":${setpointC}}")
        state.intermediateSetpoint == null

        where:
        mode   | hpMode | currentTemp | setpointF | setpointC
        "heat" | "HEAT" | 64.0        | 67.1      | 19.5
        "heat" | "HEAT" | 64.0        | 68.0      | 20.0
        "heat" | "HEAT" | 67.0        | 68.0      | 20.0
        "heat" | "HEAT" | 69.0        | 68.0      | 20.0
        "cool" | "COOL" | 70.0        | 67.1      | 19.5
        "cool" | "COOL" | 70.0        | 66.2      | 19.0
        "cool" | "COOL" | 67.0        | 66.2      | 19.0
        "cool" | "COOL" | 65.0        | 66.2      | 19.0
    }

    def "setThermostatMode gradualAdjustment"() {
        given:
        initState()
        BigDecimal setpoint
        BigDecimal temp
        device.currentValue("coolingSetpoint") >> { setpoint }
        device.currentValue("heatingSetpoint") >> { setpoint }
        device.currentValue("temperature") >> { temp }
        final def script = sandbox.run(
                api: executorApi,
                userSettingValues: [
                        Input1: "topicRoot", topicRoot: "heatpump",
                        Input2: "gradualAdjustment", gradualAdjustment: true,
                        Input3: "debugLoggingEnabled", debugLoggingEnabled: true
                ],
                validationFlags: [
                        Flags.DontRestrictGroovy,
                        Flags.DontRunScript
                ]
        )

        when:
        setpoint = setpointF
        temp = currentTemp
        script.setThermostatMode(mode)

        then:
        1 * mqtt.publish("heatpump/set", "{\"power\":\"ON\",\"mode\":\"${hpMode}\",\"temperature\":${setpointC}}")
        state.intermediateSetpoint == intermediateSetpoint

        where:
        mode   | hpMode | currentTemp | setpointF | setpointC | intermediateSetpoint
        "heat" | "HEAT" | 64.0        | 67.1      | 18.5      | setpointC
        "heat" | "HEAT" | 64.0        | 68.0      | 18.5      | setpointC
        "heat" | "HEAT" | 67.0        | 68.0      | 20.0      | null
        "heat" | "HEAT" | 69.0        | 68.0      | 20.0      | null
        "cool" | "COOL" | 70.0        | 67.1      | 19.5      | null // TODO: Implement gradual cooling
        "cool" | "COOL" | 70.0        | 66.2      | 19.0      | null
        "cool" | "COOL" | 67.0        | 66.2      | 19.0      | null
        "cool" | "COOL" | 65.0        | 66.2      | 19.0      | null
    }

    def calculateSetback() {
        given:
        initState()
        BigDecimal setpoint
        BigDecimal temp
        device.currentValue("coolingSetpoint") >> { setpoint }
        device.currentValue("heatingSetpoint") >> { setpoint }
        device.currentValue("temperature") >> { temp }
        final def script = sandbox.run(
                api: executorApi,
                userSettingValues: [
                        Input4: "debugLoggingEnabled", debugLoggingEnabled: true
                ],
                validationFlags: [
                        Flags.DontRestrictGroovy,
                        Flags.DontRunScript
                ]
        )

        when:
        setpoint = setpointF
        temp = currentTempF
        def result = script.calculateSetback(mode)

        then:
        result == setbackC

        where:
        currentTempF | setpointF | setbackC | mode
        64.8         | 68.0      | 17.0     | "heat"
        67.8         | 68.0      | 19.0     | "heat"
        68.0         | 68.0      | 19.0     | "heat"
        69.0         | 68.9      | 19.5     | "heat"
        75.3         | 68.0      | 25.0     | "cool"
        68.2         | 68.0      | 21.0     | "cool"
    }

    def "setThermostatMode avoidImmediateCycle"() {
        given:
        initState()
        BigDecimal setpoint
        BigDecimal temp
        device.currentValue("coolingSetpoint") >> { setpoint }
        device.currentValue("heatingSetpoint") >> { setpoint }
        device.currentValue("temperature") >> { temp }
        final def script = sandbox.run(
                api: executorApi,
                userSettingValues: [
                        Input1: "topicRoot", topicRoot: "heatpump",
                        Input2: "gradualAdjustment", gradualAdjustment: false,
                        Input3: "avoidImmediateCycle", avoidImmediateCycle: true,
                        Input4: "debugLoggingEnabled", debugLoggingEnabled: true
                ],
                validationFlags: [
                        Flags.DontRestrictGroovy,
                        Flags.DontRunScript
                ]
        )

        when:
        setpoint = setpointF
        temp = currentTemp
        script.setThermostatMode(mode)

        then:
        1 * mqtt.publish("heatpump/set", "{\"power\":\"ON\",\"mode\":\"${hpMode}\",\"temperature\":${setpointC}}")
        state.intermediateSetpoint == null
        state.restoredSetpoint == setpointF

        where:
        mode   | hpMode | currentTemp | setpointF | setpointC
        "heat" | "HEAT" | 64.4        | 67.1      | 17.0
        "heat" | "HEAT" | 64.0        | 68.0      | 17.0
        "heat" | "HEAT" | 67.0        | 68.0      | 18.5
        "heat" | "HEAT" | 69.0        | 68.0      | 19.5
        "cool" | "COOL" | 70.0        | 67.1      | 22.0
        "cool" | "COOL" | 70.0        | 66.2      | 22.0
        "cool" | "COOL" | 67.0        | 66.2      | 20.5
        "cool" | "COOL" | 65.0        | 66.2      | 19.5
    }

    def "restore setpoint after avoidImmediateCycle"() {
        given:
        initState()
        String mode
        device.currentValue("thermostatMode", *_) >> { mode }
        device.currentValue("heatingSetpoint") >> 68.0
        device.currentValue("coolingSetpoint") >> 68.0
        final def script = sandbox.run(
                api: executorApi,
                userSettingValues: [
                        Input1: "topicRoot", topicRoot: "heatpump",
                        Input2: "gradualAdjustment", gradualAdjustment: false,
                        Input3: "debugLoggingEnabled", debugLoggingEnabled: true
                ],
                validationFlags: [
                        Flags.DontRestrictGroovy,
                        Flags.DontRunScript
                ]
        )

        when:
        mode = lastRunningMode
        data.lastRunningMode = thermostatMode
        state.restoredSetpoint = restoredSetpoint
        script.restoreSetpoint()

        then:
        1 * executorApi.sendEvent(["name": "${setpointType}", "value": restoredSetpoint, "unit": "°F"])
        state.restoredSetpoint == null

        where:
        thermostatMode | lastRunningMode | setpointType      | restoredSetpoint
        "heat"         | thermostatMode  | "heatingSetpoint" | 68.9
        "cool"         | thermostatMode  | "coolingSetpoint" | 68.9
        "auto"         | "heat"          | "heatingSetpoint" | 68.0
        "auto"         | "cool"          | "coolingSetpoint" | 68.0
    }
}
