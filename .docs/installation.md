# Installation

*omz-proxy3* requires Java 8 or above and these dependencies which need to be added to the classpath: [omz-java-lib](https://git.omegazero.org/omegazero/omz-java-lib), [omz-net-lib](https://git.omegazero.org/omegazero/omz-net-lib), [omz-http-lib](https://git.omegazero.org/omegazero/omz-http-lib) and [JSON-java](https://github.com/stleary/JSON-java) (see release notes for minimum versions).

A JAR file containing the proxy and all dependencies is available [here](https://drone.omegazero.org/build-artifacts/java/org.omegazero.proxy:omz-proxy-all).

You will also need to add a plugin containing a HTTP implementation: [HTTP/1.1](HTTP_implementations/HTTP_1.1) should always be added, [HTTP/2](HTTP_implementations/HTTP_2) is also available (plugins are loaded from the `plugins/` directory by default, relative to the process working directory). Without any HTTP implementation plugin, the proxy does not understand HTTP and will reject all connections (in practice, the proxy will not start at all if there are no other plugins requesting to start any networking instance).

Run using this command to start the proxy (this is a minimal example; substitute `(version)` with the version you downloaded):
```bash
java -cp "omz-proxy-all-(version).jar" org.omegazero.proxy.core.ProxyMain --config "{}"
```
See [Command line arguments](Command_line_arguments) for possible arguments.
