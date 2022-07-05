# Command line arguments

Command line arguments have the standard *omz-lib* parameter format:
- Key-value pairs of the format `--[key] [value]` (without the square brackets) **OR**
- A single dash with the key name `-[key]` for setting boolean options to *true* (effectively short for `--[key] true`)

The following command line options are available:

| Name | Type | Description | Required | Default value | Since |
| --- | --- | --- | --- | --- | --- |
| logFile | string | The file path of the log file, or "null" if no log file should be created. | no | `"log"` | 3.1.0 |
| logLevel | string / number | The log level. Numbers 5 (highest, most log message) to -1 (no log messages) or "trace" (highest) to "fatal" (lowest, equivalent to "0"). | no | `null` (`INFO`) | 3.1.0 |
| configFile | string | The file path of the configuration file. | no | `"config.json"` | 3.1.0 |
| config | string | The content of the configuration file, if a file cannot be used. If this is provided, `configFile` is ignored. | no | `null` | 3.1.0 |
| pluginDir | string | The directory or directories where plugins should be loaded from. Multiple directories are separated by two colons (`::`). | no | `"plugins"` | 3.1.0 |
| dirPlugins | boolean | If plugins should be able to be loaded from directories instead of JAR files. | no | `false` | 3.1.0 |
| configFileReload | boolean | Reload the configuration file after it has been modified. This option has no effect when no configuration file is used. | no | `false` | 3.1.1 |

### Example

```bash
java -cp "...." org.omegazero.proxy.core.ProxyMain --logLevel warn --configFile data/config.json -dirPlugins
```

Sets the log level to `warn`, allows plugins to be loaded from directories and specifies that the configuration file should be loaded from "data/config.json".


## System Properties

*omz-proxy3* reads several system properties for advanced configuration. All names below are prepended with "org.omegazero.proxy." (e.g. "something.someOption" becomes "org.omegazero.proxy.something.someOption"). All properties are evaluated at class initialization of the containing class unless otherwise specified.

These may be passed as command line arguments as JVM arguments as follows: `-Dorg.omegazero.proxy.something.someOption=value`. JVM arguments are passed before the main class name.

| Name | Type | Description | Default value | Since |
| --- | --- | --- | --- | --- |
| shutdownTimeout | number | The maximum time in milliseconds to wait for non-daemon threads to exit before forcibly terminating the JVM. Renamed to `org.omegazero.common.runtime.shutdownTimeout` in version 3.7.1. | `2000` | 3.1.0 |
| sni.maxCacheNameLen | number | The maximum server name length to cache for SNI. | `64` | 3.1.0 |
| sni.maxCacheMappings | number | The maximum number of entries in the SNI name cache. | `4096` | 3.1.0 |
| http.iaddrHashSalt | number | A 32-bit salt used for generating request IDs. | `42` | 3.3.1 |
| http.requestId.separator | string | The separator string for multiple `X-Request-ID` values. | `","` | 3.7.1 |
| http.requestId.timeLength | int | The number of hex characters to use for the time part of `X-Request-ID` values. The value is padded with `"0"`'s. If this value is `0`, the number of characters is automatic (behavior before v3.6.2), if `-1`, the time part is disabled. | `0` | 3.7.1 |
| http.requestId.timeBase | int64 | A number (milliseconds) to subtract from the absolute time value in `X-Request-ID` values. | `0` | 3.7.1 |
| net.upstreamSocketErrorDebug | boolean | Whether log messages of upstream connection failures should be printed with log level *DEBUG* instead of *WARN*. Similar to [`org.omegazero.net.socketErrorDebug`](https://docs.omegazero.org/javadoc/omz-net-lib/org/omegazero/net/common/NetCommon.html#SOCKET_ERROR_DEBUG). | `false` | 3.6.1 |

