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
package org.omegazero.proxy.util;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.omegazero.common.util.PropertyUtil;
import org.omegazero.net.client.params.ConnectionParameters;
import org.omegazero.net.client.params.TLSConnectionParameters;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.core.Proxy;
import org.omegazero.proxy.net.UpstreamServer;

public class ProxyUtil {

	/**
	 * The namespace of default client manager IDs to use.
	 *
	 * @since 3.10.1
	 */
	public static final String clientImplNamespace = PropertyUtil.getString("org.omegazero.proxy.clientImplNamespace", "tcp.client");
	/**
	 * The namespace of server implementation names to use.
	 *
	 * @since 3.10.1
	 */
	public static final String serverImplNamespace = PropertyUtil.getString("org.omegazero.proxy.serverImplNamespace", "tcp.server");


	/**
	 * Checks if the given <b>hostname</b> matches the expression (<b>expr</b>) containing the wildcard character '<code>*</code>'. The wildcard character matches any character,
	 * including '<code>.</code>' (dot). If no wildcard character is used in <b>expr</b>, this function behaves exactly the same as {@link String#equals(Object)}.
	 * <p>
	 * <b>Examples:</b>
	 * <table>
	 * <tr>
	 * <td><b>expr</b></td>
	 * <td><b>hostname</b></td>
	 * <td><b>Return value</b></td>
	 * </tr>
	 * <tr>
	 * <td>*.example.com</td>
	 * <td>foo.example.com</td>
	 * <td><code>true</code></td>
	 * </tr>
	 * <tr>
	 * <td>*.example.com</td>
	 * <td>foo.example.org</td>
	 * <td><code>false</code></td>
	 * </tr>
	 * <tr>
	 * <td>subdomain.*.net</td>
	 * <td>subdomain.example.net</td>
	 * <td><code>true</code></td>
	 * </tr>
	 * <tr>
	 * <td>*subdomain.*.net</td>
	 * <td>othersubdomain.example.net</td>
	 * <td><code>true</code></td>
	 * </tr>
	 * </table>
	 * 
	 * @param expr
	 * @param hostname
	 * @return <code>true</code> if the given hostname matches the expression
	 * @implNote This function currently cannot handle certain edge cases, for example: expr = <code>a.n*n.a</code> and hostname = <code>a.nnnn.a</code> returns <code>false</code>,
	 * even though it should return <code>true</code>. Given that such a hostname expression is quite unlikely to be used in actual configurations, this is fine for now.
	 */
	public static boolean hostMatches(String expr, String hostname) {
		int exprlen = expr.length();
		int hnlen = hostname.length();
		int exprindex = 0;
		int hnindex = 0;
		int lastbranch = -1;
		boolean reset = false;
		while(true){
			if(hnindex >= hnlen)
				break;
			char exprchar = expr.charAt(exprindex);
			if(exprchar == '*'){
				if(exprindex < exprlen - 1 && hnindex < hnlen - 1){
					if(expr.charAt(exprindex + 1) == hostname.charAt(hnindex + 1)){
						lastbranch = exprindex;
						exprindex++;
					}
					hnindex++;
				}else if(exprindex < exprlen - 1 && hnindex == hnlen - 1){ // at the end of hostname string, but not at end of expr -> missing characters
					return false;
				}else // at end of expr or hostname and expr is *
					hnindex++;
			}else if(exprchar == hostname.charAt(hnindex)){
				exprindex++;
				hnindex++;
			}else
				reset = true;
			if(exprindex >= exprlen && hnindex < hnlen)
				reset = true;
			if(reset){
				if(lastbranch >= 0){
					exprindex = lastbranch;
				}else
					return false;
				reset = false;
			}
		}
		return true;
	}


	/**
	 * Checks if the <b>writeStream</b> (the connection where data is being written to) is connected (or about to be) and is buffering write calls
	 * ({@link SocketConnection#isWritable()} returns <code>false</code>). If that is the case, reads from the <b>readStream</b> will be blocked using
	 * {@link SocketConnection#setReadBlock(boolean)} until the <b>writeStream</b> is writable again.
	 * <p>
	 * This method uses the <code>onWrite</code> callback of the <code>SocketConnection</code>, which should not be used when this function is in use.
	 * 
	 * @param writeStream The connection where data is being written to
	 * @param readStream The connection where reads should be blocked until <b>writeStream</b> is writable again
	 * @throws ClassCastException If <b>writeStream</b> is not a {@link org.omegazero.net.socket.AbstractSocketConnection}
	 */
	public static void handleBackpressure(SocketConnection writeStream, SocketConnection readStream) {
		synchronized(((org.omegazero.net.socket.AbstractSocketConnection) writeStream).getWriteLock()){
			if((!writeStream.hasConnected() || writeStream.isConnected()) /* not disconnected */ && !writeStream.isWritable()){
				if(readStream.isConnected())
					readStream.setReadBlock(true);
				writeStream.once("writable", (org.omegazero.common.event.runnable.GenericRunnable.A0) () -> {
					if(readStream.isConnected())
						readStream.setReadBlock(false);
				});
			}
		}
	}


	/**
	 * Connects to an upstream server over TCP, plaintext or encrypted using TLS.
	 * 
	 * @param proxy The proxy instance to connect with
	 * @param downstreamSecurity Whether the client connection was encrypted
	 * @param userver The upstream server to connect to
	 * @param alpn The protocols to advertise using TLS ALPN
	 * @return The new connection
	 * @throws IOException If an IO error occurred
	 * @since 3.3.1
	 * @deprecated Since 3.7.1, use {@link #connectUpstreamTCP(Proxy, SocketConnection, boolean, UpstreamServer, String...)} to pass the client connection
	 */
	@Deprecated
	public static SocketConnection connectUpstreamTCP(Proxy proxy, boolean downstreamSecurity, UpstreamServer userver, String... alpn) throws IOException {
		return connectUpstreamTCP(proxy, null, downstreamSecurity, userver, alpn);
	}

	/**
	 * Connects to an upstream server over TCP, plaintext or encrypted using TLS, depending on the context and server settings.
	 * 
	 * @param proxy The proxy instance to connect with
	 * @param downstreamConnection The client connection
	 * @param downstreamSecurity Whether the client connection was encrypted
	 * @param userver The upstream server to connect to
	 * @param alpn The protocols to advertise using TLS ALPN
	 * @return The new connection
	 * @throws IOException If an IO error occurred
	 * @since 3.7.1
	 * @see Proxy#connection(String, ConnectionParameters, SocketConnection)
	 */
	public static SocketConnection connectUpstreamTCP(Proxy proxy, SocketConnection downstreamConnection, boolean downstreamSecurity, UpstreamServer userver, String... alpn)
			throws IOException {
		String cmidNs = userver.getClientImplOverride() != null ? userver.getClientImplOverride() : clientImplNamespace;
		String type;
		ConnectionParameters params;
		InetAddress remoteAddress = userver.getAddress();
		InetAddress localAddress = userver.getLocalAddress();
		if(localAddress == null){
			if(remoteAddress instanceof java.net.Inet4Address)
				localAddress = proxy.getConfig().getDefaultOutboundLocalAddressV4();
			else if(remoteAddress instanceof java.net.Inet6Address)
				localAddress = proxy.getConfig().getDefaultOutboundLocalAddressV6();
			else
				throw new IOException("Unknown address type of address: " + remoteAddress);
		}
		InetSocketAddress localSocketAddress = new InetSocketAddress(localAddress, 0);
		if((downstreamSecurity || userver.getPlainPort() <= 0) && userver.getSecurePort() > 0){
			type = cmidNs + ".tls";
			params = new TLSConnectionParameters(new InetSocketAddress(remoteAddress, userver.getSecurePort()), localSocketAddress);
			((TLSConnectionParameters) params).setAlpnNames(alpn);
			((TLSConnectionParameters) params).setSniOptions(new String[] { remoteAddress.getHostName() });
		}else if(userver.getPlainPort() > 0){
			type = cmidNs + ".plain";
			params = new ConnectionParameters(new InetSocketAddress(remoteAddress, userver.getPlainPort()), localSocketAddress);
		}else
			throw new IOException("Upstream server " + remoteAddress + " neither has a plain nor a secure port set");

		return proxy.connection(type, params, downstreamConnection);
	}
}
