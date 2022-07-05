# HTTP/1.1 implementation

The default implementation for HTTP/1.1 is available as a [plugin](https://git.omegazero.org/omegazero/omz-proxy3/src/branch/master/http1) ([JAR download](https://drone.omegazero.org/build-artifacts/java/org.omegazero.proxy:http1)).

This plugin should always be added, because HTTP/1.1 is often used as fallback if other HTTP versions are not available, for example because the client or an upstream server does not support any other HTTP versions. Without any plugin for any HTTP implementation, the proxy will reject all connections.

## Configuration

### Plugin Configuration Object

Configuration ID: `http1`

| Name | Type | Description | Required | Default value |
| --- | --- | --- | --- | --- |
| enable | boolean | Whether HTTP/1 support should be enabled (cannot change during runtime). | no | `true` |

### HTTP Engine Configuration Object

Configuration ID: `HTTP1`

All common HTTP engine parameters are supported (see [configuration file options](Configuration_file)).

### Upstream server protocol configuration

All upstream servers are marked as supporting HTTP/1 by default, unless `upstreamServerProtocols` (or similar configuration options in other plugins) is explicitly overriden in the configuration. The protocol name for HTTP/1 is `"http/1.1"`.

If an upstream server is selected that is not marked as supporting HTTP/1, but the client is using HTTP/1, a `505 HTTP Version Not Supported` error is returned to the client.

