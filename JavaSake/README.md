# JavaSake

Java port of the SAKE handshake protocol used by 700-series Medtronic pumps.

Mirrors the public surface of [PythonSake](https://github.com/OpenMinimed/PythonSake)
so it can be consumed by the [JavaPumpConnector](https://github.com/OpenMinimed/JavaPumpConnector)
Android application (and any other JVM project that needs to drive a SAKE handshake).

## Build

```sh
./gradlew build
```

## Test

```sh
./gradlew test
```

Requires JDK 11 or later.

## License

GPL-3.0. See [`LICENSE`](LICENSE).
