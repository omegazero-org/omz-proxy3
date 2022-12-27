/*
 * Copyright (C) 2021 omegazero.org
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Covered Software is provided under this License on an "as is" basis, without warranty of any kind,
 * either expressed, implied, or statutory, including, without limitation, warranties that the Covered Software
 * is free of defects, merchantable, fit for a particular purpose or non-infringing.
 * The entire risk as to the quality and performance of the Covered Software is with You.
 */
package org.omegazero.proxy.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;

import org.omegazero.common.logging.Logger;

/**
 * Contains information about another server where requests can be forwarded to.
 */
public class UpstreamServer implements java.io.Serializable {

	private static final long serialVersionUID = 1L;

	private static final Logger logger = Logger.create();

	/**
	 * An immutable collection containing the single default supported procotol, {@code http/1.1}, used if no protocols are passed in the constructor.
	 */
	public static final Collection<String> PROTOCOLS_DEFAULT = java.util.Collections.singleton("http/1.1");
	/**
	 * An immutable collection representing support for all protocols.
	 */
	public static final Collection<String> PROTOCOLS_ALL = java.util.Collections.singleton(null);


	private InetAddress address;
	private final int addressTTL;
	private final int plainPort;
	private final int securePort;
	private final Collection<String> protocols;

	private transient long addressExpiration;

	/**
	 * Creates an {@code UpstreamServer} instance with no parameters set, and protocols set to {@link #PROTOCOLS_ALL}.
	 * <p>
	 * This may be used by plugins to create "virtual" upstream servers, where the plugin returns this instance of an {@code UpstreamServer} in {@code selectUpstreamServer} and then
	 * responds to requests with that upstream server. When an {@code UpstreamServer} with <b>address</b> set to {@code null} is selected for a request, all request events are run normally
	 * as if the request is being proxied, but no upstream connection actually exists. Any handler must respond in the {@code onHTTPRequestEnded} event or before, otherwise an error is returned.
	 *
	 * @since 3.7.2
	 */
	public UpstreamServer() {
		this(null, -1, -1, -1, PROTOCOLS_ALL);
	}

	/**
	 * Creates an {@code UpstreamServer} instance.
	 *
	 * @param address The address of the server
	 * @param plainPort The port on which the server listens for plaintext connections. {@code -1} means there is no such port
	 * @param securePort The port on which the server listens for encrypted connections. {@code -1} means there is no such port
	 */
	public UpstreamServer(InetAddress address, int plainPort, int securePort) {
		this(address, -1, plainPort, securePort, null);
	}

	/**
	 * Creates an {@code UpstreamServer} instance.
	 *
	 * @param address The address of the server
	 * @param plainPort The port on which the server listens for plaintext connections. {@code -1} means there is no such port
	 * @param securePort The port on which the server listens for encrypted connections. {@code -1} means there is no such port
	 * @param protocols The list of protocol names the server supports
	 * @since 3.3.1
	 */
	public UpstreamServer(InetAddress address, int plainPort, int securePort, Collection<String> protocols) {
		this(address, -1, plainPort, securePort, protocols);
	}

	/**
	 * Creates an {@code UpstreamServer} instance.
	 *
	 * @param address The address of the server
	 * @param addressTTL The time in seconds to cache a resolved address. See {@link #getAddressTTL}
	 * @param plainPort The port on which the server listens for plaintext connections. {@code -1} means there is no such port
	 * @param securePort The port on which the server listens for encrypted connections. {@code -1} means there is no such port
	 * @param protocols The list of protocol names the server supports. If {@code null}, the {@linkplain #PROTOCOLS_DEFAULT default set} is used
	 * @since 3.4.1
	 */
	public UpstreamServer(InetAddress address, int addressTTL, int plainPort, int securePort, Collection<String> protocols) {
		this.address = address;
		this.addressTTL = addressTTL;
		this.plainPort = plainPort;
		this.securePort = securePort;
		if(protocols != null)
			this.protocols = protocols;
		else
			this.protocols = PROTOCOLS_DEFAULT;

		if(addressTTL >= 0)
			this.addressExpiration = System.nanoTime() + addressTTL * 1000000000L;
	}


	private void reresolveAddressIfNecessary() {
		if(this.addressTTL < 0)
			return;
		long time = System.nanoTime();
		if(this.addressExpiration > 0 && time <= this.addressExpiration)
			return;
		String hostname = this.address.getHostName();
		try{
			this.address = InetAddress.getByName(hostname);
			this.addressExpiration = time + this.addressTTL * 1000000000L;
			if(logger.debug())
				logger.debug("Re-resolved address '", hostname, "': ", this.address.getHostAddress());
		}catch(UnknownHostException e){
			logger.warn("Error while re-resolving address '", hostname, "', using existing address: ", e.toString());
		}
	}


	/**
	 * Returns the address of this <code>UpstreamServer</code>. May be {@code null}.
	 * <p>
	 * If {@code addressTTL} was set in the constructor, this method may re-resolve the configured {@code address} if necessary.
	 * 
	 * @return The address of this <code>UpstreamServer</code>
	 * @throws NullPointerException If no {@code address} was passed in the constructor
	 */
	public InetAddress getAddress() {
		if(this.address != null)
			this.reresolveAddressIfNecessary();
		return this.address;
	}

	/**
	 * Returns the number of seconds to cache a resolved {@code InetAddress}. After this time expires, the address is re-resolved using {@link InetAddress#getByName}. {@code -1} means
	 * there is no timeout. Note that the {@code InetAddress} implementation may also cache name resolutions internally (see {@link InetAddress}).
	 *
	 * @return The address TTL in seconds
	 */
	public int getAddressTTL() {
		return this.addressTTL;
	}

	/**
	 * Returns the port on which this {@code UpstreamServer} listens for plaintext connections. {@code -1} means there is no such port.
	 *
	 * @return The plaintext port
	 */
	public int getPlainPort() {
		return this.plainPort;
	}

	/**
	 * Returns the port on which this {@code UpstreamServer} listens for encrypted connections. {@code -1} means there is no such port.
	 *
	 * @return The encrypted port
	 */
	public int getSecurePort() {
		return this.securePort;
	}

	/**
	 * Returns {@code true} if this {@code UpstreamServer} was configured to support the given protocol name.
	 *
	 * @param proto The name of the protocol
	 * @return {@code true} if the given protocol is supported
	 * @since 3.3.1
	 */
	public boolean isProtocolSupported(String proto) {
		if(this.protocols == PROTOCOLS_ALL)
			return true;
		return this.protocols.contains(proto);
	}

	@Override
	public int hashCode() {
		return this.address.hashCode() + 36575 * this.plainPort + 136575 * this.securePort;
	}

	@Override
	public boolean equals(Object o) {
		if(o == null || !(o instanceof UpstreamServer))
			return false;
		UpstreamServer u = (UpstreamServer) o;
		return u.address.equals(this.address) && u.plainPort == this.plainPort && u.securePort == this.securePort;
	}

	@Override
	public String toString() {
		return "UpstreamServer{" + this.address + ":" + this.plainPort + "/" + this.securePort + "}";
	}
}
