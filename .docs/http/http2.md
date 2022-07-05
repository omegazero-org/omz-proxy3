# HTTP/2 implementation

The default implementation for HTTP/2 is available as a [plugin](https://git.omegazero.org/omegazero/omz-proxy3/src/branch/master/http2) ([JAR download](https://drone.omegazero.org/build-artifacts/java/org.omegazero.proxy:http2)).

## Configuration

### Plugin Configuration Object

Configuration ID: `http2`

| Name | Type | Description | Required | Default value |
| --- | --- | --- | --- | --- |
| enable | boolean | Whether HTTP/2 support should be enabled by registering the "h2" TLS ALPN option (cannot change during runtime). | no | `true` |

### HTTP Engine Configuration Object

Configuration ID: `HTTP2`

All common HTTP engine parameters except `requestTimeout` are supported (see [configuration file options](Configuration_file)), in addition to the ones listed below.

| Name | Type | Description | Required | Default value |
| --- | --- | --- | --- | --- |
| maxFrameSize | number | The maximum HTTP/2 frame payload size in bytes (HTTP/2 setting: MAX_FRAME_SIZE). | no | `16384` (http/2 default) |
| maxDynamicTableSize | number | The maximum size in bytes of the HPACK dynamic table used by the decoder (HTTP/2 setting: HEADER_TABLE_SIZE). | no | `4096` (http/2 default) |
| initialWindowSize | number | The initial flow control window size in bytes (HTTP/2 setting: INITIAL_WINDOW_SIZE). | no | `65535` (http/2 default) |
| maxConcurrentStreams | number | The maximum number of concurrent streams (HTTP/2 setting: MAX_CONCURRENT_STREAMS). Should be lower or equal than the setting value of the upstream server. | no | `100` |
| useHuffmanEncoding | boolean | Whether to compress header strings with Huffman Coding. | no | `true` |
| closeWaitTimeout | number | The close-wait timeout for closed streams in seconds. | no | `5` |
| disablePromiseRequestLog | boolean | Disable request log of server push requests. | no | value of `disableDefaultRequestLog` |

### Upstream server protocol configuration

To actually enable proxying HTTP/2, the upstream server must support HTTP/2 and be marked as such in the configuration.

With the default configuration of a single upstream server, this is done by adding the string `"http/2"` to the array `upstreamServerProtocols` in the proxy configuration. If a plugin is used that may select a different upstream server, the configuration is likely also different (see the respective plugin documentation on how to add `"http/2"` as a supported protocol).

If an upstream server is selected that is not marked as supporting HTTP/2, but the client is using HTTP/2, a stream error with error code *HTTP_1_1_REQUIRED* is returned to the client. Modern browsers will retry the request with HTTP/1.1 in that case (for which the [HTTP/1.1](HTTP_1.1) implementation is required).

