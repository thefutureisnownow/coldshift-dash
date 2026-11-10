package racing.coldshift.dash;

import java.util.HashMap;
import java.util.Map;

/**
 * Plain-words descriptions for the common generic fault codes (SAE J2012, P0xxx and a few others), for the
 * fault code list. A code that is not here (a maker's own, P1xxx, or a rare one) shows with no words;
 * describe() returns "". Short on purpose: the name of the part and what it did.
 */
final class Dtcs {
    private Dtcs() {}

    private static final Map<String, String> TEXT = new HashMap<String, String>();

    private static void add(String code, String text) { TEXT.put(code, text); }

    /** Codes from P0300 on that come in runs: P0301 .. P0308 and so on. */
    private static void run(String prefix, int from, int to, String format) {
        for (int i = from; i <= to; i++) TEXT.put(prefix + i, String.format(java.util.Locale.US, format, i - from + 1));
    }

    static String describe(String code) {
        String s = TEXT.get(code);
        return s == null ? "" : s;
    }

    static {
        add("P0016", "Crankshaft and camshaft position do not match (bank 1, sensor A)");
        add("P0030", "Oxygen sensor heater circuit (bank 1, sensor 1)");
        add("P0031", "Oxygen sensor heater circuit low (bank 1, sensor 1)");
        add("P0032", "Oxygen sensor heater circuit high (bank 1, sensor 1)");
        add("P0036", "Oxygen sensor heater circuit (bank 1, sensor 2)");
        add("P0037", "Oxygen sensor heater circuit low (bank 1, sensor 2)");
        add("P0038", "Oxygen sensor heater circuit high (bank 1, sensor 2)");
        add("P0100", "Mass air flow sensor circuit");
        add("P0101", "Mass air flow sensor out of range");
        add("P0102", "Mass air flow sensor circuit low");
        add("P0103", "Mass air flow sensor circuit high");
        add("P0104", "Mass air flow sensor circuit intermittent");
        add("P0105", "Manifold pressure sensor circuit");
        add("P0106", "Manifold pressure sensor out of range");
        add("P0107", "Manifold pressure sensor circuit low");
        add("P0108", "Manifold pressure sensor circuit high");
        add("P0109", "Manifold pressure sensor circuit intermittent");
        add("P0110", "Intake air temperature sensor circuit");
        add("P0111", "Intake air temperature sensor out of range");
        add("P0112", "Intake air temperature sensor circuit low");
        add("P0113", "Intake air temperature sensor circuit high");
        add("P0114", "Intake air temperature sensor circuit intermittent");
        add("P0115", "Coolant temperature sensor circuit");
        add("P0116", "Coolant temperature sensor out of range");
        add("P0117", "Coolant temperature sensor circuit low");
        add("P0118", "Coolant temperature sensor circuit high");
        add("P0119", "Coolant temperature sensor circuit intermittent");
        add("P0120", "Throttle position sensor A circuit");
        add("P0121", "Throttle position sensor A out of range");
        add("P0122", "Throttle position sensor A circuit low");
        add("P0123", "Throttle position sensor A circuit high");
        add("P0124", "Throttle position sensor A circuit intermittent");
        add("P0125", "Engine too cool to run in closed loop");
        add("P0128", "Coolant thermostat: engine below its regulating temperature");
        add("P0130", "Oxygen sensor circuit (bank 1, sensor 1)");
        add("P0131", "Oxygen sensor low voltage (bank 1, sensor 1)");
        add("P0132", "Oxygen sensor high voltage (bank 1, sensor 1)");
        add("P0133", "Oxygen sensor slow response (bank 1, sensor 1)");
        add("P0134", "Oxygen sensor no activity (bank 1, sensor 1)");
        add("P0135", "Oxygen sensor heater circuit (bank 1, sensor 1)");
        add("P0136", "Oxygen sensor circuit (bank 1, sensor 2)");
        add("P0137", "Oxygen sensor low voltage (bank 1, sensor 2)");
        add("P0138", "Oxygen sensor high voltage (bank 1, sensor 2)");
        add("P0139", "Oxygen sensor slow response (bank 1, sensor 2)");
        add("P0140", "Oxygen sensor no activity (bank 1, sensor 2)");
        add("P0141", "Oxygen sensor heater circuit (bank 1, sensor 2)");
        add("P0150", "Oxygen sensor circuit (bank 2, sensor 1)");
        add("P0151", "Oxygen sensor low voltage (bank 2, sensor 1)");
        add("P0152", "Oxygen sensor high voltage (bank 2, sensor 1)");
        add("P0153", "Oxygen sensor slow response (bank 2, sensor 1)");
        add("P0154", "Oxygen sensor no activity (bank 2, sensor 1)");
        add("P0155", "Oxygen sensor heater circuit (bank 2, sensor 1)");
        add("P0156", "Oxygen sensor circuit (bank 2, sensor 2)");
        add("P0157", "Oxygen sensor low voltage (bank 2, sensor 2)");
        add("P0158", "Oxygen sensor high voltage (bank 2, sensor 2)");
        add("P0159", "Oxygen sensor slow response (bank 2, sensor 2)");
        add("P0160", "Oxygen sensor no activity (bank 2, sensor 2)");
        add("P0161", "Oxygen sensor heater circuit (bank 2, sensor 2)");
        add("P0170", "Fuel trim fault (bank 1)");
        add("P0171", "Fuel mixture too lean (bank 1)");
        add("P0172", "Fuel mixture too rich (bank 1)");
        add("P0173", "Fuel trim fault (bank 2)");
        add("P0174", "Fuel mixture too lean (bank 2)");
        add("P0175", "Fuel mixture too rich (bank 2)");
        add("P0180", "Fuel temperature sensor A circuit");
        add("P0190", "Fuel rail pressure sensor circuit");
        add("P0200", "Injector circuit");
        run("P020", 1, 8, "Injector circuit, cylinder %d");
        add("P0217", "Engine over temperature");
        add("P0219", "Engine over speed");
        add("P0220", "Throttle position sensor B circuit");
        add("P0230", "Fuel pump primary circuit");
        add("P0234", "Turbocharger over boost");
        add("P0235", "Turbo boost sensor A circuit");
        add("P0236", "Turbo boost sensor A out of range");
        add("P0237", "Turbo boost sensor A circuit low");
        add("P0238", "Turbo boost sensor A circuit high");
        add("P0243", "Wastegate solenoid A fault");
        add("P0245", "Wastegate solenoid A low");
        add("P0246", "Wastegate solenoid A high");
        add("P0299", "Turbocharger under boost");
        add("P0300", "Random or multiple cylinder misfire");
        run("P030", 1, 8, "Cylinder %d misfire");
        add("P0325", "Knock sensor 1 circuit");
        add("P0326", "Knock sensor 1 out of range");
        add("P0327", "Knock sensor 1 circuit low");
        add("P0328", "Knock sensor 1 circuit high");
        add("P0335", "Crankshaft position sensor A circuit");
        add("P0336", "Crankshaft position sensor A out of range");
        add("P0337", "Crankshaft position sensor A circuit low");
        add("P0338", "Crankshaft position sensor A circuit high");
        add("P0339", "Crankshaft position sensor A circuit intermittent");
        add("P0340", "Camshaft position sensor A circuit (bank 1)");
        add("P0341", "Camshaft position sensor A out of range (bank 1)");
        add("P0342", "Camshaft position sensor A circuit low (bank 1)");
        add("P0343", "Camshaft position sensor A circuit high (bank 1)");
        add("P0365", "Camshaft position sensor B circuit (bank 1)");
        add("P0400", "Exhaust gas recirculation flow");
        add("P0401", "Exhaust gas recirculation flow too low");
        add("P0402", "Exhaust gas recirculation flow too high");
        add("P0403", "Exhaust gas recirculation circuit");
        add("P0404", "Exhaust gas recirculation out of range");
        add("P0405", "Exhaust gas recirculation sensor A low");
        add("P0406", "Exhaust gas recirculation sensor A high");
        add("P0410", "Secondary air injection system");
        add("P0411", "Secondary air injection: wrong flow");
        add("P0420", "Catalyst efficiency below threshold (bank 1)");
        add("P0421", "Warm-up catalyst efficiency below threshold (bank 1)");
        add("P0430", "Catalyst efficiency below threshold (bank 2)");
        add("P0440", "Evaporative emission system");
        add("P0441", "Evaporative emission system: wrong purge flow");
        add("P0442", "Evaporative emission system: small leak");
        add("P0443", "Evaporative purge valve circuit");
        add("P0446", "Evaporative vent control circuit");
        add("P0449", "Evaporative vent valve circuit");
        add("P0451", "Evaporative pressure sensor out of range");
        add("P0452", "Evaporative pressure sensor low");
        add("P0453", "Evaporative pressure sensor high");
        add("P0455", "Evaporative emission system: large leak (is the fuel cap on?)");
        add("P0456", "Evaporative emission system: very small leak");
        add("P0457", "Evaporative emission system: leak (fuel cap loose or off)");
        add("P0460", "Fuel level sensor circuit");
        add("P0461", "Fuel level sensor out of range");
        add("P0462", "Fuel level sensor circuit low");
        add("P0463", "Fuel level sensor circuit high");
        add("P0480", "Cooling fan 1 control circuit");
        add("P0481", "Cooling fan 2 control circuit");
        add("P0500", "Vehicle speed sensor");
        add("P0501", "Vehicle speed sensor out of range");
        add("P0502", "Vehicle speed sensor circuit low");
        add("P0503", "Vehicle speed sensor intermittent");
        add("P0505", "Idle control system");
        add("P0506", "Idle speed lower than expected");
        add("P0507", "Idle speed higher than expected");
        add("P0510", "Closed throttle position switch");
        add("P0520", "Oil pressure sensor circuit");
        add("P0521", "Oil pressure sensor out of range");
        add("P0522", "Oil pressure sensor circuit low");
        add("P0523", "Oil pressure sensor circuit high");
        add("P0524", "Engine oil pressure too low");
        add("P0530", "Air conditioning pressure sensor circuit");
        add("P0560", "System voltage");
        add("P0562", "System voltage low");
        add("P0563", "System voltage high");
        add("P0571", "Brake switch circuit");
        add("P0601", "Engine computer memory checksum");
        add("P0602", "Engine computer programming");
        add("P0603", "Engine computer keep-alive memory");
        add("P0604", "Engine computer RAM");
        add("P0605", "Engine computer ROM");
        add("P0606", "Engine computer processor");
        add("P0607", "Engine computer performance");
        add("P0615", "Starter relay circuit");
        add("P0620", "Generator control circuit");
        add("P0630", "VIN not programmed");
        add("P0638", "Throttle actuator out of range (bank 1)");
        add("P0642", "Sensor reference voltage A low");
        add("P0643", "Sensor reference voltage A high");
        add("P0650", "Check-engine light circuit");
        add("P0657", "Actuator supply voltage");
        add("P0700", "Transmission control system");
        add("P0705", "Transmission range sensor circuit");
        add("P0710", "Transmission fluid temperature sensor circuit");
        add("P0715", "Input or turbine speed sensor circuit");
        add("P0720", "Output speed sensor circuit");
        add("P0725", "Engine speed input circuit");
        add("P0730", "Wrong gear ratio");
        add("P0731", "Wrong gear ratio in 1st gear");
        add("P0732", "Wrong gear ratio in 2nd gear");
        add("P0733", "Wrong gear ratio in 3rd gear");
        add("P0734", "Wrong gear ratio in 4th gear");
        add("P0735", "Wrong gear ratio in 5th gear");
        add("P0741", "Torque converter clutch circuit performance");
        add("P0750", "Shift solenoid A");
        add("P0755", "Shift solenoid B");
        add("P0760", "Shift solenoid C");
        add("P0765", "Shift solenoid D");
        add("P0780", "Shift fault");
        add("P0850", "Park or neutral switch circuit");
        add("P2096", "Fuel mixture too lean after the catalyst (bank 1)");
        add("P2097", "Fuel mixture too rich after the catalyst (bank 1)");
        add("P2101", "Throttle actuator motor out of range");
        add("P2135", "Throttle position sensors A and B do not agree");
        add("P2138", "Accelerator pedal sensors D and E do not agree");
        add("P2187", "Fuel mixture too lean at idle (bank 1)");
        add("P2188", "Fuel mixture too rich at idle (bank 1)");
        add("P2195", "Oxygen sensor stuck lean (bank 1, sensor 1)");
        add("P2196", "Oxygen sensor stuck rich (bank 1, sensor 1)");
        add("U0073", "Control module communication bus off");
        add("U0100", "Lost communication with the engine computer");
        add("U0101", "Lost communication with the transmission computer");
        add("U0121", "Lost communication with the ABS computer");
        add("U0140", "Lost communication with the body computer");
    }
}
