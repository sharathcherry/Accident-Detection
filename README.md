# Accident Detection System

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://developer.android.com)
[![ESP](https://img.shields.io/badge/hardware-ESP-red.svg)](https://www.espressif.com/)

## 🚗 Overview

The **Accident Detection App** is a sophisticated safety system engineered for vehicular use, combining dedicated ESP-powered external sensors with an intelligent Android application. This innovative solution provides robust, automated accident detection and emergency notification capabilities designed to save lives when every second counts.

## ✨ Key Features

### 🔧 Hardware Integration
- **ESP-Powered Sensor Module**: External sensor unit equipped with high-precision accelerometers and gyroscopes
- **Superior Accuracy**: Monitors vehicle dynamics independently of phone position with greater precision than built-in phone sensors
- **Wireless Communication**: Real-time data transmission via [Bluetooth/Wi-Fi] to the Android app
- **Robust Design**: Resilient hardware backbone optimized for automotive environments

### 🤖 Intelligent Detection
- **ML-Powered Analysis**: ONNX Runtime-accelerated machine learning model for accurate collision detection
- **Multi-Sensor Fusion**: Combines ESP sensor data with phone's GPS and internal sensors
- **Pattern Recognition**: Advanced algorithms identify severe collision signatures
- **Adjustable Sensitivity**: User-configurable threshold settings to minimize false positives

### 🛡️ Safety Features
- **Automatic Emergency Response**: Sends SMS alerts with GPS location to pre-configured contacts
- **Countdown Override**: Customizable delay with "I'm Safe" option for false alarms
- **Manual Testing Mode**: Safe simulation of the entire alert pipeline without triggering real emergencies
- **Threshold Testing**: Verify system responsiveness to different impact severities
- **Location Tracking**: Precise GPS coordinates included in all emergency notifications

## 🏗️ System Architecture

```
┌─────────────────┐         Wireless          ┌──────────────────┐
│  ESP Sensor     │◄──────(BT/WiFi)──────────►│  Android App     │
│  Module         │                           │                  │
│  - Accelerometer│                           │  - ONNX ML Model │
│  - Gyroscope    │                           │  - GPS Tracking  │
│  - Data TX      │                           │  - SMS Handler   │
└─────────────────┘                           └──────────────────┘
                                                        │
                                                        ▼
                                               ┌─────────────────┐
                                               │ Emergency SMS   │
                                               │ to Contacts     │
                                               └─────────────────┘
```

## 📋 Requirements

### Hardware
- ESP32/ESP8266 module (or compatible)
- MPU6050 or similar accelerometer/gyroscope sensor
- Power supply for ESP module
- Android smartphone (version X.X or higher)

### Software
- Android Studio (for app development)
- Arduino IDE or PlatformIO (for ESP firmware)
- ONNX Runtime library
- Android SDK version XX+

## 🚀 Getting Started

### ESP Module Setup

1. **Hardware Assembly**
   ```
   Connect the sensors to your ESP module according to the wiring diagram
   ```

2. **Flash Firmware**
   ```bash
   # Using Arduino IDE or PlatformIO
   # Upload the firmware from /esp-firmware directory
   ```

3. **Configure Connection**
   ```cpp
   // Set your connection parameters in config.h
   #define WIFI_SSID "your_ssid"
   #define BLUETOOTH_NAME "AccidentDetector"
   ```

### Android App Setup

1. **Clone the Repository**
   ```bash
   git clone https://github.com/yourusername/accident-detection.git
   cd accident-detection
   ```

2. **Open in Android Studio**
   ```
   File → Open → Select the project directory
   ```

3. **Configure Emergency Contacts**
   ```
   Navigate to Settings → Add emergency contacts with phone numbers
   ```

4. **Pair with ESP Module**
   ```
   Enable Bluetooth/WiFi → Scan for devices → Connect to your ESP module
   ```

## 🧪 Testing the System

### Manual Testing Mode
The app includes a comprehensive testing interface to verify functionality without triggering real emergencies:

1. Navigate to **Settings → Test Mode**
2. Tap **Simulate Accident** to test the complete alert pipeline
3. Verify that:
   - ESP data is being received
   - ML model processes the simulated input
   - Countdown timer initiates
   - Test notifications are displayed (no SMS sent in test mode)

### Threshold Calibration
Fine-tune detection sensitivity for your specific vehicle:

1. Access **Settings → Sensitivity Settings**
2. Adjust impact threshold sliders
3. Use **Test Threshold** feature to validate different severity levels
4. Save your preferred configuration

## 📱 Usage

### Normal Operation
1. Mount the ESP sensor module securely in your vehicle
2. Launch the Android app and ensure ESP connection is active
3. The system runs automatically in the background
4. In case of an accident:
   - Alert countdown begins
   - Tap "I'm Safe" to cancel if it's a false alarm
   - If not canceled, emergency SMS with GPS location is sent automatically

### Alert Cancellation
- You have **X seconds** (configurable) to cancel the alert after detection
- Simply tap the prominent "I'm Safe" button
- Alert will be logged but no emergency contacts will be notified

## ⚙️ Configuration

### App Settings
- **Emergency Contacts**: Add/remove emergency contact numbers
- **Countdown Duration**: Adjust the time window for alert cancellation (15-60 seconds)
- **Sensitivity Level**: Configure detection thresholds (Low/Medium/High)
- **GPS Update Frequency**: Balance accuracy and battery life
- **Test Mode**: Enable safe testing without triggering real alerts

### ESP Configuration
- **Sampling Rate**: Adjust sensor data collection frequency
- **Transmission Interval**: Configure wireless data update rate
- **Power Management**: Sleep mode and battery optimization settings

## 🔒 Privacy & Permissions

This app requires the following permissions:
- **Location**: For GPS coordinates in emergency alerts
- **SMS**: To send emergency notifications
- **Bluetooth/WiFi**: For ESP module communication
- **Sensors**: To access phone's internal sensors (supplementary)

All data is processed locally on your device. No information is transmitted to external servers.

## 🛠️ Technology Stack

- **Android**: Kotlin/Java
- **Machine Learning**: ONNX Runtime
- **Hardware**: ESP32/ESP8266
- **Sensors**: MPU6050 (or equivalent)
- **Communication**: Bluetooth Low Energy / WiFi
- **Location Services**: Android GPS API

## 🤝 Contributing

We welcome contributions! Please follow these steps:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🆘 Support

If you encounter any issues or have questions:
- Open an issue on GitHub
- Contact: [your-email@example.com]
- Documentation: [Link to detailed docs]

## ⚠️ Disclaimer

This system is designed to assist in emergency situations but should not be considered a replacement for professional safety equipment or emergency services. Users are responsible for ensuring the system is properly configured and maintained. Always follow local traffic laws and safety regulations.

## 🙏 Acknowledgments

- ONNX Runtime team for the ML framework
- ESP community for hardware support
- Contributors and testers who helped improve the system

## 📊 Roadmap

- [ ] iOS companion app
- [ ] Cloud-based contact synchronization
- [ ] Multi-language support
- [ ] Enhanced ML model with more collision types
- [ ] Integration with vehicle OBD-II systems
- [ ] Voice alert capabilities

---

**Made with ❤️ for safer roads**
