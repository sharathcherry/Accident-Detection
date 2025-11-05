#include "soc/soc.h"
#include "soc/rtc_cntl_reg.h"
#include <BluetoothSerial.h>
#include <Wire.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>

// Bluetooth
BluetoothSerial SerialBT;
const char* deviceName = "ESP32_AccidentDetector";

// MPU6050 Sensor
Adafruit_MPU6050 mpu;

// Sensor readings
float accelX, accelY, accelZ;
float gyroX, gyroY, gyroZ;
float totalAccel = 0;
float totalGyro = 0;

// Accident Detection Thresholds (matching your app's expectations)
const float ACCEL_THRESHOLD = 12.0;      // m/s²
const float GYRO_THRESHOLD = 150.0;      // deg/s
const float IMPACT_THRESHOLD = 15.0;     // Combined impact

// Detection state
bool accidentDetected = false;
unsigned long accidentTime = 0;
const unsigned long ALERT_DURATION = 5000;

// Timing
unsigned long lastSendTime = 0;
const unsigned long SEND_INTERVAL = 100; // Send every 100ms

// LED Pin
const int LED_PIN = 2;

// Baseline calibration
float baselineAccelZ = 9.81;
bool isCalibrated = false;

// Reset functions
void resetI2C() {
  Wire.end();
  delay(100);
  pinMode(23, OUTPUT);  // SDA = GPIO 23
  pinMode(22, OUTPUT);  // SCL = GPIO 22
  for(int i = 0; i < 10; i++) {
    digitalWrite(22, HIGH);
    delayMicroseconds(5);
    digitalWrite(22, LOW);
    delayMicroseconds(5);
  }
  Wire.begin(23, 22);  // SDA=23, SCL=22
  Wire.setClock(100000);
  delay(100);
}

void resetMPU6050() {
  // Full device reset
  Wire.beginTransmission(0x68);
  Wire.write(0x6B);
  Wire.write(0x80);
  Wire.endTransmission(true);
  delay(200);
  
  // Wake up and use PLL
  Wire.beginTransmission(0x68);
  Wire.write(0x6B);
  Wire.write(0x01);
  Wire.endTransmission(true);
  delay(100);
  
  // Clear sleep mode
  Wire.beginTransmission(0x68);
  Wire.write(0x6B);
  Wire.write(0x00);
  Wire.endTransmission(true);
  delay(100);
  
  // Set sample rate
  Wire.beginTransmission(0x68);
  Wire.write(0x19);
  Wire.write(0x07);
  Wire.endTransmission(true);
  delay(50);
  
  // Configure accelerometer
  Wire.beginTransmission(0x68);
  Wire.write(0x1C);
  Wire.write(0x00);
  Wire.endTransmission(true);
  delay(50);
  
  // Configure gyroscope
  Wire.beginTransmission(0x68);
  Wire.write(0x1B);
  Wire.write(0x00);
  Wire.endTransmission(true);
  delay(50);
}

void calibrateSensor() {
  float sumZ = 0;
  int samples = 50;
  
  for(int i = 0; i < samples; i++) {
    sensors_event_t accel, gyro, temp;
    mpu.getEvent(&accel, &gyro, &temp);
    sumZ += accel.acceleration.z;
    delay(20);
  }
  
  baselineAccelZ = sumZ / samples;
  isCalibrated = true;
  Serial.println("   Baseline Z-axis: " + String(baselineAccelZ, 2) + " m/s²");
}

void setup() {
  // Disable brownout detector
  WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);
  
  Serial.begin(115200);
  delay(2000);
  
  Serial.println("\n╔════════════════════════════════════════╗");
  Serial.println("║  ESP32 ACCIDENT DETECTION SYSTEM      ║");
  Serial.println("║  Android App Integration              ║");
  Serial.println("╚════════════════════════════════════════╝\n");
  
  // Setup LED
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);
  
  // Reset I2C bus
  Serial.print("Resetting I2C bus... ");
  resetI2C();
  Serial.println("✓ DONE");
  
  // Reset MPU6050
  Serial.print("Resetting MPU6050... ");
  resetMPU6050();
  Serial.println("✓ DONE");
  
  // Initialize MPU6050
  Serial.print("Initializing MPU6050... ");
  if (!mpu.begin(0x68, &Wire)) {
    Serial.println("❌ FAILED!");
    Serial.println("Trying address 0x69...");
    if (!mpu.begin(0x69, &Wire)) {
      Serial.println("❌ FAILED on both addresses!");
      while(1) {
        digitalWrite(LED_PIN, !digitalRead(LED_PIN));
        delay(500);
      }
    }
  }
  Serial.println("✓ SUCCESS");
  
  // Configure sensor
  mpu.setAccelerometerRange(MPU6050_RANGE_16_G);
  mpu.setGyroRange(MPU6050_RANGE_500_DEG);
  mpu.setFilterBandwidth(MPU6050_BAND_21_HZ);
  
  Serial.println("\n📊 Sensor Configuration:");
  Serial.println("   Accelerometer Range: ±16G");
  Serial.println("   Gyroscope Range: ±500°/s");
  Serial.println("   Filter Bandwidth: 21 Hz");
  
  // Initialize Bluetooth
  Serial.print("\nInitializing Bluetooth... ");
  delay(500);
  if (!SerialBT.begin(deviceName)) {
    Serial.println("❌ FAILED!");
    while(1) {
      digitalWrite(LED_PIN, !digitalRead(LED_PIN));
      delay(200);
    }
  }
  Serial.println("✓ SUCCESS");
  Serial.println("Device Name: " + String(deviceName));
  
  // Calibration
  Serial.println("\n⏳ Calibrating... Keep sensor still!");
  delay(1000);
  calibrateSensor();
  
  Serial.println("\n✓ System Ready!");
  Serial.println("════════════════════════════════════════");
  Serial.println("🔍 DETECTION ACTIVE");
  Serial.println("📱 Waiting for Android app connection...");
  Serial.println("════════════════════════════════════════\n");
  
  // Flash LED to indicate ready
  for(int i = 0; i < 3; i++) {
    digitalWrite(LED_PIN, HIGH);
    delay(100);
    digitalWrite(LED_PIN, LOW);
    delay(100);
  }
}

void loop() {
  // Read sensor data
  sensors_event_t accel, gyro, temp;
  mpu.getEvent(&accel, &gyro, &temp);
  
  // Store values
  accelX = accel.acceleration.x;
  accelY = accel.acceleration.y;
  accelZ = accel.acceleration.z;
  
  gyroX = gyro.gyro.x * (180.0 / PI);
  gyroY = gyro.gyro.y * (180.0 / PI);
  gyroZ = gyro.gyro.z * (180.0 / PI);
  
  // Calculate total acceleration (removing gravity)
  float accelX_adj = accelX;
  float accelY_adj = accelY;
  float accelZ_adj = accelZ - baselineAccelZ;
  totalAccel = sqrt(accelX_adj*accelX_adj + accelY_adj*accelY_adj + accelZ_adj*accelZ_adj);
  
  // Calculate total gyro magnitude
  totalGyro = sqrt(gyroX*gyroX + gyroY*gyroY + gyroZ*gyroZ);
  
  // Accident detection
  bool newAccident = false;
  
  // Check for high acceleration
  if (totalAccel > ACCEL_THRESHOLD) {
    newAccident = true;
    Serial.println("\n🚨 HIGH ACCELERATION DETECTED!");
    Serial.println("   Magnitude: " + String(totalAccel, 2) + " m/s²");
  }
  
  // Check for high rotation
  if (totalGyro > GYRO_THRESHOLD) {
    newAccident = true;
    Serial.println("\n🚨 HIGH ROTATION DETECTED!");
    Serial.println("   Magnitude: " + String(totalGyro, 2) + " °/s");
  }
  
  // Check combined impact
  float impactScore = (totalAccel / ACCEL_THRESHOLD) + (totalGyro / GYRO_THRESHOLD);
  if (impactScore > 1.5) {
    newAccident = true;
    Serial.println("\n🚨 SEVERE IMPACT DETECTED!");
    Serial.println("   Impact Score: " + String(impactScore, 2));
  }
  
  // Trigger accident alert
  if (newAccident && !accidentDetected) {
    accidentDetected = true;
    accidentTime = millis();
    triggerAccidentAlert();
  }
  
  // Reset accident flag after duration
  if (accidentDetected && (millis() - accidentTime > ALERT_DURATION)) {
    accidentDetected = false;
    digitalWrite(LED_PIN, LOW);
    Serial.println("✓ Alert cleared - System monitoring...\n");
  }
  
  // Send data via Bluetooth (format expected by Android app)
  unsigned long currentTime = millis();
  if (currentTime - lastSendTime >= SEND_INTERVAL) {
    sendSensorData();
    lastSendTime = currentTime;
  }
  
  // Blink LED during accident
  if (accidentDetected) {
    digitalWrite(LED_PIN, (millis() / 200) % 2);
  }
  
  delay(10);
}

void triggerAccidentAlert() {
  Serial.println("\n╔════════════════════════════════════════╗");
  Serial.println("║     ⚠️  ACCIDENT DETECTED! ⚠️          ║");
  Serial.println("╚════════════════════════════════════════╝");
  Serial.println("Timestamp: " + String(millis()/1000.0, 2) + " seconds");
  Serial.println("Acceleration: X=" + String(accelX,2) + " Y=" + String(accelY,2) + " Z=" + String(accelZ,2));
  Serial.println("Gyroscope: X=" + String(gyroX,2) + " Y=" + String(gyroY,2) + " Z=" + String(gyroZ,2));
  Serial.println("Total Accel: " + String(totalAccel,2) + " m/s²");
  Serial.println("Total Gyro: " + String(totalGyro,2) + " °/s");
  Serial.println("════════════════════════════════════════\n");
  
  // Send accident alert to Android app (format it expects)
  if (SerialBT.hasClient()) {
    SerialBT.println("ALERT:ACCIDENT_DETECTED");
    SerialBT.println("TIME:" + String(millis()));
    SerialBT.println("ACCEL_TOTAL:" + String(totalAccel, 2));
    SerialBT.println("GYRO_TOTAL:" + String(totalGyro, 2));
    SerialBT.println("---");
  }
  
  digitalWrite(LED_PIN, HIGH);
}

void sendSensorData() {
  // Format for MainActivity.parseSensorData()
  // Your app expects: "ACCEL: X.XX" and "GYRO: Y.YY"
  String dataString = "ACCEL:" + String(totalAccel, 2) + 
                     " GYRO:" + String(totalGyro, 2) +
                     " STATUS:" + String(accidentDetected ? "ALERT" : "OK");
  
  // Send via Bluetooth
  if (SerialBT.hasClient()) {
    SerialBT.println(dataString);
  } else {
    // Periodic connection status
    static unsigned long lastStatusTime = 0;
    if (millis() - lastStatusTime > 10000) {
      Serial.println("📱 Waiting for Android app connection...");
      lastStatusTime = millis();
    }
  }
  
  // Print to Serial (only when not in accident mode)
  if (!accidentDetected) {
    Serial.print("📊 Accel: " + String(totalAccel, 2) + " m/s² | ");
    Serial.println("Gyro: " + String(totalGyro, 2) + " °/s");
  }
}
