# Flight Tracker

Watches the sky over a location using live ADS-B data and explains what is happening, not just where planes are.

**Detectors**
- Emergency squawks (7700 / 7600 / 7500) and ADS-B emergency status
- Go-arounds: an airliner descends on approach, does not land, and climbs away
- Holding: an aircraft keeps turning in one direction (cumulative heading change ≥ 360°)
- Rare aircraft types (A380, 747, An-124, C-5, ...)

**Data source**: [adsb.lol](https://adsb.lol) public API by default. The input format is the same `aircraft.json` that dump1090/readsb produce, so a home RTL-SDR receiver can be plugged in by passing its URL.

## Run
Java 17+, no dependencies.

**Web app**: run `WebApp` from the project root (in IntelliJ, just run `src/WebApp.java`), then open http://localhost:8080. Live map, alert feed, and per-aircraft track history.

```
java src/WebApp.java                        # from the project root

cd src
java FlightTracker.java                     # command-line version; default: Vanderbilt, Nashville, 25 nm
java FlightTracker.java 36.14 -86.80 15     # lat lon radius(nm)
java DetectorTests.java                     # simulated-track tests
```

(Running a multi-file program with `java X.java` needs Java 22+. On 17–21, compile first: `javac *.java`, then `java FlightTracker`.)
