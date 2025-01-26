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
    HubitatDeviceSandbox sandbox = new HubitatDeviceSandbox(new File("../hubitat-mitsubishi-mqtt.groovy"))
    Log logger = Mock {
        debug(_) >> { args -> println(args) }
    }
    DeviceWrapper device
    Mqtt mqtt
    InterfaceHelper interfaceHelper
    DeviceExecutor executorApi

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

        expect:
        script.graduallyAdjustSetpointHeating(currentTempC, targetSetpointC) == newSetpoint

        where:
        currentTempC | targetSetpointC | newSetpoint
        18.2         | 20              | 18.5
        18.5         | 20              | 19.0
        18.9         | 20              | 19.5
        19.8         | 20              | 20.0
        19.8         | 20              | 20.0
        21.0         | 20              | 20.0
    }

    def "processTemperatureUpdate() Celsius"() {
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
        def events = script.processTemperatureUpdate([roomTemperature: tempC, operating: false])

        then:
        events == [
                [name: "temperature", value: tempC, unit: "°C", descriptionText: "Temperature is ${tempC}°C"],
                [name: "thermostatOperatingState", value: "idle"]
        ]
        1 * mqtt.publish("heatpump/set", "{\"temperature\":${gradualTempC}}") // gradualAdjustment side-effect

        where:
        tempC | gradualTempC
        19.5  | 20.0
        20.0  | 20.5
        20.5  | 20.5
    }

    def "processTemperatureUpdate() Fahrenheit"() {
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
        def events = script.processTemperatureUpdate([roomTemperature: tempC, operating: false])

        then:
        events == [
                [name: "temperature", value: tempF, unit: "°F", descriptionText: "Temperature is ${tempF}°F"],
                [name: "thermostatOperatingState", value: "idle"]
        ]
        1 * mqtt.publish("heatpump/set", "{\"temperature\":${gradualTempC}}") // gradualAdjustment side-effect

        where:
        tempC | tempF | gradualTempC
        19.5  | 67.1  | 20.0
        20.0  | 68.0  | 20.5
        20.5  | 68.9  | 20.5
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
                ["power": "ON", "mode": "HEAT", "temperature": 17.5,
                 "fan"  : "3", "vane": "4", "wideVane": "<<"]
        )

        then:
        events[0] == [name: "thermostatMode", value: "heat"]
        events[1] == [name: "thermostatFanMode", value: "3"]
        events[2] == [name: "heatingSetpoint", value: 17.5, unit: "°C"]
        events[3] == [name: "thermostatSetpoint", value: 17.5, unit: "°C"]
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
                ["power": "ON", "mode": "HEAT", "temperature": 17.5,
                 "fan"  : "3", "vane": "4", "wideVane": "<<"]
        )

        then:
        events[0] == [name: "thermostatMode", value: "heat"]
        events[1] == [name: "thermostatFanMode", value: "3"]
        events[2] == [name: "heatingSetpoint", value: 20.5, unit: "°C"]
        events[3] == [name: "thermostatSetpoint", value: 20.5, unit: "°C"]
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
                ["power": "ON", "mode": "HEAT", "temperature": 17.5,
                 "fan"  : "3", "vane": "4", "wideVane": "<<"]
        )

        then:
        events[0] == [name: "thermostatMode", value: "heat"]
        events[1] == [name: "thermostatFanMode", value: "3"]
        events[2] == [name: "heatingSetpoint", value: 63.5, unit: "°F"]
        events[3] == [name: "thermostatSetpoint", value: 63.5, unit: "°F"]
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
                ["power": "ON", "mode": "HEAT", "temperature": 17.5,
                 "fan"  : "3", "vane": "4", "wideVane": "<<"]
        )

        then:
        events[0] == [name: "thermostatMode", value: "heat"]
        events[1] == [name: "thermostatFanMode", value: "3"]
        events[2] == [name: "heatingSetpoint", value: 68.9, unit: "°F"]
        events[3] == [name: "thermostatSetpoint", value: 68.9, unit: "°F"]
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
                ["power": "ON", "mode": "HEAT", "temperature": 17.5,
                 "fan"  : "3", "vane": "4", "wideVane": "<<"]
        )

        then:
        events[0] == [name: "thermostatMode", value: "heat"]
        events[1] == [name: "thermostatFanMode", value: "3"]
        events[2] == [name: "heatingSetpoint", value: 68.9, unit: "°F"]
        events[3] == [name: "thermostatSetpoint", value: 68.9, unit: "°F"]
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
        initState()
        device.currentValue("thermostatMode", *_) >> "heat"
        // device.currentValue("heatingSetpoint") >> 68.9
        device.currentValue("temperature") >> 66.2
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
        script.debouncedSetHeatingSetpoint([data: [setpoint: 68.8]])

        then:
        1 * executorApi.sendEvent(["name": "heatingSetpoint", "value": 68.9, "unit": "°F"])
        1 * mqtt.publish("heatpump/set", "{\"temperature\":19.5}")
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
}
