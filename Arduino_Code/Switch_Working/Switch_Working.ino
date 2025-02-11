#include <Arduino.h>
#include <NimBLEDevice.h>
#include "NimBLEUtils.h"

#define RELAY_PIN  21
#define LED_PIN    20

static const char* SWITCH_SERVICE_UUID = "7a18bef7-af16-4272-9777-63402c3a7ad3";
static const char* SWITCH_CHAR_UUID    = "18ae98ca-1e23-40d8-8350-1333899458f3";

static NimBLEServer*         pServer        = nullptr;
static NimBLEService*        pSwitchService = nullptr;
static NimBLECharacteristic* pSwitchChar    = nullptr;

bool scaleConnected = false;
bool relayOn        = false;
unsigned long relayOnTimestamp = 0;
bool hadDisconnect = false;

void updateStatusLED() {
  static unsigned long lastToggle = 0;
  static bool ledState = false;

  if (!scaleConnected) {
    //Blink slowly if scale isn't connected
    if (millis() - lastToggle >= 1000) {
      lastToggle = millis();
      ledState = !ledState;
      digitalWrite(LED_PIN, ledState ? HIGH : LOW);
    }
    return;
  }

  // If connected but relay is OFF => solid ON
  if (!relayOn) {
    digitalWrite(LED_PIN, HIGH);
    return; // done
  }

  // If connected & relayOn => fast blink
  if (millis() - lastToggle >= 50) {
    lastToggle = millis();
    ledState = !ledState;
    digitalWrite(LED_PIN, (ledState ? HIGH : LOW));
  }
}

class SwitchServerCallbacks : public NimBLEServerCallbacks {
 public:
  void onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) override {
    pServer->updateConnParams(connInfo.getConnHandle(), 24, 48, 0, 180);
    scaleConnected = true;
    Serial.print("Scale connected to Switch");
    Serial.println(connInfo.getAddress().toString().c_str());
  }

  void onDisconnect(NimBLEServer* pServer, NimBLEConnInfo &connInfo, int reason) override {
    scaleConnected = false;
    Serial.print("Client disconnected, Relay OFF for safety. Reason = ");
    Serial.println(reason);
    Serial.print("Meaning: ");
    Serial.println(NimBLEUtils::returnCodeToString(reason));
    if (relayOn && !hadDisconnect) {
      hadDisconnect = true;
      Serial.println("Disconnect detected during shot; auto-off timer will expire at 30s from shot start.");
    }
    Serial.println("Restarting advertising...");
    NimBLEDevice::startAdvertising();
  }
};

class SwitchCharCallbacks : public NimBLECharacteristicCallbacks {
 public:
  // Called whenever the central writes data to this characteristic.
  void onWrite(NimBLECharacteristic* pCharacteristic, NimBLEConnInfo &connInfo) override {
    std::string data = pCharacteristic->getValue();
    if (data.size() > 0) {
      uint8_t cmd = data[0];
      if (cmd == 1) {
        // Turn relay ON
        digitalWrite(RELAY_PIN, HIGH);
        relayOn = true;
        relayOnTimestamp = millis(); //log start time in case connection drops.
        hadDisconnect = false;
        Serial.println("Relay ON");
      } else {
        // Turn relay OFF
        digitalWrite(RELAY_PIN, LOW);
        relayOn = false;
        hadDisconnect = false;
        Serial.println("Relay OFF");
      }
    }
  }
};

void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println("Starting EspressoSwitch...");

  pinMode(RELAY_PIN, OUTPUT);
  digitalWrite(RELAY_PIN, LOW);  // default OFF
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);

  NimBLEDevice::init("EspSw");
  NimBLEDevice::setPower(9); // dBm

  pServer = NimBLEDevice::createServer();
  pServer->setCallbacks(new SwitchServerCallbacks());

  pSwitchService = pServer->createService(SWITCH_SERVICE_UUID);

  pSwitchChar = pSwitchService->createCharacteristic(
                  SWITCH_CHAR_UUID,
                  NIMBLE_PROPERTY::READ |
                  NIMBLE_PROPERTY::WRITE
                );
  pSwitchChar->setCallbacks(new SwitchCharCallbacks());

  pSwitchService->start();

  NimBLEAdvertising* pAdvertising = NimBLEDevice::getAdvertising();
  pAdvertising->enableScanResponse(true);
  pAdvertising->setName("EspSw");
  pAdvertising->addServiceUUID(SWITCH_SERVICE_UUID);
  pAdvertising->start();
  Serial.println("Advertising started. Ready to connect.");
}

void loop() {

  updateStatusLED();
  
  // Shut off 30 seconds from shot start if the scale drops connection.
  if (relayOn && hadDisconnect) {
    unsigned long elapsed = millis() - relayOnTimestamp;
    static unsigned long lastLogTime = 0;
    if (millis() - lastLogTime >= 1000) {
      lastLogTime = millis();
      Serial.print("Auto-off timer: ");
      Serial.print(elapsed / 1000);
      Serial.println(" seconds elapsed since shot started.");
    }
    if (elapsed >= 30000) { // 30 seconds (30,000 ms)
      digitalWrite(RELAY_PIN, LOW);
      relayOn = false;
      Serial.println("Relay automatically turned OFF after 30 seconds from shot start.");
      // Reset the disconnect flag so that a new shot starts fresh.
      hadDisconnect = false;
    }
  }
}