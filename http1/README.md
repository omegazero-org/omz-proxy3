## omz-proxy3/http1

A plugin containing the default HTTP/1 implementation.

Prebuilt JARs: <https://drone.omegazero.org/build-artifacts/java/org.omegazero.proxy:http1>

## Configuration

### Plugin Configuration Object

Configuration ID: `http1`

| Name | Type | Description | Required | Default value |
| --- | --- | --- | --- | --- |
| enable | boolean | Whether HTTP/1 support should be enabled (cannot change during runtime). | no | `true` |

### HTTP Engine Configuration Object

Configuration ID: `HTTP1`

All [common HTTP engine parameters](https://git.omegazero.org/omegazero/omz-proxy3#common-http-engine-parameters) are supported.

### Upstream server protocol configuration

All upstream servers are marked as supporting HTTP/1 by default, unless `upstreamServerProtocols` (or similar configuration options in other plugins) is explicitly overriden in the configuration. The protocol name for HTTP/1 is `"http/1.1"`.

If an upstream server is selected that is not marked as supporting HTTP/1, but the client is using HTTP/1, a `505 HTTP Version Not Supported` error is returned to the client.

