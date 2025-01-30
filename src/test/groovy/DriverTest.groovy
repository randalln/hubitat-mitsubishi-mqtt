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
import spock.lang.Shared
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
    @Shared
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

    def calculateGradualSetpointHeating() {
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
        script.calculateGradualSetpointHeating(currentTempC, targetSetpointC) == newSetpoint

        where:
        currentTempC | targetSetpointC | newSetpoint
        18.2         | 20              | 18.5
        18.5         | 20              | 19.0
        18.9         | 20              | 19.5
        19.8         | 20              | 20.0
        19.8         | 20              | 20.0
        21.0         | 20              | 20.0
    }

    def graduallyAdjustSetpointHeating() {
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
        script.graduallyAdjustSetpointHeating(currentTempC, targetSetpointC) == newSetpoint

        then:
        publishes * mqtt.publish("heatpump/set", "{\"temperature\":${newSetpoint}}")
        state.intermediateSetpoint == intermediateSetpoint

        where:
        currentTempC | targetSetpointC | newSetpoint | intermediateSetpoint | publishes
        18.2         | 20              | 18.5        | newSetpoint          | 1
        18.5         | 20              | 19.0        | newSetpoint          | 1
        18.9         | 20              | 19.5        | newSetpoint          | 1
        19.8         | 20              | 20.0        | null                 | 1
        20   | 20 | 20.0 | null | 1
        21.0 | 20 | 20.0 | null | 1
    }

    def "processTemperatureUpdate() Celsius heating"() {
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
        state.intermediateSetpoint = intermediateSetpointBefore
        def events = script.processTemperatureUpdate([roomTemperature: tempC, operating: true])

        then:
        events == [
                [name: "temperature", value: tempC, unit: "°C", descriptionText: "Temperature is ${tempC}°C"],
                [name: "thermostatOperatingState", value: "heating"]
        ]
        publishes * mqtt.publish("heatpump/set", "{\"temperature\":${gradualTempC}}") // gradualAdjustment side-effect
        state.intermediateSetpoint == intermediateSetpointAfter

        where:
        tempC | gradualTempC | publishes | intermediateSetpointBefore | intermediateSetpointAfter
        18.5  | 19.0         | 1         | 18.5                       | gradualTempC
        19.0  | 19.5         | 1         | 19.0                       | gradualTempC
        19.5  | 20.0         | 1         | 19.5                       | gradualTempC
        20.0  | 20.5         | 1         | 20.0                       | null
        20.5  | 20.5         | 0         | null                       | null
        21.0  | 20.5         | 0         | null                       | null
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
        19.5        | 20.5
        20.0        | 20.5
        20.5        | 20.5
        21.0        | 20.5
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
        19.5        | 68.9
        20.0        | 68.9
        21.0        | 68.9
        22.0        | 68.9
    }

    def "debouncedSetHeatingSetpoint Fahrenheit"() {
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
        script.debouncedSetHeatingSetpoint([data: [setpoint: 67.0]])

        then:
        1 * executorApi.sendEvent(["name": "heatingSetpoint", "value": 67.1, "unit": "°F"])
    }

    def "debouncedSetHeatingSetpoint Fahrenheit gradualAdjustment"() {
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
        script.debouncedSetHeatingSetpoint([data: [setpoint: setpointF]])

        then:
        1 * executorApi.sendEvent(["name": "heatingSetpoint", "value": newSetpointF, "unit": "°F"])
        1 * mqtt.publish("heatpump/set", "{\"temperature\":${newSetpointC}}")
        state.intermediateSetpoint == intermediateAfter

        where:
        currentTemp | setpointF | newSetpointF | newSetpointC | intermediateBefore | intermediateAfter
        65.3        | 66.0      | 66.2         | 19.0         | null               | null
        66.2        | 68.0      | 68.0         | 19.5         | null               | newSetpointC
        66.2        | 68.0      | 68.0         | 19.5         | 67.1               | newSetpointC
        68.0        | 63.0      | 62.6         | 17.0         | 19.5               | null
    }

    def "debouncedSetHeatingSetpoint Celsius gradualAdjustment"() {
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
        script.debouncedSetHeatingSetpoint([data: [setpoint: 20.5]])

        then:
        1 * executorApi.sendEvent(["name": "heatingSetpoint", "value": 20.5, "unit": "°C"])
        1 * mqtt.publish("heatpump/set", "{\"temperature\":19.5}")
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
}
