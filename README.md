# omz-proxy3



## Installation

*omz-proxy3* requires Java 8 or above and these three dependencies which need to be added to the classpath: [omz-java-lib](https://git.omegazero.org/omz-infrastructure/omz-java-lib), [omz-net-lib](https://git.omegazero.org/omz-infrastructure/omz-net-lib) and [JSON-java](https://github.com/stleary/JSON-java).

When all four JAR files are present, this command can be run to quickly start the proxy with default settings (note that you may need to adjust the file names of the JAR files):
```bash
java -cp "omz-proxy3.jar:omz-java-lib.jar:omz-net-lib.jar:json-java.jar" org.omegazero.proxy.core.ProxyMain
```

## Command line arguments

Command line arguments have the standard *omz-lib* parameter format:
- Key-value pairs of the format `--[key] [value]` (without the square brackets) **OR**
- A single dash with the key name `-[key]` for setting boolean options to *true* (effectively short for `--[key] true`)

The following command line options are available:

| Name | Type | Description | Required | Default value |
| --- | --- | --- | --- | --- |
| logFile | string | The file path of the log file, or "null" if no log file should be created. | no | "log" |
| logLevel | string / number | The log level. Numbers 5 (highest, most log message) to -1 (no log messages) or "trace" (highest) to "fatal" (lowest, equivalent to "0"). | no | null `(INFO)` |
| configFile | string | The file path of the configuration file. | no | "config.json" |
| config | string | The content of the configuration file, if a file cannot be used. If this is provided, `configFile` is ignored. | no | null |
| pluginDir | string | The directory or directories where plugins should be loaded from. Multiple directories are separated by two colons (`::`). | no | "plugins" |
| dirPlugins | boolean | If plugins should be able to be loaded from directories instead of JAR files. | no | false |
| exitOnDoubleFault | boolean | (advanced) If the JVM should be terminated if an exception is thrown in the uncaught exception handler. This should only be set to `false` for debugging. | no | true |

### Example

```bash
java -cp "...." org.omegazero.proxy.core.ProxyMain --logLevel warn --configFile data/config.json -dirPlugins
```

Sets the log level to `warn`, allows plugins to be loaded from directories and specifies that the configuration file should be loaded from "data/config.json".

## Configuration file

The configuration file is in JSON format and loaded from "config.json" in the working directory by default.

The JSON root element must be an object and can have any of the following properties:

| Name | Type | Description | Required | Default value |
| --- | --- | --- | --- | --- |
| bindAddress | string | The local address to bind to. | no | null |
| backlog | number | The connection backlog. 0 to let the system choose a default value. | no | 0 |
| portsPlain | array(number) | The list of ports to accept plaintext connections on. | no | `empty` |
| portsTls | array(number) | The list of ports to accept SSL/TLS-encrypted connections on. | no | `empty` |
| tlsAuth | object / array(object) | A list of TLS authentication information (keys/certificates etc). Each object has two required arguments "key" and "cert", which must be strings representing the file path to the TLS server key and certificate (-chain), respectively. An optional "servername" string is used as a domain name for TLS Server Name Indication. A value of "default" (the default value) indicates that this key/certificate pair should be used when no other entry is suitable for the SNI request. A key/certificate pair is selected when the requested server name matches the "servername" string or is a subdomain of it. | no | `empty` |
| tlsAuthReloadInterval | number | The time in seconds between reloading all configured TLS key/certificate pairs. Reloading is disabled if this value is 0. | no | 0 |
| connectionIdleTimeout | number | The time in seconds to keep a connection with no traffic before it is closed. | no | 300 |
| errdocFiles | object | Additional error document files to load. The key is the MIME-type of the error document, the value is the file path. By default, only a built-in error document of type `text/html` is available. Which error document is served to the client is based on the `Accept` HTTP request header. | no | `empty` |
| upstreamServerAddress | string | The address of the default upstream server where requests will be proxied to. | no | "localhost" |
| upstreamServerPortPlain | number | The port number where the default upstream server is listening for plaintext connections. | no | 80 |
| upstreamServerPortTLS | number | The port number where the default upstream server is listening for TLS connections. | no | 443 |
| upstreamConnectionTimeout | number | The connection timeout in seconds when connecting to an upstream server. | no | 30 |
| enableHeaders | boolean | Whether the proxy should add HTTP headers when proxying HTTP messages (for example "Via" and "X-Request-Id"). Note that this does not prevent plugins from adding HTTP headers. | no | true |
| trustedCertificates | array(string) | List of file paths of CA certificates to trust when making outgoing TLS connections. | no | `empty` |
| pluginConfig | object | Contains plugin configuration objects. See [omz-proxy3-plugins](https://git.omegazero.org/omz-infrastructure/omz-proxy3-plugins) for more information. | no | `empty` |

### Example

```json
{
	"portsPlain": [80],
	"portsTls": [443],
	"tlsAuth": {
		"key": "tls/private.key",
		"cert": "tls/cert.crt"
	},
	"upstreamServerAddress": "192.168.0.11",
	"errdocFiles": {
		"application/json": "errdocs/errdoc.json"
	}
}
```

This configuration listens on the default local address and HTTP/HTTPS ports and forwards all requests to "192.168.0.11" and the default HTTP ports. It contains a single key/certificate pair for TLS which is served to all TLS clients. Finally, it adds an additional error document of type "application/json".

## System Properties

*omz-proxy3* reads several system properties for advanced configuration. All names below are prepended with "org.omegazero.proxy." (e.g. "something.someOption" becomes "org.omegazero.proxy.something.someOption"). All properties are evaluated at class initialization of the containing class unless otherwise specified.

| Name | Type | Description | Default value |
| --- | --- | --- | --- |
| shutdownTimeout | number | The maximum time in milliseconds to wait for non-daemon threads to exit before forcibly terminating the JVM. This value is evaluated when the shutdown procedure starts. | 2000 |
| sni.maxCacheNameLen | number | The maximum server name length to cache for SNI. | 64 |
| sni.maxCacheMappings | number | The maximum number of entries in the SNI name cache. | 4096 |



