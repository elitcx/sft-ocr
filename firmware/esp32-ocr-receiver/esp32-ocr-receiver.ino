// BRaiLLE OCR - ESP32 text receiver (test bench)
//
// Classic Bluetooth serial (SPP). Pair the phone with "ESP32_Test_Board" (the app's
// Hubungkan BraillePad screen can do this), then "Kirim ke BraillePad" connects, writes the
// scanned text plus a newline, and disconnects. Received text is echoed to the USB serial
// port (115200 baud), one line at a time.
//
// Commands: a line starting with the SOH byte (0x01) is a request from the app, never text
// to print. Scanned text cannot contain SOH.
//   SOH "INFO"  ->  SOH "INFO fw=<FIRMWARE_VERSION>" newline
//
// Needs an original ESP32 (e.g. DevKit V1) - the S3/C3/C6 have no classic Bluetooth.

#include <Arduino.h>
#include "BluetoothSerial.h"

// Bump this whenever the sketch changes; the app shows it under Pengaturan > Perangkat Alat.
const char *FIRMWARE_VERSION = "1.1.0";
const char COMMAND_PREFIX = 0x01;

BluetoothSerial SerialBT;
String line;

void handleCommand(const String &command) {
  if (command == "INFO") {
    SerialBT.write(COMMAND_PREFIX);
    SerialBT.print("INFO fw=");
    SerialBT.print(FIRMWARE_VERSION);
    SerialBT.print('\n');
  }
}

void handleLine() {
  if (line.length() > 0 && line[0] == COMMAND_PREFIX) {
    handleCommand(line.substring(1));
  } else {
    Serial.println(line);
  }
  line = "";
}

void setup() {
  Serial.begin(115200);
  line.reserve(4096);
  SerialBT.begin("ESP32_Test_Board");  // must match Esp32BluetoothSender.DEVICE_NAME
  Serial.print("Bluetooth active! Ready to pair. Firmware ");
  Serial.println(FIRMWARE_VERSION);
}

void loop() {
  // Drain everything waiting, not one byte per pass: a scanned page arrives in one burst,
  // and BluetoothSerial drops bytes once its receive buffer fills.
  while (SerialBT.available()) {
    char c = (char)SerialBT.read();
    if (c == '\r') continue;
    if (c == '\n') {
      handleLine();
    } else {
      line += c;
    }
  }
  while (Serial.available()) {
    SerialBT.write(Serial.read());
  }
  delay(10);
}
