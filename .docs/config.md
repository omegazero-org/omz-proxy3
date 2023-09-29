# Configuration file

The configuration file is in JSON format and loaded from "config.json" in the working directory by default (this can be changed with the `configFile` [command line argument](Command_line_arguments)).

The JSON root element must be an object and can have any of the following properties:

| Name | Type | Description | Required | Default value | Since |
| --- | --- | --- | --- | --- | --- |
| bindAddresses | array(string) | A list of local addresses to bind to. A single element of `null` indicates that the system should choose a default address. | no | `[null]` | 3.1.0 |
| backlog | number | The connection backlog. 0 to let the system choose a default value. | no | `0` | 3.1.0 |
| portsPlain | array(number) | The list of ports to accept plaintext connections on. | no | `[80]` | 3.1.0 |
| portsTls | array(number) | The list of ports to accept SSL/TLS-encrypted connections on. | no | (empty) | 3.1.0 |
| tlsAuth | object / array(object) | A list of TLS authentication information (keys/certificates etc). Each object has two required arguments "key" and "cert", which must be strings representing the file path to the TLS server key and certificate (-chain), respectively. An optional "servername" string is used as a domain name for TLS Server Name Indication. A value of "default" (the default value) indicates that this key/certificate pair should be used when no other entry is suitable for the SNI request. A key/certificate pair is selected when the requested server name matches the "servername" string or is a subdomain of it. | no | (empty) | 3.1.0 |
| tlsAuthReloadInterval | number | The time in seconds between reloading all configured TLS key/certificate pairs. Reloading is disabled if this value is 0. | no | `0` | 3.1.0 |
| connectionIdleTimeout | number | The time in seconds to keep a connection with no traffic before it is closed. | no | `300` | 3.1.0 |
| errdocFiles | object | Additional error document files to load. The key is the MIME-type of the error document, the value is the file path. By default, only a built-in error document of type `text/html` is available. Which error document is served to the client is based on the `Accept` HTTP request header. | no | (empty) | 3.1.0 |
| defaultOutboundLocalAddressV4 | string | The default local address to use to connect to upstream servers over IPv4. | no | none (system default) | 3.10.4 |
| defaultOutboundLocalAddressV6 | string | The default local address to use to connect to upstream servers over IPv6. | no | none (system default) | 3.10.4 |
| upstreamServerAddress | string | The address of the default upstream server where requests will be proxied to. | no | `"localhost"` | 3.1.0 |
| upstreamServerAddressTTL | number | The number of seconds the IP address resolved from `upstreamServerAddress` is valid. If this value is negative, the address is considered valid forever. It is recommended to only set this value if `upstreamServerAddress` is not a literal IP address. The underlying Java library uses its own cache for IP address caching (InetAddress Cache), which may need to be configured as well to prevent unexpectedly long caching times. | no | `-1` | 3.4.1 |
| upstreamServerLocalAddress | string | The local address to use to connect to the default upstream server. | no | defaultOutboundLocalAddressV4/6 | 3.10.4 |
| upstreamServerPortPlain | number | The port number where the default upstream server is listening for plaintext connections. | no | `8080` | 3.1.0 |
| upstreamServerPortTLS | number | The port number where the default upstream server is listening for TLS connections. | no | `8443` | 3.1.0 |
| upstreamServerProtocols | array(string) | A list of protocol names the default upstream server supports. The list of supported protocols is checked by the running HTTP engine and a specific protocol name is usually also defined by it. | no | `["http/1.1"]` | 3.3.1 |
| upstreamServerClientImplOverride | string | An override for the client manager IDs to use to connect to the server (overrides the `.clientImplNamespace` system property). | no | none | 3.10.2 |
| trustedCertificates | array(string) | List of file paths of CA certificates to trust when making outgoing TLS connections. | no | (empty) | 3.1.0 |
| workerThreadCount | number | The maximum number of worker threads. A negative value sets the maximum worker thread count to the number of available processors. | no | `-1` | 3.7.1 |
| pluginConfig | object | Contains plugin configuration objects. See [omz-proxy3-plugins](https://git.omegazero.org/omegazero/omz-proxy3-plugins) for more information. | no | (empty) | 3.1.0 |
| engineConfig | object | Contains configuration objects passed to the specified HTTP engine. The key of each object in this object is the name of the HTTP engine to pass the object to; this may either be the fully qualified class name or only the class name. | no | (empty) | 3.3.1 |
| defaultEngineConfig | object | Properties in this object will be copied to each HTTP engine configuration object, if not already set. See **Common HTTP engine parameters** for common properties. | no | (empty) | 3.3.1 |

### Common HTTP engine parameters

This is a list of common properties for the default or a specific HTTP engine configuration object:

| Name | Type | Description | Required | Default value | Since |
| --- | --- | --- | --- | --- | --- |
| disableDefaultRequestLog | boolean | Disable the request log, containing the client IP address and HTTP request data. | no | `false` | 3.3.1 |
| upstreamConnectionTimeout | number | The connection timeout in seconds when connecting to an upstream server. | no | `30` | 3.3.1 |
| enableHeaders | boolean | Whether the proxy should add HTTP headers when proxying HTTP messages (for example "Via" and "X-Request-Id"). Note that this does not prevent plugins from adding HTTP headers. | no | `true` | 3.3.1 |
| maxHeaderSize | number | The maximum size of a HTTP message header (the start line and all headers) in bytes. | no | `8192` | 3.6.1 |
| requestTimeout | number | The maximum time in seconds to wait for a request to finish before responding with status 408. | no | `5` | 3.6.1 |
| responseTimeout | number | The maximum time in seconds to wait for a response from an upstream server before responding with status 504. This must not equal `upstreamConnectionTimeout`, because it would cause undefined behavior. | no | `60` | 3.6.1 |
| maxStreamsPerServer | number | The maximum number of concurrent active requests (streams) to an upstream server for a single client. In HTTP/2, this is the sum of the *MAX_CONCURRENT_STREAMS* setting of all open connections; in HTTP/1.1, this is the number of connections (since HTTP/1.1 only supports a single concurrent request per connection). If the value is exceeded, no new connections will be created. | `100` | 3.10.1 |

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
	"upstreamServerPortPlain": 80,
	"upstreamServerPortTLS": 443,
	"errdocFiles": {
		"application/json": "errdocs/errdoc.json"
	}
}
```

This configuration listens on the default local address and HTTP/HTTPS ports and forwards all requests to "192.168.0.11" and the default HTTP ports. It contains a single key/certificate pair for TLS which is served to all TLS clients. And it adds an additional error document of type "application/json".

To completely disable the default request logging, the following may be added to the configuration above:
```json
	"defaultEngineConfig": {
		"disableDefaultRequestLog": true
	},
```

Example of a HTTP-implementation-specific option ([http2](HTTP_implementations/HTTP_2)):
```json
	"engineConfig": {
		"HTTP2": {
			"disablePromiseRequestLog": true
		}
	},
```

When using a plugin such as [virtual-host](https://git.omegazero.org/omegazero/omz-proxy3-plugins/src/branch/master/virtual-host), the configuration might contain something like this:
```json
	"pluginConfig": {
		"vhost": {
			"hosts": [
				{
					"hostname": "example.com",
					"address": "192.168.0.11"
				}
			]
		}
	},
```
In this case, `upstreamServerAddress` may be set to `null` to return an error message on any other requested domain name.

