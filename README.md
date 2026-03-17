# BlueCove Bluetooth Tester

A simple Swing UI to test Bluetooth scanning using the [ultreia.io fork of BlueCove](https://gitlab.com/ultreiaio/bluecove) (`io.ultreia:bluecove:2.1.1`).

## Requirements

- Windows 10/11 with Bluetooth hardware
- Java 11+ (JDK installed)
- IntelliJ IDEA (recommended) **or** Maven installed

## How to Run

### Option A — IntelliJ IDEA (easiest)

1. Open IntelliJ IDEA
2. **File → Open** → select this folder
3. IntelliJ will detect `pom.xml` and import it as a Maven project (downloads dependencies automatically)
4. Open `src/main/java/com/bluetoothtest/BluetoothTesterUI.java`
5. Click the green **Run** arrow next to `main()`

### Option B — Maven command line

```bash
mvn compile exec:java -Dexec.mainClass=com.bluetoothtest.BluetoothTesterUI
```

### Option C — Build a runnable JAR

```bash
mvn package
java -jar target/bluetooth-tester-1.0-SNAPSHOT-jar-with-dependencies.jar
```

## What it does

- Shows your local Bluetooth adapter name and address on startup
- **Scan for Devices** button starts a GIAC inquiry (discovers all nearby Bluetooth Classic devices)
- Discovered devices appear in the left panel with name and MAC address
- Log panel shows detailed output including device class codes
