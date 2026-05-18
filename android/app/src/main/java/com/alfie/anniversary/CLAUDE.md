# com.alfie.anniversary/

Capacitor Android package: main activity and native sensor bridge plugin.

## Index

| File                      | Contents (WHAT)                                                   | Read When (WHEN)                                              |
| ------------------------- | ----------------------------------------------------------------- | ------------------------------------------------------------- |
| `MainActivity.java`       | Capacitor entry point; no custom logic                            | Debugging Capacitor initialization or plugin registration     |
| `SensorBridgePlugin.java` | Native GPS + rotation-vector bridge; quaternion delta gating      | Modifying sensor delivery, fallback timing, or IPC rate       |
| `README.md`               | Event channels, plugin registration, fallback contract, threading | Understanding sensor bridge design or diagnosing Logcat gaps  |
