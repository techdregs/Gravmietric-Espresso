#include <Arduino.h>
#include <NimBLEDevice.h>
#include <U8g2lib.h>
#include <Wire.h>
#include <HX711_ADC.h>
#include <EEPROM.h>
#include <math.h>

//U8g2 Contructor and display variables
U8G2_SH1106_128X64_NONAME_1_HW_I2C u8g2(U8G2_R2, U8X8_PIN_NONE);
#define LCDWidth u8g2.getDisplayWidth()
#define ALIGN_CENTER(t) ((LCDWidth - (u8g2.getUTF8Width(t))) / 2)

//HX711 configuration
const int HX711_dout = 10;
const int HX711_sck  = 8;
HX711_ADC LoadCell(HX711_dout, HX711_sck);

//Buttons
#define Sleep_Button  D0
#define Tare_Button   D1
#define Cycle_Button  D6

//Data logging variables
bool logState = false;
unsigned long lastLogTime = 0;
const int logInterval = 0; //ms, increase to slow updates for data logging
float bleMass = 0.0;

bool shotInProgress   = false;
float targetMass = 40.0; //future: change to eeprom 

static const NimBLEAdvertisedDevice* advDevice;
static bool                          doConnect  = false;
static uint32_t                      scanTimeMs = 5000; /** scan time in milliseconds, 0 = scan forever */

//Scale to Phone communications
static const char* SCALE_SERVICE_UUID  = "1eb9bb89-186e-4d2a-a204-346da73c061c";
static const char* MASS_CHAR_UUID      = "5977b71a-a58c-40d2-85a4-34071043d9ca";
static const char* TARE_CHAR_UUID    = "a8f2d9f3-c93a-4479-8208-7287262eacf6";
static const char* LOGGING_CHAR_UUID   = "9fdd73d8-77e8-4099-816f-a1619834c3f2";
static const char* TARGET_CHAR_UUID    = "bcf25166-c8d1-4421-805f-0d277cbfb82e";
static const char* SHOTSTATE_CHAR_UUID      = "c4fc31b7-0442-4ed8-861f-08c5e8843eb7"; 
static const char* LOGSTATE_CHAR_UUID   = "101305d0-ebd3-4862-b816-a12f7694f498"; 


NimBLEServer*         pServer             = nullptr;
NimBLECharacteristic* pMassChar           = nullptr;
NimBLECharacteristic* pTareChar           = nullptr;
NimBLECharacteristic* pLoggingChar        = nullptr;
NimBLECharacteristic* pTargetMassChar     = nullptr;
NimBLECharacteristic* pShotStateChar      = nullptr;
NimBLECharacteristic* pLogStateChar       = nullptr;

// Scale to Slave communications
static const char* SWITCH_SERVICE_UUID = "7a18bef7-af16-4272-9777-63402c3a7ad3";
static const char* SWITCH_CHAR_UUID    = "18ae98ca-1e23-40d8-8350-1333899458f3";

NimBLERemoteCharacteristic* pSwitchChar = nullptr;  // Store the switch characteristic globally


//Track if a phone is connected:
bool phoneConnected = false;
//Track if the slave is connected:
bool switchConnected = false;

//Timing for HX711 read
unsigned long lastHX711Time = 0;
//HX711 Calibration
void tare() {
  LoadCell.tareNoDelay();
  Serial.println("Taring.");
  if (LoadCell.getTareStatus()) {
    Serial.println("Tare complete.");
  }
}

// Phone Connection Callbacks
class ScaleServerCallbacks : public NimBLEServerCallbacks {
  public:
    void onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) override {
      phoneConnected = true;
      Serial.println("Phone connected to scale.");
      Serial.println(connInfo.getAddress().toString().c_str());
    }

    void onDisconnect(NimBLEServer* pServer, NimBLEConnInfo &connInfo, int reason) override {
      phoneConnected = false;
      Serial.print("Phone disconnected, Reason = ");
      Serial.println(reason);
      Serial.println("Restarting advertising...");
      NimBLEDevice::startAdvertising();
    }
};

// Handles Commands Sent from Phone
class TareCharCallbacks : public NimBLECharacteristicCallbacks {
  public:
    void onWrite(NimBLECharacteristic* pCharacteristic, NimBLEConnInfo &connInfo) override {
      std::string val = pCharacteristic->getValue();
      if (val.size() >= 1) {
        int cmd = (uint8_t)val[0];
        Serial.printf("Received Tare CMD from phone: %d\n", cmd);

        if (cmd == 1) {
          tare();  // Phone sends Tare Command
        }
      }
    }
};

class ShotStateCharCallbacks : public NimBLECharacteristicCallbacks {
  public:
    void onWrite(NimBLECharacteristic* pCharacteristic, NimBLEConnInfo &connInfo) override {
      std::string val = pCharacteristic->getValue();
      if (val.size() >= 1) {
        int cmd = (uint8_t)val[0];
        Serial.printf("Received Shot CMD from phone: %d\n", cmd);
        if (cmd == 1) {
          if (!shotInProgress) startShot();
        }
        if (cmd == 0) {
          if (shotInProgress) stopShot();
        }
      }
    }
};

class LogStateCharCallbacks : public NimBLECharacteristicCallbacks {
  public:
    void onWrite(NimBLECharacteristic* pCharacteristic, NimBLEConnInfo &connInfo) override {
      std::string val = pCharacteristic->getValue();
      if (val.size() >= 1) {
        int cmd = (uint8_t)val[0];
        Serial.printf("Received Logging CMD from phone: %d\n", cmd);

        if (cmd == 1) {
          logState = true;
        }
        if (cmd == 0) {
          logState = false;
        }
      }
    }
};    

class TargetMassCallbacks : public NimBLECharacteristicCallbacks {
  public:
    void onWrite(NimBLECharacteristic* pCharacteristic, NimBLEConnInfo &connInfo) override {
      std::string val = pCharacteristic->getValue();
      if (val.size() >= 2) {
        int16_t scaledVal;
        memcpy(&scaledVal, val.data(), sizeof(scaledVal));
        targetMass = scaledVal / 10.0f;
        Serial.printf("Updated Target Mass: %.1fg\n", targetMass);
        // Save to EEPROM
        EEPROM.put(0, targetMass); 
        EEPROM.commit();
        pCharacteristic->setValue(val);
      }
    }
};

/** Client Callbacks */
class ClientCallbacks : public NimBLEClientCallbacks {
    void onConnect(NimBLEClient* pClient) override {
        switchConnected = true;
        Serial.printf("Connected to Switch\n");
        NimBLEDevice::getScan()->stop();
        Serial.println("Scanning Stopped.");
    }

    void onDisconnect(NimBLEClient* pClient, int reason) override {
        switchConnected = false;
        Serial.printf("%s Disconnected, reason = %d - Restarting scan\n", 
                      pClient->getPeerAddress().toString().c_str(), reason);
        NimBLEDevice::getScan()->start(scanTimeMs, false, true);
        if (shotInProgress) {
          Serial.println("Lost connection to Switch mid-shot, clearing shotInProgress.");
          shotInProgress = false;
        }
    }
} clientCallbacks;

/** Scan Callbacks */
class ScanCallbacks : public NimBLEScanCallbacks {
    void onResult(const NimBLEAdvertisedDevice* advertisedDevice) override {
        Serial.printf("Advertised Device found: %s\n", advertisedDevice->toString().c_str());
        if (advertisedDevice->isAdvertisingService(NimBLEUUID(SWITCH_SERVICE_UUID))) {
            Serial.printf("Found Our Service\n");
            NimBLEDevice::getScan()->stop();  // Stop scanning before connecting
            advDevice = advertisedDevice;
            doConnect = true;
        }
    }

    void onScanEnd(const NimBLEScanResults& results, int reason) override {
        Serial.printf("Scan Ended, reason: %d, device count: %d; Restarting scan\n", reason, results.getCount());
        NimBLEDevice::getScan()->start(scanTimeMs, false, true);
    }
} scanCallbacks;

/** Connects to the Switch Server */
bool connectToServer() {
    NimBLEClient* pClient = NimBLEDevice::getClientByPeerAddress(advDevice->getAddress());
    if (!pClient) {
        if (NimBLEDevice::getCreatedClientCount() >= NIMBLE_MAX_CONNECTIONS) {
            Serial.printf("Max clients reached - no more connections available\n");
            return false;
        }

        pClient = NimBLEDevice::createClient();
        Serial.println("New client created");
        pClient->setClientCallbacks(&clientCallbacks, false);
        pClient->setConnectionParams(12, 12, 0, 150);  // BLE connection parameters
        pClient->setConnectTimeout(5000);
    }

    if (!pClient->connect(advDevice)) {
        Serial.printf("Failed to connect\n");
        return false;
    }

    Serial.printf("Connected to: %s, RSSI: %d\n", 
                  pClient->getPeerAddress().toString().c_str(), pClient->getRssi());

    // Get Switch Service & Characteristic
    NimBLERemoteService* pSvc = pClient->getService(NimBLEUUID(SWITCH_SERVICE_UUID));
    if (pSvc) {
        pSwitchChar = pSvc->getCharacteristic(SWITCH_CHAR_UUID);  // Store the characteristic globally
        if (pSwitchChar) {
            Serial.println("Service & Characteristic Found! Ready to communicate.");
        } else {
            Serial.println("Characteristic not found!");
            return false;
        }
    } else {
        Serial.println("Service not found!");
        return false;
    }

    Serial.println("Done with this device!");
    return true;
}

//Battery Level
int battLevel() {
  pinMode(A2, INPUT);
  uint32_t Vbatt = 0;
  for (int i = 0; i < 16; i++) {
    Vbatt += analogReadMilliVolts(A2);
  }
  float Vbattf = 2 * (float)Vbatt / 16 / 1000.0;
  int battPct = round(-181.24 * pow(Vbattf, 3) + 2026.3 * pow(Vbattf, 2) -
                      7405.2 * Vbattf + 8885.8);
  return battPct;
}

//Write to Switch (0=OFF, 1=ON)
void writeSwitch(uint8_t val) {
  if (switchConnected && pSwitchChar != nullptr) {
    uint8_t command = val;
    pSwitchChar->writeValue(&command, 1);
    
    Serial.print("Wrote to switch: ");
    Serial.println(val == 1 ? "ON" : "OFF");
  } else {
    Serial.println("Cannot write to switch (not connected).");
  }
}

//Start/Stop Shot
void startShot() {
  if (switchConnected && pSwitchChar != nullptr) { 
      tare();
      writeSwitch(1);  // Turn ON
      shotInProgress = true;
      pShotStateChar->setValue(1);
      pShotStateChar->notify();
      Serial.println("Shot started!");
    } else {
      Serial.println("Cannot Start Shot. Switch not Connected.");
    }
}

void stopShot() {
  writeSwitch(0);  // Attempt Turn OFF
  shotInProgress = false; 
  pShotStateChar->setValue(0);
  pShotStateChar->notify();
  if (switchConnected && pSwitchChar != nullptr) { 
    Serial.println("Shot stopped!");
  } else {
    Serial.println("Switch not Connected. Shot status set to off.");
  }
}

//routine for debounced cycle button
static int cycleBtnStateStable    = HIGH;
static int cycleBtnStateLastRead  = HIGH;
static unsigned long cycleDebounceTime = 0;
const unsigned long cycleDebounceDelay = 50; // ms
void checkCycleButton() {
  int rawState = digitalRead(Cycle_Button);

  if (rawState != cycleBtnStateLastRead) {
    cycleDebounceTime = millis();
    cycleBtnStateLastRead = rawState;
  }

  if ((millis() - cycleDebounceTime) > cycleDebounceDelay) {
    if (cycleBtnStateStable != cycleBtnStateLastRead) {
      cycleBtnStateStable = cycleBtnStateLastRead;

      if (cycleBtnStateStable == LOW) {  // Button pressed
        if (!shotInProgress) {
          Serial.print("Target Mass = ");
          Serial.println(targetMass);
          startShot();  // Turn ON switch
        } else {
          stopShot();   // Turn OFF switch
        }
        Serial.println("Cycle Button Pressed.");
      }
    }
  }
}


void setup() {
  EEPROM.begin(512);
  Serial.begin(115200);
  delay(500);
  u8g2.begin();
  Serial.println("Starting Scale Client");

    //HX711 ADC
  LoadCell.begin();
  float calValue = 2262.26; //adjust scale calibration here
  LoadCell.start(2000, true); //2 second stabilizing time
  if (LoadCell.getTareTimeoutFlag()) {
    Serial.println("HX711 Timeout, check wiring");
    for (;;) ; // halt
  }
  LoadCell.setCalFactor(calValue);
  Serial.println("Load Cell init OK");

  float storedMass;
  EEPROM.get(0, storedMass);
  if (isnan(storedMass) || storedMass < 1.0f || storedMass > 2000.0f) {
      targetMass = 40.0f;
      Serial.println("No valid mass in EEPROM, defaulting to 40.0f");
  } else {
      targetMass = storedMass;
      Serial.printf("Loaded targetMass from EEPROM: %.1f\n", targetMass);
  }

  pinMode(Sleep_Button,  INPUT_PULLUP);
  pinMode(Tare_Button,   INPUT_PULLUP);
  pinMode(Cycle_Button,  INPUT_PULLUP);

  NimBLEDevice::init("LoggingScale");
  NimBLEDevice::setPower(9);  // Set transmit power

  // BLE Server for Phone Communication
  pServer = NimBLEDevice::createServer();
  pServer->setCallbacks(new ScaleServerCallbacks());

  // Create BLE Service
  NimBLEService* pScaleService = pServer->createService(SCALE_SERVICE_UUID);

  // Mass Characteristic (Phone reads mass, Scale notifies updates)
  pMassChar = pScaleService->createCharacteristic(
                MASS_CHAR_UUID,
                NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY
              );

  // Tare Command Characteristic (Phone writes commands)
  pTareChar = pScaleService->createCharacteristic(
                  TARE_CHAR_UUID,
                  NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY
                );
  pTareChar->setCallbacks(new TareCharCallbacks());

  // Logging Characteristic (Phone reads logs, Scale notifies)
  pLoggingChar = pScaleService->createCharacteristic(
                  LOGGING_CHAR_UUID,
                  NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY
                );

  // Target Mass Characteristic 
  pTargetMassChar = pScaleService->createCharacteristic(
                      TARGET_CHAR_UUID,
                      NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY
                    );
  pTargetMassChar->setCallbacks(new TargetMassCallbacks());
  int16_t target_scaled = (int16_t) round(targetMass * 10.0f);
  pTargetMassChar->setValue((uint8_t*)&target_scaled, sizeof(target_scaled));

  pShotStateChar = pScaleService->createCharacteristic(
                SHOTSTATE_CHAR_UUID,
                NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY
              );
  pShotStateChar->setCallbacks(new ShotStateCharCallbacks());
  
  pLogStateChar = pScaleService->createCharacteristic(
                  LOGSTATE_CHAR_UUID,
                  NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY
                );
  pLogStateChar->setCallbacks(new LogStateCharCallbacks());

  // Start the Service
  pScaleService->start();

  // Advertise Service for Phone
  NimBLEAdvertising* pAdv = NimBLEDevice::getAdvertising();
  pAdv->setName("LoggingScale"); 
  pAdv->addServiceUUID(SCALE_SERVICE_UUID);
  pAdv->start();
  Serial.println("Scale is advertising...");

  NimBLEScan* pScan = NimBLEDevice::getScan();
  pScan->setScanCallbacks(&scanCallbacks, false);
  pScan->setInterval(100);
  pScan->setWindow(100);
  pScan->setActiveScan(true);
  pScan->start(scanTimeMs);
  Serial.println("Scanning for peripherals...");
  }

void loop() {
  if (doConnect) {
      doConnect = false;
      if (connectToServer()) {
          Serial.println("Success! Ready to communicate.");
      } else {
          Serial.println("Failed to connect, restarting scan.");
          NimBLEDevice::getScan()->start(scanTimeMs, false, true);
      }
  }

  //Read HX711
  static bool newDataReady = false;
  if (LoadCell.update()) {
    newDataReady = true;
  }

  if (newDataReady && millis() - lastHX711Time >= 15) { //15ms read interval
    newDataReady = false;
    lastHX711Time = millis();

    bleMass  = LoadCell.getData();
    //Serial.println(bleMass, 8);  // Print mass data to serial for debug

    //update mass characteristic to phone
    if (phoneConnected) {
      int16_t mass_scaled = (int16_t) round(bleMass * 10.0f);
      pMassChar->setValue((uint8_t*)&mass_scaled, sizeof(mass_scaled));
      pMassChar->notify();
    }

    //Draw Display
    u8g2.firstPage();
    do {
      u8g2.setFont(u8g2_font_helvB08_tf);

      //Small Battery + Connections
      char topLine[32];
      snprintf(topLine, sizeof(topLine),
               "Batt:%3i%%  P:%c S:%c%c",
               battLevel(),
               phoneConnected ? 'Y' : 'N',
               switchConnected ? 'Y' : 'N',
               shotInProgress ? '*' : ' '
              );
      u8g2.drawStr(0, 16, topLine);

      //Big mass reading
      char weightChar[10];
      dtostrf(bleMass, 5, 1, weightChar);
      strcat(weightChar, "g");
      u8g2.setFont(u8g2_font_helvB24_tf);
      u8g2.drawStr(ALIGN_CENTER(weightChar), 50, weightChar);
    } while (u8g2.nextPage());
  }

  if (digitalRead(Tare_Button) == LOW) {  // Check if Tare Button Pressed
    tare();
    Serial.println("Tare button pressed.");
  }

  //Cycle_Button for start/stop shot
  checkCycleButton();

   //Halt shot at target mass
  if (shotInProgress && bleMass >= targetMass) {
    stopShot();
    Serial.println("Target Mass Reached.");
  }
    
    //Deep Sleep Routine
  if (digitalRead(Sleep_Button) == LOW) { 
    Serial.println("Sleep Time.");
    u8g2.firstPage();
    do {
      u8g2.setFont(u8g2_font_helvB08_tf);
      u8g2.drawStr(0, 16, "Sleep Time.");
    } while (u8g2.nextPage());
    esp_deep_sleep_enable_gpio_wakeup(1ULL << 2, ESP_GPIO_WAKEUP_GPIO_LOW);
    delay(1000);
    u8g2.setPowerSave(1);
    esp_deep_sleep_start();
  }

  //Log Mass Stream to Phone
  if (logState && phoneConnected) {
    if (millis() - lastLogTime >= logInterval) {
      int16_t mass_scaled = (int16_t) round(bleMass * 10.0f);
      pLoggingChar->setValue((uint8_t*)&mass_scaled, sizeof(mass_scaled));
      pLoggingChar->notify();
      lastLogTime = millis();
      Serial.println("Logging is on.");
    }
  }
}
